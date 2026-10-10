package ayx.whatsapp.group

import ayx.whatsapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Thrown for non-revoked API errors. `retryAfterMs` is set for 429 responses. */
class AyxApiException(val code: Int, message: String, val retryAfterMs: Long = 0) : Exception(message)

/** Thrown when the server reports 401 {"code":"installation_revoked"}. Never retried. */
class InstallationRevokedException : Exception("installation revoked by server")

/**
 * Raw-HttpURLConnection client for the AyX Group backend (same style as GatewayClient).
 *
 * Security notes:
 * - BASE_URL is a PUBLIC hostname, not a secret — safe to hardcode.
 * - The per-installation access token is passed in-memory only; it is NEVER logged,
 *   never included in exception messages, and never written anywhere except the
 *   encrypted store (see AyxGroupStore).
 */
object AyxGroupApi {
    /** Public base URL — NOT a secret. */
    const val BASE_URL = "https://api.imayx.in"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_TRIES = 4
    private val BACKOFF_MS = longArrayOf(2000L, 4000L, 8000L)

    data class Registration(val installationId: String, val accessToken: String)
    data class Bootstrap(
        val announcements: List<Announcement>,
        val statuses: List<AyxStatus>,
        val cursor: SyncCursor,
    )
    data class SyncDelta(
        val announcementsUpsert: List<Announcement>,
        val announcementsDeleted: List<String>,
        val statusesUpsert: List<AyxStatus>,
        val statusesDeleted: List<String>,
        val cursor: SyncCursor,
    )

    /** Register this install. No prior auth. Rate-limited 10/min/IP — 429 is honored. */
    suspend fun register(appVersion: String = BuildConfig.VERSION_NAME): Registration =
        withContext(Dispatchers.IO) {
            val o = request(
                "POST", "/v1/installations/register",
                JSONObject().put("app_version", appVersion).put("platform", "android"),
            )
            Registration(o.getString("installation_id"), o.getString("access_token"))
        }

    /** First-run seed: everything published + the sync cursor. */
    suspend fun bootstrap(token: String): Bootstrap = withContext(Dispatchers.IO) {
        val o = authed("GET", "/v1/group/bootstrap", token)
        Bootstrap(
            announcements = o.optJSONArray("announcements")?.toAnnouncementList().orEmpty(),
            statuses = o.optJSONArray("statuses")?.toStatusList().orEmpty(),
            cursor = o.optLong("cursor", NO_CURSOR),
        )
    }

    /** Incremental deltas since [cursor]. Upserts are idempotent by id. */
    suspend fun sync(token: String, cursor: SyncCursor): SyncDelta = withContext(Dispatchers.IO) {
        val o = authed("GET", "/v1/sync?cursor=$cursor", token)
        val an = o.optJSONObject("announcements")
        val st = o.optJSONObject("statuses")
        SyncDelta(
            announcementsUpsert = an?.optJSONArray("upsert")?.toAnnouncementList().orEmpty(),
            announcementsDeleted = an?.optJSONArray("deleted")?.toStringList().orEmpty(),
            statusesUpsert = st?.optJSONArray("upsert")?.toStatusList().orEmpty(),
            statusesDeleted = st?.optJSONArray("deleted")?.toStringList().orEmpty(),
            cursor = o.optLong("cursor", cursor),
        )
    }

    /** Rotate the access token. The old token stops working immediately. */
    suspend fun rotateToken(token: String): String = withContext(Dispatchers.IO) {
        authed("POST", "/v1/installations/token", token).getString("access_token")
    }

    private suspend fun authed(method: String, path: String, token: String, body: JSONObject? = null): JSONObject =
        request(method, path, body, token)

    /**
     * Retry policy: exponential backoff (2s/4s/8s, max 4 tries) on network errors
     * and 5xx ONLY. 429 honors Retry-After (capped at 120s). Other 4xx (including
     * 401 revoked) are never retried.
     */
    private suspend fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        var lastErr: Exception? = null
        for (tryNo in 0 until MAX_TRIES) {
            try {
                return requestOnce(method, path, body, token)
            } catch (e: InstallationRevokedException) {
                throw e // dead token — caller wipes and re-registers; never retry
            } catch (e: AyxApiException) {
                when {
                    e.code == 429 && tryNo < MAX_TRIES - 1 -> {
                        lastErr = e
                        delay(e.retryAfterMs)
                    }
                    e.code in 500..599 && tryNo < MAX_TRIES - 1 -> {
                        lastErr = e
                        delay(BACKOFF_MS[tryNo.coerceAtMost(BACKOFF_MS.size - 1)])
                    }
                    else -> throw e
                }
            } catch (e: Exception) {
                // Network failure (UnknownHostException, SocketTimeoutException, …) → backoff.
                lastErr = e
                if (tryNo < MAX_TRIES - 1) delay(BACKOFF_MS[tryNo.coerceAtMost(BACKOFF_MS.size - 1)])
            }
        }
        throw lastErr ?: AyxApiException(-1, "request failed")
    }

    private fun requestOnce(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val conn = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            // The token is sent here but NEVER logged or included in any error message.
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = readBody(conn)
            when {
                code in 200..299 -> return JSONObject(if (text.isBlank()) "{}" else text)
                code == 401 -> {
                    val errCode = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("code")
                    }.getOrNull()
                    if (errCode == "installation_revoked") throw InstallationRevokedException()
                    throw AyxApiException(401, "unauthorized")
                }
                code == 429 -> {
                    val retryAfterSec = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
                    throw AyxApiException(429, "rate limited", retryAfterMs = retryAfterSec.coerceIn(1L, 120L) * 1000L)
                }
                code in 500..599 -> throw AyxApiException(code, "server error")
                else -> throw AyxApiException(code, "request failed (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }
}
