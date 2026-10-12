package ayx.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.SecureRandom

/**
 * Gateway API authentication: per-installation bearer token.
 *
 * SECURITY DESIGN:
 * - Token is 32 bytes from SecureRandom (256 bits), generated once per install.
 * - Stored in EncryptedSharedPreferences (AES-256, Keystore-backed).
 * - Delivered to the Node.js gateway via environment variable (process-private,
 *   same UID — not visible to other apps).
 * - Never hardcoded, never in assets, never in logs, never in UI.
 * - The gateway rejects requests without the token (fail closed).
 *
 * Threat model: protects against other apps on the device accessing the
 * gateway via localhost. Does NOT protect against a rooted/compromised device
 * (an attacker with root can read process memory).
 */
object GatewayAuth {
    private const val TAG = "GatewayAuth"
    private const val FILE = "gateway_auth"
    private const val KEY_TOKEN = "api_token"

    @Volatile
    private var cachedToken: String? = null

    /**
     * Initialize on app start. Generates and persists a token on first run.
     * Safe to call multiple times. Must be called before NodeRuntime starts
     * the gateway (so the token can be passed via environment).
     */
    @Synchronized
    fun init(ctx: Context): String {
        cachedToken?.let { return it }
        val token = loadOrGenerate(ctx)
        cachedToken = token
        return token
    }

    /**
     * Returns the cached token, or null if init() was not called.
     * GatewayClient uses this to add the auth header. If null, requests
     * go without auth and the gateway will reject them (fail closed).
     */
    fun token(): String? = cachedToken

    /**
     * Rotate the token (e.g., on suspected compromise).
     * Note: the gateway must be restarted to pick up the new token.
     */
    @Synchronized
    fun rotate(ctx: Context): String {
        val token = generate()
        save(ctx, token)
        cachedToken = token
        Log.i(TAG, "gateway token rotated")
        return token
    }

    /** Clear the token (on logout/data reset). Next init() generates a fresh one. */
    @Synchronized
    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit().remove(KEY_TOKEN).apply()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
        cachedToken = null
    }

    private fun loadOrGenerate(ctx: Context): String {
        try {
            prefs(ctx).getString(KEY_TOKEN, null)?.let { existing ->
                if (existing.isNotBlank()) return existing
            }
        } catch (e: Exception) {
            Log.w(TAG, "token load failed, generating new: ${e.message}")
        }
        val token = generate()
        save(ctx, token)
        return token
    }

    private fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        // URL-safe Base64, no padding — safe for HTTP headers
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun save(ctx: Context, token: String) {
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply()
    }

    private fun prefs(ctx: Context) =
        EncryptedSharedPreferences.create(
            FILE,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            ctx.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
}
