package ayx.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted settings backup to the user's Google Drive (v170; v171
 * replaced the SAF folder picker with a WhatsApp-style Google-account
 * flow — same payload, crypto, key file and restore semantics).
 *
 * The user's locked decisions, implemented exactly:
 *  - SETTINGS ONLY (API keys + custom prompts, mirrored app-side by
 *    [SettingsMirror]). Login/session material NEVER enters the payload —
 *    the WhatsApp session never leaves the device (v148 rule).
 *  - NO password (he rejected one, twice). The payload is AES-256-GCM
 *    encrypted with a random app-generated key; the KEY FILE lives in
 *    Drive beside the backup as a SEPARATE file, so a format / new phone
 *    restores by signing in with the same Google account — no folder
 *    picking, ever (v171).
 *  - Transport is Drive v3 REST over the account added once in Settings
 *    (see [GoogleDriveAuth], drive.file scope: the app only ever sees
 *    the "AyX Backup" folder it creates). The two logical files stay
 *    [BACKUP_FILE] + [KEY_FILE]; v170 backups left in a manually picked
 *    SAF folder are superseded, not migrated.
 *
 * All functions are BLOCKING (network + crypto); callers run them on IO.
 */
object DriveBackup {
    private const val TAG = "DriveBackup"
    const val BACKUP_FILE = "ayx-settings-backup.ayxbak"
    const val KEY_FILE = "ayx-backup-key.ayxkey"
    private const val BACKUP_MIME = "application/octet-stream"
    private const val KEY_MIME = "application/json"
    private const val PAYLOAD_APP_VERSION = "7.09"

    private const val FOLDER_NAME = "AyX Backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    private const val PREFS = "ayx_drive_backup"
    private const val P_ACCOUNT_EMAIL = "account_email"
    private const val P_AUTO_SYNC = "auto_sync"
    private const val P_LAST_OK = "last_backup_ok_ms"
    private const val P_LAST_ERROR = "last_error"

    /** A failure with a user-readable message (shown as-is in the UI). */
    class BackupError(message: String) : Exception(message)

    /** A Drive HTTP failure; [code] is the HTTP status. */
    private class DriveHttpException(val code: Int) : Exception("Drive HTTP $code")

    data class Status(
        val accountEmail: String?,
        val autoSync: Boolean,
        val lastBackupAt: Long,
        val lastError: String?,
    )

    data class Peek(val createdAt: Long, val appVersion: String, val fieldCount: Int)

    @Volatile
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    // ---------------- UI state (plain prefs: nothing secret lives here) ---

    fun status(ctx: Context): Status {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The live Google account is the truth; the pref is its display
        // cache (and what auto-sync gates on). Reconcile when they differ.
        val live = GoogleDriveAuth.signedInAccount(ctx)?.email
        val stored = p.getString(P_ACCOUNT_EMAIL, null)
        if (live != null && live != stored) {
            p.edit().putString(P_ACCOUNT_EMAIL, live).apply()
        }
        return Status(
            accountEmail = live ?: stored,
            autoSync = p.getBoolean(P_AUTO_SYNC, false),
            lastBackupAt = p.getLong(P_LAST_OK, 0L),
            lastError = p.getString(P_LAST_ERROR, null)?.ifEmpty { null },
        )
    }

    /** Record the account the user just signed in with. */
    fun setAccount(ctx: Context, email: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(P_ACCOUNT_EMAIL, email)
            .remove(P_LAST_ERROR)
            .apply()
    }

    /** Forget the recorded account (sign-out). */
    fun clearAccount(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(P_ACCOUNT_EMAIL)
            .apply()
    }

    fun setAutoSync(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(P_AUTO_SYNC, on).apply()
    }

    private fun recordSuccess(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(P_LAST_OK, System.currentTimeMillis())
            .remove(P_LAST_ERROR)
            .apply()
    }

    private fun recordError(ctx: Context, msg: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(P_LAST_ERROR, msg).apply()
    }

    /** The signed-in account's email, or a clean "add account" failure. */
    private fun requireAccount(ctx: Context): String =
        GoogleDriveAuth.signedInAccount(ctx)?.email
            ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(P_ACCOUNT_EMAIL, null)
            ?: throw BackupError("Add your Google account first — tap “Google account”. Backups then go straight to Drive.")

