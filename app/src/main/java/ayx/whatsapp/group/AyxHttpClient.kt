package ayx.whatsapp.group

import android.content.Context
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
 * AyX HTTP networking runtime (Firebase-style: configured via AyxConfig).
 *
 * Replaces the hardcoded networking layer from AyxGroupApi. All endpoints,
 * timeouts, and retry policy come from the packaged ayx-services.json
 * configuration via AyxConfig — no hardcoded URLs or timeouts here.
 *
 * Security notes:
 * - The API base URL is a PUBLIC address, not a secret.
 * - The per-installation access token is passed in-memory only; it is NEVER
 *   logged, never included in exception messages, and never written anywhere
 *   except the encrypted credential store.
 */
object AyxHttpClient {

    /**
     * Execute an authenticated JSON API request with the configured retry policy.
     *
     * Retry: exponential backoff on network errors and 5xx ONLY.
     * 429 honors Retry-After (capped at 120s). Other 4xx (including 401
     * revoked) are never retried.
     */
    suspend fun request(
        ctx: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        ensureConfig(ctx)
        val maxTries = AyxConfig.maxRetryAttempts.coerceAtLeast(1)
        val backoff = AyxConfig.retryBackoffMs
        var lastErr: Exception? = null
        for (tryNo in 0 until maxTries) {
            try {
                return@withContext requestOnce(ctx, method, path, body, token)
            } catch (e: InstallationRevokedException) {
                throw e // dead token — caller wipes and re-registers; never retry
            } catch (e: AyxApiException) {
                when {
                    e.code == 429 && tryNo < maxTries - 1 -> {
                        lastErr = e
                        delay(e.retryAfterMs)
                    }
                    e.code in 500..599 && tryNo < maxTries - 1 -> {
                        lastErr = e
                        delay(backoff[tryNo.coerceAtMost(backoff.size - 1)])
                    }
                    else -> throw e
                }
            } catch (e: Exception) {
                // Network failure (UnknownHostException, SocketTimeoutException, …) → backoff.
                lastErr = e
                if (tryNo < maxTries - 1) delay(backoff[tryNo.coerceAtMost(backoff.size - 1)])
            }
        }
        throw lastErr ?: AyxApiException(-1, "request failed")
    }

    private fun requestOnce(
        ctx: Context,
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
    ): JSONObject {
        val url = AyxConfig.apiUrl(path)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = AyxConfig.connectionTimeoutMs
            readTimeout = AyxConfig.readTimeoutMs
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
                    // Both revoked AND unknown tokens mean the installation is dead
                    // (e.g. backend DB was reset). Wipe and re-register.
                    if (errCode == "installation_revoked" || errCode == "invalid_token") {
                        throw InstallationRevokedException()
                    }
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

    private fun ensureConfig(ctx: Context) {
        if (!AyxConfig.isLoaded()) {
            AyxConfig.load(ctx.applicationContext)
        }
    }
}
