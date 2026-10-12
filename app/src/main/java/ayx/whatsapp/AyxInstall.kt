package ayx.whatsapp

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AyX installation registration.
 * Registers this app installation with the AyX backend so the admin panel
 * can see the device (phone number + device name + app version).
 *
 * No announcements, no dialogs — registration only.
 */
object AyxInstall {
    private const val TAG = "AyxInstall"
    private const val CRED_PREFS = "ayx_install"
    private const val KEY_INSTALL_ID = "installation_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val BACKEND_BASE = "http://127.0.0.1:3101"
    private const val BACKEND_PUBLIC = "https://api.imayx.in"

    private fun credPrefs(ctx: Context) = try {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            CRED_PREFS, masterKey, ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "Encrypted prefs unavailable: ${e.message}")
        ctx.getSharedPreferences(CRED_PREFS, Context.MODE_PRIVATE)
    }

    fun getAccessToken(ctx: Context): String? =
        try { credPrefs(ctx).getString(KEY_ACCESS_TOKEN, null) } catch (e: Exception) { null }

    fun getInstallationId(ctx: Context): String? =
        try { credPrefs(ctx).getString(KEY_INSTALL_ID, null) } catch (e: Exception) { null }

    /** Get device name like "Samsung SM-A065F". */
    fun deviceName(): String {
        val mfr = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: ""
        return "$mfr $model".trim().ifBlank { "Android device" }
    }

    /**
     * Get the WhatsApp phone number with country code (e.g. +911234567890).
     * Derived from the Baileys session JID via the local gateway.
     */
    suspend fun phoneNumber(): String? = withContext(Dispatchers.IO) {
        try {
            val jid = GatewayClient.getMe().orEmpty() // e.g. 911234567890@s.whatsapp.net
            val digits = jid.substringBefore("@").filter { it.isDigit() }
            if (digits.length >= 10) "+$digits" else null
        } catch (e: Exception) {
            Log.w(TAG, "phoneNumber failed: ${e.message}")
            null
        }
    }

    /**
     * Ensure this installation is registered with the backend.
     * Safe to call on every startup; skips if already registered.
     * Updates phone_number/device_name if they changed.
     */
    suspend fun ensureRegistered(ctx: Context) = withContext(Dispatchers.IO) {
        try {
            val existing = getAccessToken(ctx)
            val phone = phoneNumber()
            val device = deviceName()
            if (existing != null) {
                // Already registered — update phone/device via heartbeat-like call
                updateProfile(ctx, phone, device)
                return@withContext
            }
            for (base in listOf(BACKEND_BASE, BACKEND_PUBLIC)) {
                try {
                    Log.i(TAG, "Registering with $base")
                    val conn = (URL("$base/v1/installations/register").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 8000
                        readTimeout = 10000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                    val payload = JSONObject().apply {
                        put("app_version", try {
                            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                        } catch (e: Exception) { "unknown" })
                        put("platform", "android")
                        put("locale", java.util.Locale.getDefault().toString())
                        phone?.let { put("phone_number", it) }
                        put("device_name", device)
                    }.toString()
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                    val code = conn.responseCode
                    if (code == 200 || code == 201) {
                        val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        val id = obj.optString("installation_id")
                        val token = obj.optString("access_token")
                        if (id.isNotBlank() && token.isNotBlank()) {
                            credPrefs(ctx).edit()
                                .putString(KEY_INSTALL_ID, id)
                                .putString(KEY_ACCESS_TOKEN, token)
                                .apply()
                            Log.i(TAG, "Registered with $base id=${id.take(8)}...")
                            return@withContext
                        }
                    }
                    Log.w(TAG, "Register $base HTTP $code")
                } catch (e: Exception) {
                    Log.w(TAG, "Register $base failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureRegistered failed: ${e.message}")
        }
    }

    /** Update phone/device on the backend for an existing registration. */
    private fun updateProfile(ctx: Context, phone: String?, device: String) {
        val token = getAccessToken(ctx) ?: return
        for (base in listOf(BACKEND_BASE, BACKEND_PUBLIC)) {
            try {
                val conn = (URL("$base/v1/installations/profile").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val payload = JSONObject().apply {
                    phone?.let { put("phone_number", it) }
                    put("device_name", device)
                }.toString()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                if (conn.responseCode == 200) {
                    Log.i(TAG, "Profile updated via $base")
                    return
                }
            } catch (e: Exception) { /* try next base */ }
        }
    }
}
