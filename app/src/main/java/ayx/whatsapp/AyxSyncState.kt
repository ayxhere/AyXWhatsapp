package ayx.whatsapp

import android.content.Context
import android.os.Build

/**
 * v178/v179: home sync state kept for the backend sync endpoints.
 *
 * On every Home open the saved state is refreshed and sent through the
 * existing sync path — no extra screen, no extra step. The account
 * field comes from the live gateway identity (same parse rules as
 * MainActivity.ayxOwnPhoneE164 — only a real phone JID carries a value,
 * "<number>@s.whatsapp.net", optionally "<number>:<device>@…"); an @lid
 * identity, a foreign suffix, or a not-yet-connected gateway means
 * "no value yet" -> null, never a junk value.
 *
 * Persistence is the point: the last saved state is kept, so a Home
 * open can RETRY the send from storage even when the gateway is
 * offline that moment (a live-only read would silently skip the field
 * when /me fails). The backend payload uses ONLY the existing
 * /v1/installations/profile fields via the Activity's pinned POST —
 * no new endpoint, no new fields. OS/app version ride along in the
 * LOCAL record only (forward-compat info, not sent).
 *
 * This object never touches the session, the install token, or the
 * gateway lifecycle — it only reads /me and reads/writes its own prefs.
 */
object AyxSyncState {

    data class State(
        val phone: String?,
        val deviceName: String,
        val androidVersion: String,
        val appVersion: String?,
    )

    private const val PREFS = "ayx_sync_state"

    /** Collect fresh values. Phone is null when the gateway is not connected. */
    suspend fun collect(ctx: Context): State {
        val phone = ownPhoneE164()
        // Same device string the install paths have always sent, so the
        // website row keeps one stable value instead of churning.
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        val appVersion = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull()
        return State(phone, deviceName, androidVersion, appVersion)
    }

    /** Same parse as MainActivity.ayxOwnPhoneE164 — kept in sync by rule, not by call. */
    private suspend fun ownPhoneE164(): String? {
        return try {
            val jid = GatewayClient.getMe().trim()
            if (jid.isEmpty()) return null
            if (jid.contains("@") && !jid.endsWith("@s.whatsapp.net")) return null
            val digits = jid.substringBefore("@").substringBefore(":").filter { it.isDigit() }
            if (digits.length in 10..15) "+$digits" else null
        } catch (e: Exception) { null }
    }

    /** Persist the latest saved state (overwrites the previous one). */
    fun save(ctx: Context, id: State) {
        try {
            prefs(ctx).edit()
                .putString("phone", id.phone)
                .putString("device_name", id.deviceName)
                .putString("android_version", id.androidVersion)
                .putString("app_version", id.appVersion)
                .apply()
        } catch (e: Exception) { /* never crash Home open over prefs */ }
    }

    /** Last saved state, or null if nothing was ever saved. */
    fun load(ctx: Context): State? {
        return try {
            val p = prefs(ctx)
            if (!p.contains("device_name")) return null
            State(
                phone = p.getString("phone", null)?.ifBlank { null },
                deviceName = p.getString("device_name", "") ?: "",
                androidVersion = p.getString("android_version", "") ?: "",
                appVersion = p.getString("app_version", null),
            )
        } catch (e: Exception) { null }
    }

    /** Same encrypted-store pattern as the install stores; plain prefs fallback so a keystore failure can never block the sync. */
    private fun prefs(ctx: Context): android.content.SharedPreferences {
        return try {
            val masterKey = androidx.security.crypto.MasterKeys.getOrCreate(
                androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
            androidx.security.crypto.EncryptedSharedPreferences.create(
                PREFS, masterKey, ctx,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        } catch (e: Exception) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }
}