    // ---------------- Backup ----------------

    /**
     * Write the encrypted backup (+ the key file, if Drive has none yet)
     * into the "AyX Backup" folder of the signed-in account. Returns a
     * success message; throws [BackupError] with a clean message on any
     * failure. Nothing is written unless the payload built and encrypted
     * successfully.
     */
    fun backupNow(ctx: Context): String {
        try {
            SettingsMirror.init(ctx)
            requireAccount(ctx)
            if (!SettingsMirror.hasAnyField()) {
                throw BackupError(
                    "Nothing to back up yet. The app learns your keys & prompts when you save them — " +
                        "save your AI API key / prompts once in Settings → AI Assistant, then back up."
                )
            }
            val key = resolveKeyForBackup(ctx)
            val payload = JSONObject()
                .put("v", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("appVersion", PAYLOAD_APP_VERSION)
                .put("fields", SettingsMirror.snapshotFields())
            val envelope = encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
            writeRemote(ctx, BACKUP_FILE, BACKUP_MIME, envelope.toString().toByteArray(Charsets.UTF_8))
            recordSuccess(ctx)
            return "Backup saved to Drive."
        } catch (e: BackupError) {
            recordError(ctx, e.message ?: "Backup failed")
            throw e
        } catch (e: Exception) {
            val msg = friendly(e)
            recordError(ctx, msg)
            throw BackupError(msg)
        }
    }

    // ---------------- Restore ----------------

    /** Decrypt the backup in Drive WITHOUT applying anything. */
    fun peekBackup(ctx: Context): Peek {
        try {
            SettingsMirror.init(ctx)
            requireAccount(ctx)
            val payload = readPayload(ctx)
            val fields = payload.optJSONObject("fields")
                ?: throw BackupError("The backup file is damaged (no settings inside). Nothing was restored.")
            return Peek(
                createdAt = payload.optLong("createdAt", 0L),
                appVersion = payload.optString("appVersion", "?"),
                fieldCount = fields.length(),
            )
        } catch (e: BackupError) {
            throw e
        } catch (e: Exception) {
            throw BackupError(friendly(e))
        }
    }

    /**
     * Full restore: decrypt + validate EVERYTHING first; only then apply,
     * in one settings patch through the existing gateway path (real values
     * replace stored ones under the v167 rules). A corrupt backup can
     * never half-apply.
     */
    suspend fun restore(ctx: Context): String {
        try {
            SettingsMirror.init(ctx)
            requireAccount(ctx)
            val payload = readPayload(ctx)
            val fields = payload.optJSONObject("fields")
                ?: throw BackupError("The backup file is damaged (no settings inside). Nothing was restored.")
            if (payload.optInt("v", 0) != 1) {
                throw BackupError("This backup was made by a newer app version. Update AyX WhatsApp first. Nothing was restored.")
            }

            val patch = JSONObject()
            val names = fields.names()
            if (names != null) {
                for (i in 0 until names.length()) {
                    val k = names.getString(i)
                    patch.put(k, fields.get(k))
                }
            }
            // An explicitly empty aiApiKey in the backup means "no key":
            // blank alone would read as "keep stored" (v167), so use the
            // gateway's explicit-clear flag for exactly that case.
            if (fields.has("aiApiKey") && fields.optString("aiApiKey", "").isBlank()) {
                patch.remove("aiApiKey")
                patch.put("aiApiKeyClear", true)
            }
            // The single gateway write. If it throws, nothing else ran.
            GatewayClient.patchSettings(patch)

            // Mirror + app-side key copy now match the restored values.
            SettingsMirror.applyRestored(fields)
            val realKey = fields.optString("aiApiKey", "").trim()
            if (realKey.isNotEmpty() && !AiKeyStore.isMask(realKey)) AiKeyStore.save(ctx, realKey)
            if (fields.has("aiApiKey") && realKey.isEmpty()) AiKeyStore.clear(ctx)
            // A successful restore clears the error line but is NOT a
            // backup — lastBackupAt keeps the backup's own time.
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(P_LAST_ERROR).apply()
            return "Settings restored from Drive."
        } catch (e: BackupError) {
            recordError(ctx, e.message ?: "Restore failed")
            throw e
        } catch (e: Exception) {
            val msg = friendly(e)
            recordError(ctx, msg)
            throw BackupError(msg)
        }
    }

    private fun readPayload(ctx: Context): JSONObject {
        val key = resolveKeyForRestore(ctx)
        val raw = readRemote(ctx, BACKUP_FILE)
            ?: throw BackupError("No backup found in Drive ($BACKUP_FILE is missing). Nothing was restored.")
        val envelope = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupError("The backup file is damaged and cannot be read. Nothing was restored.")
        }
        val plain = try {
            decrypt(key, envelope)
        } catch (e: Exception) {
            throw BackupError("This backup cannot be opened with the key file in Drive — the backup and its key file do not belong together. Nothing was restored.")
        }
        return try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupError("The backup file is damaged (bad contents). Nothing was restored.")
        }
    }

    // ---------------- Auto-sync ----------------

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ayx-drive-backup").apply { isDaemon = true }
    }

    @Volatile
    private var pendingAuto: ScheduledFuture<*>? = null

    /**
     * Called from the settings-write choke point whenever the mirror
     * changed. With auto-sync ON and an account added, schedules ONE
     * debounced silent backup; failures only update the status line.
     */
    fun onMirrorUpdated() {
        val ctx = appCtx ?: return
        val st = status(ctx)
        if (!st.autoSync || st.accountEmail == null) return
        synchronized(this) {
            pendingAuto?.cancel(false)
            pendingAuto = scheduler.schedule({
                runCatching { backupNow(ctx) }
                    .onFailure { Log.w(TAG, "auto-sync backup failed: ${it.message}") }
            }, 6, TimeUnit.SECONDS)
        }
    }

    // ---------------- Key handling ----------------

    /**
     * The key for a backup write: the key file in Drive WINS when present
     * (so backups stay readable by the very file a future restore will
     * use); otherwise the app's cached/generated key, uploaded as the
     * key file.
     */
    private fun resolveKeyForBackup(ctx: Context): ByteArray {
        readRemote(ctx, KEY_FILE)?.let { raw ->
            val k = parseKeyFile(raw)
            if (k != null) {
                SettingsMirror.cacheBackupKey(k)
                return k
            }
            // An unreadable key file must not be silently overwritten —
            // it may belong to backups this phone hasn't seen.
            throw BackupError("The key file in Drive ($KEY_FILE) cannot be read. Backup stopped so nothing is overwritten.")
        }
        val key = SettingsMirror.cachedBackupKey() ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            SettingsMirror.cacheBackupKey(it)
        }
        val keyJson = JSONObject().put("v", 1).put("k", Base64.encodeToString(key, Base64.NO_WRAP))
        writeRemote(ctx, KEY_FILE, KEY_MIME, keyJson.toString().toByteArray(Charsets.UTF_8))
        return key
    }

    private fun resolveKeyForRestore(ctx: Context): ByteArray {
        val raw = readRemote(ctx, KEY_FILE)
            ?: throw BackupError("Key file not found in Drive ($KEY_FILE). Restore is impossible without it — the backup and its key file must stay together.")
        val k = parseKeyFile(raw)
            ?: throw BackupError("The key file in Drive ($KEY_FILE) cannot be read. Nothing was restored.")
        SettingsMirror.cacheBackupKey(k)
        return k
    }

    private fun parseKeyFile(raw: ByteArray): ByteArray? = try {
        val b64 = JSONObject(String(raw, Charsets.UTF_8)).optString("k", "")
        Base64.decode(b64, Base64.NO_WRAP).takeIf { it.size == 32 }
    } catch (e: Exception) {
        null
    }

    // ---------------- Crypto (AES-256-GCM, random IV per write) ----------

    private fun encrypt(key: ByteArray, plain: ByteArray): JSONObject {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain)
        return JSONObject()
            .put("v", 1)
            .put("alg", "AES-256-GCM")
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
    }

    private fun decrypt(key: ByteArray, envelope: JSONObject): ByteArray {
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    // ---------------- Drive REST backend (v171; replaces v170's SAF) ----

    /** Run [block] with a Drive access token; on a 401, refresh once. */
    private fun <T> withToken(ctx: Context, block: (String) -> T): T {
        var token = freshToken(ctx)
        try {
            return block(token)
        } catch (e: DriveHttpException) {
            if (e.code != 401) throw e
            GoogleDriveAuth.invalidateToken(ctx, token)
            token = freshToken(ctx)
            return block(token)
        }
    }

    private fun freshToken(ctx: Context): String = try {
        GoogleDriveAuth.accessToken(ctx)
    } catch (e: GoogleDriveAuth.AuthException) {
        throw BackupError(e.message ?: GoogleDriveAuth.MSG_SIGN_IN_AGAIN)
    }

    private fun readRemote(ctx: Context, name: String): ByteArray? =
        withToken(ctx) { token -> driveRead(token, name) }

    private fun writeRemote(ctx: Context, name: String, mime: String, bytes: ByteArray) {
        withToken(ctx) { token -> driveWrite(token, name, mime, bytes) }
    }

    /** The "AyX Backup" folder in the account's Drive root — found or created. */
    private fun driveFolderId(token: String): String {
        val query = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and 'root' in parents and trashed = false"
        driveListFirst(token, query)?.let { return it.getString("id") }
        val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
        val (code, bytes) = driveHttp(
            "POST", "$DRIVE_API/files?fields=id", token,
            meta.toString().toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8",
        )
        if (code !in 200..299) throw DriveHttpException(code)
        return JSONObject(String(bytes, Charsets.UTF_8)).getString("id")
    }

    private fun driveFindChildId(token: String, folderId: String, name: String): String? {
        val query = "'$folderId' in parents and name = '$name' and trashed = false"
        return driveListFirst(token, query)?.optString("id")?.ifEmpty { null }
    }

    private fun driveRead(token: String, name: String): ByteArray? {
        val folderId = driveFolderId(token)
        val id = driveFindChildId(token, folderId, name) ?: return null
        val (code, bytes) = driveHttp("GET", "$DRIVE_API/files/$id?alt=media", token)
        return when {
            code in 200..299 -> bytes
            code == 404 -> null
            else -> throw DriveHttpException(code)
        }
    }

    private fun driveWrite(token: String, name: String, mime: String, bytes: ByteArray) {
        val folderId = driveFolderId(token)
        val id = driveFindChildId(token, folderId, name) ?: run {
            val meta = JSONObject()
                .put("name", name)
                .put("mimeType", mime)
                .put("parents", JSONArray().put(folderId))
            val (code, created) = driveHttp(
                "POST", "$DRIVE_API/files?fields=id", token,
                meta.toString().toByteArray(Charsets.UTF_8), "application/json; charset=UTF-8",
            )
            if (code !in 200..299) throw DriveHttpException(code)
            JSONObject(String(created, Charsets.UTF_8)).getString("id")
        }
        val (code, _) = driveHttp(
            "PATCH", "$DRIVE_UPLOAD/files/$id?uploadType=media", token, bytes, mime,
        )
        if (code !in 200..299) throw DriveHttpException(code)
    }

    private fun driveListFirst(token: String, query: String): JSONObject? {
        val url = "$DRIVE_API/files?q=${enc(query)}&fields=${enc("files(id,name)")}&pageSize=1"
        val (code, bytes) = driveHttp("GET", url, token)
        return when {
            code in 200..299 -> {
                val files = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("files")
                if (files != null && files.length() > 0) files.getJSONObject(0) else null
            }
            code == 404 -> null
            else -> throw DriveHttpException(code)
        }
    }

    /**
     * One Drive HTTP call. Returns (status, body). HTTP error statuses
     * are NOT exceptions here — callers decide (404 = missing is normal).
     */
    private fun driveHttp(
        method: String,
        url: String,
        token: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): Pair<Int, ByteArray> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                if (contentType != null) setRequestProperty("Content-Type", contentType)
            }
        }
        if (body != null) conn.outputStream.use { it.write(body); it.flush() }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun friendly(e: Exception): String = when (e) {
        is DriveHttpException -> when (e.code) {
            401 -> GoogleDriveAuth.MSG_SIGN_IN_AGAIN
            403 -> "Google refused Drive access (quota or permission). Try again later, or sign in again from “Google account”."
            404 -> "Something in Drive was moved or deleted — try again."
            else -> "Drive answered with an error (HTTP ${e.code}) — try again in a bit."
        }
        is IOException -> GoogleDriveAuth.MSG_NETWORK
        else -> "Drive backup failed: ${e.message ?: "unknown error"}"
    }
}
