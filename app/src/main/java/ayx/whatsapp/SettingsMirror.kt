package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-side encrypted mirror of the settings fields a Drive backup needs:
 * the AI API keys + the user's custom prompts (v170).
 *
 * Why this exists: since v167 the gateway NEVER returns real key values
 * (GET /settings masks them), so a backup built from gateway reads would
 * contain masks, not keys. Instead, every settings WRITE is mirrored here
 * at the single choke point all writes flow through —
 * [GatewayClient.patchSettings] — whenever the outgoing patch carries a
 * REAL (non-blank, non-mask) value for a whitelisted field. The mirror is
 * the backup's only source; [DriveBackup] encrypts its snapshot again
 * before anything leaves the device.
 *
 * Whitelist (gateway settings field names): aiApiKey, aiVisionApiKey,
 * aiVisionApiKey2, aiSarvamKey, aiLyricsApis (list), aiSystemPrompt (the
 * custom text prompt), aiReplyLang (the custom reply-language prompt —
 * there is NO separate voice-prompt field in the settings schema; the
 * same persona/language prompts drive voice replies too).
 *
 * Nothing else is ever mirrored — and by design this store can never hold
 * session/login material: the WhatsApp auth lives in the gateway's
 * private auth dir and no settings field carries it.
 */
object SettingsMirror {
    private const val TAG = "SettingsMirror"
    private const val FILE = "ayx_settings_mirror_enc"
    private const val PREFIX = "f:"
    private const val KEY_BACKUP_KEY = "drive_backup_key_b64"

    /** Key fields: mirrored only when the value is real (non-blank, non-mask). */
    private val KEY_FIELDS = listOf("aiApiKey", "aiVisionApiKey", "aiVisionApiKey2", "aiSarvamKey")

    /** Prompt fields: mirrored verbatim (a cleared prompt is a real state). */
    private val PROMPT_FIELDS = listOf("aiSystemPrompt", "aiReplyLang")

    const val LIST_FIELD = "aiLyricsApis"

    @Volatile
    private var appCtx: Context? = null

    /** Cache the application context (NodeRuntime, before the gateway serves). */
    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    /**
     * Mirror one outgoing settings patch. Called by
     * [GatewayClient.patchSettings] AFTER the gateway accepted the patch.
     * Returns true when the mirror actually changed (the caller then offers
     * [DriveBackup] an auto-sync). Mask/blank key values never overwrite —
     * the same rule as [AiKeyStore.save].
     */
    fun mirrorPatch(patch: JSONObject): Boolean {
        val p = prefs() ?: return false
        var changed = false
        val ed = p.edit()
        // v168's explicit removal also clears the mirror, so a removed key
        // can never resurrect from a later backup/restore.
        if (patch.optBoolean("aiApiKeyClear", false) && p.contains(PREFIX + "aiApiKey")) {
            ed.remove(PREFIX + "aiApiKey")
            changed = true
        }
        for (f in KEY_FIELDS) {
            if (!patch.has(f)) continue
            val v = patch.optString(f, "").trim()
            if (v.isEmpty() || AiKeyStore.isMask(v)) continue
            if (p.getString(PREFIX + f, null) != v) {
                ed.putString(PREFIX + f, v)
                changed = true
            }
        }
        for (f in PROMPT_FIELDS) {
            if (!patch.has(f)) continue
            val v = patch.optString(f, "")
            if (p.getString(PREFIX + f, null) != v) {
                ed.putString(PREFIX + f, v)
                changed = true
            }
        }
        if (patch.has(LIST_FIELD)) {
            val clean = sanitizeLyricsApis(patch.optJSONArray(LIST_FIELD))
            if (clean != null) {
                val s = clean.toString()
                if (p.getString(PREFIX + LIST_FIELD, null) != s) {
                    ed.putString(PREFIX + LIST_FIELD, s)
                    changed = true
                }
            }
        }
        if (changed) ed.apply()
        return changed
    }

    /**
     * The backup payload's `fields` object: every mirrored whitelisted
     * field, or an empty object when nothing has been mirrored yet.
     */
    fun snapshotFields(): JSONObject {
        val out = JSONObject()
        val p = prefs() ?: return out
        for (f in KEY_FIELDS + PROMPT_FIELDS) {
            p.getString(PREFIX + f, null)?.let { out.put(f, it) }
        }
        p.getString(PREFIX + LIST_FIELD, null)?.let { s ->
            runCatching { out.put(LIST_FIELD, JSONArray(s)) }
        }
        return out
    }

    fun hasAnyField(): Boolean {
        val p = prefs() ?: return false
        return (KEY_FIELDS + PROMPT_FIELDS).any { p.contains(PREFIX + it) } || p.contains(PREFIX + LIST_FIELD)
    }

    /**
     * Make the mirror match a restored backup for the fields the backup
     * carries (fields absent from the backup are left alone). A blank key
     * in the backup means "no key" and clears the mirror entry.
     */
    fun applyRestored(fields: JSONObject) {
        val p = prefs() ?: return
        val ed = p.edit()
        for (f in KEY_FIELDS) {
            if (!fields.has(f)) continue
            val v = fields.optString(f, "").trim()
            if (v.isEmpty() || AiKeyStore.isMask(v)) ed.remove(PREFIX + f) else ed.putString(PREFIX + f, v)
        }
        for (f in PROMPT_FIELDS) {
            if (fields.has(f)) ed.putString(PREFIX + f, fields.optString(f, ""))
        }
        if (fields.has(LIST_FIELD)) {
            val clean = sanitizeLyricsApis(fields.optJSONArray(LIST_FIELD))
            if (clean != null) ed.putString(PREFIX + LIST_FIELD, clean.toString())
        }
        ed.apply()
    }

    // ---- Drive backup key cache (the recovery path is the key FILE on
    // Drive; this cache only spares re-reading it on every backup) ----

    fun cachedBackupKey(): ByteArray? {
        val p = prefs() ?: return null
        val b64 = p.getString(KEY_BACKUP_KEY, null) ?: return null
        return try {
            Base64.decode(b64, Base64.NO_WRAP).takeIf { it.size == 32 }
        } catch (e: Exception) {
            null
        }
    }

    fun cacheBackupKey(key: ByteArray) {
        if (key.size != 32) return
        try {
            prefs()?.edit()?.putString(KEY_BACKUP_KEY, Base64.encodeToString(key, Base64.NO_WRAP))?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "cacheBackupKey failed: ${e.message}")
        }
    }

    /**
     * Normalize an aiLyricsApis array for the mirror/backup: entries are
     * re-built with exactly the gateway's four string fields, and entries
     * carrying a MASK as their key are dropped (a mask-keyed entry is the
     * inert residue of the pre-v168 settings migration — backing it up
     * would restore garbage). Returns null when a non-empty input consists
     * ONLY of dropped entries: the caller then keeps the previous mirror
     * instead of wiping a good list with noise. A genuinely empty input
     * array mirrors as an empty list (the user deleted every entry).
     */
    private fun sanitizeLyricsApis(arr: JSONArray?): JSONArray? {
        if (arr == null) return JSONArray()
        val out = JSONArray()
        var dropped = 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val key = e.optString("key", "")
            if (key.isNotBlank() && AiKeyStore.isMask(key.trim())) {
                dropped++
                continue
            }
            out.put(
                JSONObject()
                    .put("name", e.optString("name", ""))
                    .put("url", e.optString("url", ""))
                    .put("key", key)
                    .put("model", e.optString("model", ""))
            )
        }
        if (out.length() == 0 && dropped > 0) return null
        return out
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
