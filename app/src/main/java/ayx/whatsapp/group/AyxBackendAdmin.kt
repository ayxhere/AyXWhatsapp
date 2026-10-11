package ayx.whatsapp.group

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-time admin account bootstrap for the embedded AyX Group backend.
 * Talks to 127.0.0.1:3101/v1/local endpoints — guarded by the per-device LOCAL_OP_TOKEN.
 */
object AyxBackendAdmin {
    private const val TAG = "AyxBackendAdmin"
    private const val BASE = "http://127.0.0.1:3101/v1/local"

    private fun localOpToken(ctx: Context): String? {
        return try {
            val prefs = EncryptedSharedPreferences.create(
                "ayx_backend",
                "ayx_backend_key",
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            prefs.getString("local_op_token", null)
        } catch (e: Exception) {
            Log.w(TAG, "cannot read local op token: ${e.message}")
            null
        }
    }

    private fun post(path: String, token: String, body: JSONObject?): JSONObject? {
        val url = URL(BASE + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Local-Token", token)
            doOutput = body != null
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val text = try {
                conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            if (code in 200..299) return JSONObject(text)
            Log.w(TAG, "bootstrap HTTP $code: $text")
            // Try to parse error JSON for a message
            return try { JSONObject(text) } catch (_: Exception) { null }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String, token: String): JSONObject? {
        val url = URL(BASE + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("X-Local-Token", token)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader().readText()
            return JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "admin/exists failed: ${e.message}")
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Returns true if an admin already exists, false if bootstrap is needed, null if backend unreachable. */
    suspend fun adminExists(ctx: Context): Boolean? = withContext(Dispatchers.IO) {
        val token = localOpToken(ctx) ?: return@withContext null
        get("/admin/exists", token)?.optBoolean("exists")
    }

    /** Creates the initial admin. Returns (success, message). */
    suspend fun createAdmin(ctx: Context, username: String, password: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val token = localOpToken(ctx)
                ?: return@withContext false to "Backend not ready — try again in a few seconds."
            val body = JSONObject().put("username", username).put("password", password)
            val resp = post("/admin/bootstrap", token, body)
                ?: return@withContext false to "Backend unreachable — is the app running?"
            if (resp.optBoolean("ok")) {
                true to "Admin \"${resp.optString("username")}\" created."
            } else {
                val err = resp.optString("error", "unknown error")
                if (err.contains("already exists")) true to "Admin already exists."
                else false to err
            }
        }

    /** Quick check: is the embedded backend responding? */
    suspend fun backendAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://127.0.0.1:3101/v1/health")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}
