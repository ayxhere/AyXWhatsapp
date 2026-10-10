package ayx.whatsapp.group

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AyX Group credentials, held in EncryptedSharedPreferences (AES-256).
 *
 * Stored: installation_id (opaque), access_token (secret — never logged, never
 * shown in UI, never hardcoded anywhere), sync cursor, last-sync timestamp.
 * The only cleartext constant in this feature is the PUBLIC api.imayx.in URL.
 */
object AyxGroupStore {
    private const val FILE = "ayx_group"
    private const val K_INSTALLATION_ID = "installation_id"
    private const val K_ACCESS_TOKEN = "access_token"
    private const val K_CURSOR = "cursor"
    private const val K_LAST_SYNC_AT = "last_sync_at"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun prefs(ctx: Context): SharedPreferences {
        prefs?.let { return it }
        val appCtx = ctx.applicationContext
        val masterKey = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appCtx,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { prefs = it }
    }

    fun isRegistered(ctx: Context): Boolean =
        prefs(ctx).getString(K_INSTALLATION_ID, null) != null &&
            prefs(ctx).getString(K_ACCESS_TOKEN, null) != null

    fun installationId(ctx: Context): String? = prefs(ctx).getString(K_INSTALLATION_ID, null)

    /** The secret token. Callers must never log or display the returned value. */
    fun accessToken(ctx: Context): String? = prefs(ctx).getString(K_ACCESS_TOKEN, null)

    fun cursor(ctx: Context): SyncCursor = prefs(ctx).getLong(K_CURSOR, NO_CURSOR)

    fun lastSyncAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_SYNC_AT, 0L)

    fun saveRegistration(ctx: Context, installationId: String, accessToken: String) {
        prefs(ctx).edit()
            .putString(K_INSTALLATION_ID, installationId)
            .putString(K_ACCESS_TOKEN, accessToken)
            .apply()
    }

    /** Atomically replace the token after rotation (old token is already dead server-side). */
    fun saveToken(ctx: Context, accessToken: String) {
        prefs(ctx).edit().putString(K_ACCESS_TOKEN, accessToken).apply()
    }

    /**
     * Persist the new cursor ONLY after the corresponding content writes have
     * succeeded (callers write files first, then call this).
     */
    fun saveCursor(ctx: Context, cursor: SyncCursor) {
        prefs(ctx).edit()
            .putLong(K_CURSOR, cursor)
            .putLong(K_LAST_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    /** Wipe credentials (revocation / reset). Cached content is deleted by AyxGroupSync.wipeAll. */
    fun wipe(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
