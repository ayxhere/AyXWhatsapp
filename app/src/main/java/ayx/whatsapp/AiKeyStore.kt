package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * App-side encrypted copy of the user's primary AI API key (settings.aiApiKey).
 *
 * Why this exists (v168): since v167 the gateway never returns real keys —
 * GET /settings masks aiApiKey as "••••••••" + last-4. Two app features call
 * the user's AI API DIRECTLY (not through the gateway) and were silently
 * broken by the mask: Reschedule's reminder extraction and the legacy
 * single-key fallback in GatewayClient.lyricApis() — both sent the mask as
 * the Bearer key and got 401s.
 *
 * This store keeps the real key on-device only, in EncryptedSharedPreferences
 * (AES-256, Keystore-backed — same pattern as GatewayAuth / AiImgSettings).
 * It is written ONLY when the user saves a real (non-blank, non-mask) key in
 * the API configuration UI or the Groq key dialog, and cleared by the
 * explicit "Remove key" action. It is NEVER sent anywhere except as the
 * Authorization header of the user's own AI API calls, same as before v167.
 */
object AiKeyStore {
    private const val TAG = "AiKeyStore"
    private const val FILE = "ayx_ai_key_enc"
    private const val KEY_API_KEY = "ai_api_key"

    /** The gateway's mask prefix (main.js maskKey: 8 bullets + last-4). A value
     *  starting with this is a display mask, never a usable key. */
    const val MASK_PREFIX = "••••••••"

    fun isMask(v: String): Boolean = v.startsWith(MASK_PREFIX)

    @Volatile
    private var appCtx: Context? = null

    /** Cache the application context (called at gateway startup from NodeRuntime,
     *  and opportunistically by every ctx-taking method below). */
    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    /** Save the REAL key. Callers must pass a non-blank, non-mask value;
     *  blanks and masks are ignored so a masked settings round-trip can
     *  never overwrite a good key with its own mask. */
    fun save(ctx: Context, key: String) {
        init(ctx)
        val k = key.trim()
        if (k.isEmpty() || isMask(k)) return
        try {
            prefs()?.edit()?.putString(KEY_API_KEY, k)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /** The stored real key, or "" when none. Uses the context cached by
     *  init()/save()/get(ctx) — for callers that have no Context of their
     *  own (GatewayClient). NodeRuntime inits this at app startup, before
     *  the gateway (and therefore settings fetches) can serve anything. */
    fun getCached(): String = read()

    fun get(ctx: Context): String {
        init(ctx)
        return read()
    }

    fun clear(ctx: Context) {
        init(ctx)
        clear()
    }

    fun clear() {
        try {
            prefs()?.edit()?.remove(KEY_API_KEY)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "clear failed: ${e.message}")
        }
    }

    private fun read(): String =
        try {
            prefs()?.getString(KEY_API_KEY, "")?.trim() ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${e.message}")
            ""
        }

    private fun prefs(): SharedPreferences? {
        val ctx = appCtx ?: return null
        return try {
            EncryptedSharedPreferences.create(
                FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "prefs unavailable: ${e.message}")
            null
        }
    }
}
