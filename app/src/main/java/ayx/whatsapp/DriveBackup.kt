package ayx.whatsapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted settings backup to a user-picked Google Drive folder (v170).
 *
 * The user's locked decisions, implemented exactly:
 *  - SETTINGS ONLY (API keys + custom prompts, mirrored app-side by
 *    [SettingsMirror]). Login/session material NEVER enters the payload —
 *    the WhatsApp session never leaves the device (v148 rule).
 *  - NO password (he rejected one, twice). The payload is AES-256-GCM
 *    encrypted with a random app-generated key; the KEY FILE lives in the
 *    same picked folder as a SEPARATE file, so a format / new phone can
 *    restore by picking the same folder again.
 *  - Drive access is the Storage Access Framework (one-time folder pick,
 *    persisted URI permission) — no Google Cloud/OAuth setup exists for
 *    this app, so no Drive REST API.
 *
 * All functions are BLOCKING (SAF + crypto); callers run them on IO.
 */
object DriveBackup {
    private const val TAG = "DriveBackup"
    const val BACKUP_FILE = "ayx-settings-backup.ayxbak"
    const val KEY_FILE = "ayx-backup-key.ayxkey"
    private const val BACKUP_MIME = "application/octet-stream"
    private const val KEY_MIME = "application/json"
    private const val PAYLOAD_APP_VERSION = "7.06"

    private const val PREFS = "ayx_drive_backup"
    private const val P_TREE_URI = "tree_uri"
    private const val P_TREE_NAME = "tree_name"
    private const val P_AUTO_SYNC = "auto_sync"
    private const val P_LAST_OK = "last_backup_ok_ms"
    private const val P_LAST_ERROR = "last_error"

    /** A failure with a user-readable message (shown as-is in the UI). */
    class BackupError(message: String) : Exception(message)

    data class Status(
        val folderUri: String?,
        val folderName: String?,
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
        return Status(
            folderUri = p.getString(P_TREE_URI, null),
            folderName = p.getString(P_TREE_NAME, null),
            autoSync = p.getBoolean(P_AUTO_SYNC, false),
            lastBackupAt = p.getLong(P_LAST_OK, 0L),
            lastError = p.getString(P_LAST_ERROR, null)?.ifEmpty { null },
        )
    }

    fun setFolder(ctx: Context, uri: Uri) {
        val name = runCatching { queryFolderName(ctx, uri) }.getOrNull()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(P_TREE_URI, uri.toString())
            .putString(P_TREE_NAME, name ?: "Selected folder")
            .remove(P_LAST_ERROR)
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

    private fun folder(ctx: Context): Uri =
        status(ctx).folderUri?.let { Uri.parse(it) }
            ?: throw BackupError("No folder chosen yet — tap “Backup folder” and pick a Google Drive folder first.")

    // ---------------- Backup ----------------

    /**
     * Write the encrypted backup (+ the key file, if the folder has none
     * yet). Returns a success message; throws [BackupError] with a clean
     * message on any failure. Nothing is written unless the payload built
     * and encrypted successfully.
     */
    fun backupNow(ctx: Context): String {
        try {
            SettingsMirror.init(ctx)
            val tree = folder(ctx)
            if (!SettingsMirror.hasAnyField()) {
                throw BackupError(
                    "Nothing to back up yet. The app learns your keys & prompts when you save them — " +
                        "save your AI API key / prompts once in Settings → AI Assistant, then back up."
                )
            }
            val key = resolveKeyForBackup(ctx, tree)
            val payload = JSONObject()
                .put("v", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("appVersion", PAYLOAD_APP_VERSION)
                .put("fields", SettingsMirror.snapshotFields())
            val envelope = encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
            writeChild(ctx, tree, BACKUP_FILE, BACKUP_MIME, envelope.toString().toByteArray(Charsets.UTF_8))
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

    /** Decrypt the backup in the picked folder WITHOUT applying anything. */
    fun peekBackup(ctx: Context): Peek {
        try {
            SettingsMirror.init(ctx)
            val tree = folder(ctx)
            val payload = readPayload(ctx, tree)
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
            val tree = folder(ctx)
            val payload = readPayload(ctx, tree)
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

    private fun readPayload(ctx: Context, tree: Uri): JSONObject {
        val key = resolveKeyForRestore(ctx, tree)
        val raw = readChild(ctx, tree, BACKUP_FILE)
            ?: throw BackupError("No backup found in this folder ($BACKUP_FILE is missing). Nothing was restored.")
        val envelope = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupError("The backup file is damaged and cannot be read. Nothing was restored.")
        }
        val plain = try {
            decrypt(key, envelope)
        } catch (e: Exception) {
            throw BackupError("This backup cannot be opened with the key file in this folder — the backup and its key file do not belong together. Nothing was restored.")
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
     * changed. With auto-sync ON and a folder picked, schedules ONE
     * debounced silent backup; failures only update the status line.
     */
    fun onMirrorUpdated() {
        val ctx = appCtx ?: return
        val st = status(ctx)
        if (!st.autoSync || st.folderUri == null) return
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
     * The key for a backup write: the folder's key file WINS when present
     * (so backups stay readable by the very file a future restore will
     * use); otherwise the app's cached/generated key, written to the
     * folder as the key file.
     */
    private fun resolveKeyForBackup(ctx: Context, tree: Uri): ByteArray {
        readChild(ctx, tree, KEY_FILE)?.let { raw ->
            val k = parseKeyFile(raw)
            if (k != null) {
                SettingsMirror.cacheBackupKey(k)
                return k
            }
            // An unreadable key file must not be silently overwritten —
            // it may belong to backups this phone hasn't seen.
            throw BackupError("The key file in this folder ($KEY_FILE) cannot be read. Backup stopped so nothing is overwritten.")
        }
        val key = SettingsMirror.cachedBackupKey() ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            SettingsMirror.cacheBackupKey(it)
        }
        val keyJson = JSONObject().put("v", 1).put("k", Base64.encodeToString(key, Base64.NO_WRAP))
        writeChild(ctx, tree, KEY_FILE, KEY_MIME, keyJson.toString().toByteArray(Charsets.UTF_8))
        return key
    }

    private fun resolveKeyForRestore(ctx: Context, tree: Uri): ByteArray {
        val raw = readChild(ctx, tree, KEY_FILE)
            ?: throw BackupError("Key file not found in this folder ($KEY_FILE). Restore is impossible without it — the backup and its key file must stay together.")
        val k = parseKeyFile(raw)
            ?: throw BackupError("The key file in this folder ($KEY_FILE) cannot be read. Nothing was restored.")
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

    // ---------------- SAF plumbing (raw DocumentsContract; no extra dep) -

    private fun queryFolderName(ctx: Context, tree: Uri): String? {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun findChildId(ctx: Context, tree: Uri, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return c.getString(0)
            }
        }
        return null
    }

    private fun readChild(ctx: Context, tree: Uri, name: String): ByteArray? {
        val id = findChildId(ctx, tree, name) ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }

    private fun writeChild(ctx: Context, tree: Uri, name: String, mime: String, bytes: ByteArray) {
        val existing = findChildId(ctx, tree, name)
        val uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, existing)
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)
                ?: throw BackupError("Could not create $name in the chosen folder.")
        }
        val out = ctx.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupError("Could not open $name for writing.")
        out.use { it.write(bytes); it.flush() }
    }

    private fun friendly(e: Exception): String = when (e) {
        is SecurityException ->
            "Folder access was lost (permission revoked or folder moved). Pick the backup folder again."
        else -> "Drive backup failed: ${e.message ?: "unknown error"}"
    }
}
