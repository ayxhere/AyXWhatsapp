package ayx.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted CHAT backup to the user's Google Drive (v199).
 *
 * User order 2026-10-27, explicit: chats must survive via sync — chats
 * AND photos AND videos, everything, uploaded to Drive, so that after
 * re-login / on another device the chats come back from Drive. This is
 * the user's OWN account data on his OWN device going to his OWN Drive
 * folder — the same legitimacy as the existing session backup.
 *
 * Pattern: mirrors [SessionBackup] deliberately, with two differences
 * forced by size (chats + media can be hundreds of MB, a session is KB):
 *  - BUNDLE, not a JSON file map. The payload is ONE zip (chats.json +
 *    messages.json + media/<file>) so media bytes are not base64-inflated
 *    inside a JSONObject.
 *  - BINARY envelope, not a JSON envelope. The .ayxbak file is
 *    12-byte IV || AES-256-GCM ciphertext of the zip. Same cipher, same
 *    separate-key-file design as the session backup; only the container
 *    differs, so a 300 MB bundle is not held twice as base64 text.
 *
 * Non-negotiable rules (same as session backup):
 *  - TAP-GATED ONLY. Backup runs from the Sync page "Backup chats"
 *    button; restore runs from the LinkScreen "Restore chats from
 *    Drive" button. NO auto-sync, NO auto-restore, NO auto-popup, and
 *    no call to this object from any automatic path.
 *  - ENCRYPTED ONLY. Plaintext chats/media NEVER touch Drive, logs, or
 *    UI text. The key file lives in Drive beside the backup as a
 *    SEPARATE file (ayx-chats-backup-key.ayxkey).
 *  - OWN FILES. Different filenames from the settings pair and the
 *    session pair, so a chats backup can be deleted independently.
 *  - LOGIN UNTOUCHED. This backup/restore never reads or writes the
 *    auth dir, settings, or session files.
 *  - RESTORE NEEDS A RESTART. The running gateway reads chats.json /
 *    messages.json ONLY at boot (loadChats/loadMessages in main.js) and
 *    then saves its in-memory maps back to disk on every chat/message
 *    event (debounced 1.5s / 2s) — so writing restored files under a
 *    running gateway would be clobbered by its next save, and the v198
 *    /reload-auth socket restart does NOT re-read chat files. The
 *    caller therefore restarts the app right after a successful
 *    restore (SessionBackup.restartApp): the fresh process boots node,
 *    node reads the restored files, and Home shows them with no WA
 *    history re-sync needed. The confirm dialog says this plainly.
 *
 * Gateway on-disk layout (code-confirmed, NodeRuntime.kt + main.js):
 *  - chats.json:    <noBackupFilesDir>/chats.json
 *                    (dirname of WAGW_AUTH_DIR = noBackupFilesDir/auth)
 *  - messages.json: <noBackupFilesDir>/messages.json
 *  - media dir:     <filesDir>/media
 *                    (NodeRuntime sets WAGW_MEDIA_DIR explicitly; the
 *                    main.js default dirname(auth)/media does NOT apply)
 *  - media name:    <msg.key.id>.<ext> (enrichMedia), served by
 *    GET /media/:name from that dir. Backup includes EVERY file in the
 *    media dir (all gateway media), not just ones referenced by the
 *    current messages.json — a message whose media row was pruned from
 *    the log still keeps its file on disk until the 300 MB prune runs.
 *
 * All functions are BLOCKING (network + crypto + disk); callers run
 * them on IO. No function here logs chat text, media names, or key
 * bytes — at most counts and sizes.
 */
object ChatsBackup {
    private const val TAG = "ChatsBackup"
    const val CHATS_BACKUP_FILE = "ayx-chats-backup.ayxbak"
    const val CHATS_KEY_FILE = "ayx-chats-backup-key.ayxkey"
    private const val BACKUP_MIME = "application/octet-stream"
    private const val KEY_MIME = "application/json"

    private const val FOLDER_NAME = "AyX Backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    private const val PREFS = "ayx_chats_backup"
    private const val P_LAST_OK = "last_chats_backup_ok_ms"
    private const val P_LAST_ERROR = "last_chats_error"

    // Caps: the gateway itself caps media at 300 MB (MEDIA_CAP_BYTES)
    // and the message log at 3000 entries, so a real bundle is at most
    // ~310 MB. 512 MB is an absurdity guard only (a corrupt or
    // attacker-grown dir), never a normal-size limit.
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_MEDIA_FILES = 100000

    /** A failure with a user-readable message (shown as-is in the UI). */
    class ChatsError(message: String) : Exception(message)

    /** A Drive HTTP failure; [code] is the HTTP status. */
    private class DriveHttpException(val code: Int) : Exception("Drive HTTP $code")

    data class Peek(
        val createdAt: Long,
        val appVersion: String,
        val chatCount: Int,
        val messageCount: Int,
        val mediaCount: Int,
        val totalBytes: Long,
    )

    // ---------------- UI status (plain prefs: nothing secret lives here) ---

    fun statusLine(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val err = p.getString(P_LAST_ERROR, null)?.ifEmpty { null }
        if (err != null) return "Chats backup failed: $err"
        val at = p.getLong(P_LAST_OK, 0L)
        if (at > 0L) {
            return "Chats backup: " + java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.US)
                .format(java.util.Date(at))
        }
        return "Chats backup: none yet — back up once, then a new phone restores chats + photos + videos from Drive."
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
            ?: throw ChatsError("Add your Google account first — tap “Google account” in Sync, then back up the chats.")

    private fun gatewayDir(ctx: Context): File = ctx.noBackupFilesDir
    private fun mediaDir(ctx: Context): File = File(ctx.filesDir, "media")

    // ---------------- Backup ----------------

    /**
     * Pack chats.json + messages.json + the media dir into ONE zip,
     * encrypt it, and upload it (+ the key file, if Drive has none yet)
     * into the "AyX Backup" folder. Returns a success message with
     * counts + size; throws [ChatsError] with a clean message on any
     * failure. Nothing is uploaded unless the bundle built and
     * encrypted successfully.
     */
    fun backupChats(ctx: Context): String {
        try {
            requireAccount(ctx)
            val dir = gatewayDir(ctx)
            val chatsFile = File(dir, "chats.json")
            val messagesFile = File(dir, "messages.json")
            val media = mediaDir(ctx)
            val mediaFiles = if (media.isDirectory) {
                (media.listFiles() ?: emptyArray()).filter { it.isFile && it.length() > 0L }
            } else emptyList()
            val hasChats = chatsFile.isFile && chatsFile.length() > 0L
            val hasMessages = messagesFile.isFile && messagesFile.length() > 0L
            if (!hasChats && !hasMessages && mediaFiles.isEmpty()) {
                throw ChatsError("No chats on this phone to back up — link WhatsApp and let chats arrive first, then back up.")
            }
            if (mediaFiles.size > MAX_MEDIA_FILES) {
                throw ChatsError("The media folder is unexpectedly large — backup stopped, nothing was uploaded.")
            }
            // Best-effort counts for the UI + key-file meta. A corrupt
            // JSON never blocks the backup itself — the raw bytes are
            // still backed up as-is (restore validates them then).
            val chatCount = countJsonArray(chatsFile)
            val messageCount = countJsonArray(messagesFile)
            // Build the zip in memory, enforcing the cap as we go.
            val zipBytes = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    if (hasChats) zip.putNextEntry(ZipEntry("chats.json")).also { zip.write(chatsFile.readBytes()); zip.closeEntry() }
                    if (hasMessages) zip.putNextEntry(ZipEntry("messages.json")).also { zip.write(messagesFile.readBytes()); zip.closeEntry() }
                    for (f in mediaFiles) {
                        val name = f.name
                        if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "..") continue
                        zip.putNextEntry(ZipEntry("media/$name"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        if (out.size() > MAX_TOTAL_BYTES) {
                            throw ChatsError("The chats + media are unexpectedly large (over 512 MB) — backup stopped, nothing was uploaded.")
                        }
                    }
                }
            }.toByteArray()
            if (zipBytes.isEmpty()) {
                throw ChatsError("No chats on this phone to back up — link WhatsApp and let chats arrive first, then back up.")
            }
            val key = resolveKeyForBackup(ctx)
            val blob = encrypt(key, zipBytes)
            writeRemote(ctx, CHATS_BACKUP_FILE, BACKUP_MIME, blob)
            // Key-file meta AFTER the upload succeeds, so a failed
            // upload can never leave meta describing a backup that
            // never landed. Peek falls back to a slow download-inspect
            // path when meta is absent.
            writeKeyFile(ctx, key, JSONObject()
                .put("createdAt", System.currentTimeMillis())
                .put("appVersion", appVersion(ctx))
                .put("chatCount", chatCount)
                .put("messageCount", messageCount)
                .put("mediaCount", mediaFiles.size)
                .put("totalBytes", zipBytes.size.toLong()))
            recordSuccess(ctx)
            Log.i(TAG, "chats backup uploaded ($chatCount chats, $messageCount messages, ${mediaFiles.size} media, ${zipBytes.size} bytes)")
            return "Chats backup saved to Drive — $chatCount chats, $messageCount messages, ${mediaFiles.size} photos/videos (${zipBytes.size / 1048576} MB)."
        } catch (e: ChatsError) {
            recordError(ctx, e.message ?: "Chats backup failed")
            throw e
        } catch (e: Exception) {
            val msg = friendly(e)
            recordError(ctx, msg)
            throw ChatsError(msg)
        }
    }

    /** Length of a JSON array file, or 0 when unreadable/unparseable. */
    private fun countJsonArray(f: File): Int = runCatching {
        if (!f.isFile || f.length() == 0L) 0 else JSONArray(f.readText()).length()
    }.getOrDefault(0)

    // ---------------- Peek ----------------

    /**
     * Describe the Drive chats backup WITHOUT writing anything.
     * Fast path: the backup file exists + the key file carries meta
     * (written after a successful upload) — no multi-MB download.
     * Slow path (meta absent): download + decrypt + list the zip
     * entries and count them, still without writing.
     */
    fun peekChats(ctx: Context): Peek {
        try {
            requireAccount(ctx)
            val meta = driveFileMeta(ctx, CHATS_BACKUP_FILE)
                ?: throw ChatsError("No chats backup found in Drive ($CHATS_BACKUP_FILE is missing). Nothing was restored.")
            val keyRaw = readRemote(ctx, CHATS_KEY_FILE)
                ?: throw ChatsError("Chats key file not found in Drive ($CHATS_KEY_FILE). Restore is impossible without it — the backup and its key file must stay together.")
            val keyJson = runCatching { JSONObject(String(keyRaw, Charsets.UTF_8)) }.getOrNull()
                ?: throw ChatsError("The chats key file in Drive ($CHATS_KEY_FILE) cannot be read. Nothing was restored.")
            val key = parseKeyJson(keyJson)
                ?: throw ChatsError("The chats key file in Drive ($CHATS_KEY_FILE) cannot be read. Nothing was restored.")
            val m = keyJson.optJSONObject("meta")
            if (m != null) {
                return Peek(
                    createdAt = m.optLong("createdAt", meta.modifiedTime),
                    appVersion = m.optString("appVersion", "?"),
                    chatCount = m.optInt("chatCount", 0),
                    messageCount = m.optInt("messageCount", 0),
                    mediaCount = m.optInt("mediaCount", 0),
                    totalBytes = m.optLong("totalBytes", meta.size),
                )
            }
            // Slow path: meta missing (e.g. key written but meta update
            // failed) — inspect the real bundle instead of guessing.
            val zip = decrypt(key, readBackupBlob(ctx))
            return inspectZip(zip, meta.modifiedTime)
        } catch (e: ChatsError) {
            throw e
        } catch (e: Exception) {
            throw ChatsError(friendly(e))
        }
    }

    /** Read zip entry names + parse the two JSONs, without writing. */
    private fun inspectZip(zipBytes: ByteArray, fallbackCreatedAt: Long): Peek {
        var chatCount = 0
        var messageCount = 0
        var mediaCount = 0
        var sawChats = false
        var sawMessages = false
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                when {
                    e.name == "chats.json" -> {
                        sawChats = true
                        chatCount = runCatching { JSONArray(zip.readBytes().toString(Charsets.UTF_8)).length() }.getOrDefault(0)
                    }
                    e.name == "messages.json" -> {
                        sawMessages = true
                        messageCount = runCatching { JSONArray(zip.readBytes().toString(Charsets.UTF_8)).length() }.getOrDefault(0)
                    }
                    e.name.startsWith("media/") && !e.isDirectory -> mediaCount++
                }
                e = zip.nextEntry
            }
        }
        if (!sawChats && !sawMessages) {
            throw ChatsError("The chats backup is damaged (no chats inside). Nothing was restored.")
        }
        return Peek(
            createdAt = fallbackCreatedAt,
            appVersion = "?",
            chatCount = chatCount,
            messageCount = messageCount,
            mediaCount = mediaCount,
            totalBytes = zipBytes.size.toLong(),
        )
    }

    // ---------------- Restore ----------------

    /**
     * Full restore: download + decrypt + validate EVERYTHING first
     * (zip integrity, JSON arrays parse, safe entry names, size caps);
     * only then stage the files and swap them into place, with the
     * previous files kept as .bak until the swap completes — a mid-
     * swap failure rolls the old files back. On success the CALLER
     * restarts the app at once (see the class header: the running
     * gateway would otherwise clobber the restored files on its next
     * debounced save, and /reload-auth does not re-read chat files).
     * The WhatsApp login/session is NEVER touched here.
     */
    fun restoreChats(ctx: Context): String {
        try {
            requireAccount(ctx)
            val blob = readBackupBlob(ctx)
            val keyRaw = readRemote(ctx, CHATS_KEY_FILE)
                ?: throw ChatsError("Chats key file not found in Drive ($CHATS_KEY_FILE). Restore is impossible without it — the backup and its key file must stay together.")
            val key = runCatching { JSONObject(String(keyRaw, Charsets.UTF_8)) }.getOrNull()
                ?.let { parseKeyJson(it) }
                ?: throw ChatsError("The chats key file in Drive ($CHATS_KEY_FILE) cannot be read. Nothing was restored.")
            val zipBytes = try {
                decrypt(key, blob)
            } catch (e: Exception) {
                throw ChatsError("This chats backup cannot be opened with the key file in Drive — the backup and its key file do not belong together. Nothing was restored.")
            }
            // Validate + stage BEFORE touching any live file.
            val staged = stageValidatedZip(ctx, zipBytes)
            try {
                swapIntoPlace(ctx, staged)
            } catch (e: Exception) {
                rollbackSwap(ctx)
                runCatching { staged.deleteRecursively() }
                // NOTE: .bak files are deliberately NOT deleted here —
                // they are the only copy of the old chats if the
                // rollback itself failed midway. The next restore
                // overwrites them via rename.
                throw ChatsError("The chats could not be written — the old chats were kept. (${e.message ?: "write failed"})")
            }
            // Success only: drop staging + the superseded .bak copies.
            runCatching { staged.deleteRecursively() }
            runCatching { File(gatewayDir(ctx), "chats.json.bak").delete() }
            runCatching { File(gatewayDir(ctx), "messages.json.bak").delete() }
            runCatching { File(ctx.filesDir, "media.bak").deleteRecursively() }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(P_LAST_ERROR).apply()
            Log.i(TAG, "chats restored — app restart required so the gateway re-reads them")
            return "Chats restored from Drive."
        } catch (e: ChatsError) {
            throw e
        } catch (e: Exception) {
            throw ChatsError(friendly(e))
        }
    }

    private data class Staged(val chats: File?, val messages: File?, val media: File?)

    /**
     * Unzip into a staging dir, validating as we go: at least one of
     * chats.json/messages.json present, both parse as JSON arrays when
     * present, media names are plain basenames, and the inflated total
     * stays under the cap (zip-bomb guard). Throws [ChatsError] before
     * any live file is touched.
     */
    private fun stageValidatedZip(ctx: Context, zipBytes: ByteArray): Staged {
        val stage = File(ctx.cacheDir, "ayx-chats-restore").apply {
            deleteRecursively(); mkdirs()
        }
        val stageMedia = File(stage, "media").apply { mkdirs() }
        var sawChats = false
        var sawMessages = false
        var total = 0L
        var mediaCount = 0
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        when {
                            e.name == "chats.json" || e.name == "messages.json" -> {
                                val bytes = zip.readBytes()
                                total += bytes.size
                                if (total > MAX_TOTAL_BYTES) throw ChatsError("The chats backup is unexpectedly large — nothing was restored.")
                                runCatching { JSONArray(String(bytes, Charsets.UTF_8)) }.onFailure {
                                    throw ChatsError("The chats backup is damaged (bad chat data). Nothing was restored.")
                                }
                                File(stage, e.name).writeBytes(bytes)
                                if (e.name == "chats.json") sawChats = true else sawMessages = true
                            }
                            e.name.startsWith("media/") -> {
                                val base = e.name.removePrefix("media/")
                                if (base.isBlank() || base.contains('/') || base.contains('\\') || base == "..") {
                                    throw ChatsError("The chats backup is damaged (bad media file path). Nothing was restored.")
                                }
                                mediaCount++
                                if (mediaCount > MAX_MEDIA_FILES) throw ChatsError("The chats backup holds unexpectedly many media files — nothing was restored.")
                                val out = File(stageMedia, base)
                                out.outputStream().use { o ->
                                    val buf = ByteArray(64 * 1024)
                                    var n: Int
                                    while (zip.read(buf).also { n = it } >= 0) {
                                        o.write(buf, 0, n)
                                        total += n
                                        if (total > MAX_TOTAL_BYTES) throw ChatsError("The chats backup is unexpectedly large — nothing was restored.")
                                    }
                                }
                            }
                            // Unknown entries are ignored, never written.
                            else -> Unit
                        }
                    }
                    e = zip.nextEntry
                }
            }
        } catch (e: ChatsError) {
            runCatching { stage.deleteRecursively() }
            throw e
        } catch (e: Exception) {
            runCatching { stage.deleteRecursively() }
            throw ChatsError("The chats backup is damaged and cannot be read. Nothing was restored.")
        }
        if (!sawChats && !sawMessages) {
            runCatching { stage.deleteRecursively() }
            throw ChatsError("The chats backup is damaged (no chats inside). Nothing was restored.")
        }
        val chats = File(stage, "chats.json").takeIf { it.isFile }
        val messages = File(stage, "messages.json").takeIf { it.isFile }
        val media = stageMedia.takeIf { (it.listFiles()?.size ?: 0) > 0 }
        return Staged(chats, messages, media)
    }

    /**
     * Swap staged files into place. Previous files are renamed to .bak
     * first; on any failure [rollbackSwap] puts them back. The caller
     * restarts the app immediately afterwards, so the gateway boots
     * against the restored files before any debounced save can fire.
     */
    private fun swapIntoPlace(ctx: Context, staged: Staged) {
        val dir = gatewayDir(ctx)
        val chatsTarget = File(dir, "chats.json")
        val messagesTarget = File(dir, "messages.json")
        val mediaTarget = mediaDir(ctx)
        val chatsBak = File(dir, "chats.json.bak")
        val messagesBak = File(dir, "messages.json.bak")
        val mediaBak = File(ctx.filesDir, "media.bak")
        // Clear stale .bak copies from an earlier failed run first —
        // renameTo onto an existing non-empty dir is not reliable.
        runCatching { chatsBak.delete() }
        runCatching { messagesBak.delete() }
        runCatching { mediaBak.deleteRecursively() }
        if (staged.chats != null) {
            if (chatsTarget.isFile) chatsTarget.renameTo(chatsBak)
            if (!staged.chats.renameTo(chatsTarget)) throw IOException("chats.json swap failed")
        }
        if (staged.messages != null) {
            if (messagesTarget.isFile) messagesTarget.renameTo(messagesBak)
            if (!staged.messages.renameTo(messagesTarget)) throw IOException("messages.json swap failed")
        }
        if (staged.media != null) {
            if (mediaTarget.isDirectory) mediaTarget.renameTo(mediaBak)
            if (!staged.media.renameTo(mediaTarget)) throw IOException("media swap failed")
        }
    }

    /** Best-effort rollback after a failed swap: .bak files win. */
    private fun rollbackSwap(ctx: Context) {
        val dir = gatewayDir(ctx)
        runCatching {
            val chatsBak = File(dir, "chats.json.bak")
            if (chatsBak.isFile) {
                File(dir, "chats.json").delete()
                chatsBak.renameTo(File(dir, "chats.json"))
            }
            val messagesBak = File(dir, "messages.json.bak")
            if (messagesBak.isFile) {
                File(dir, "messages.json").delete()
                messagesBak.renameTo(File(dir, "messages.json"))
            }
            val mediaBak = File(ctx.filesDir, "media.bak")
            if (mediaBak.isDirectory) {
                mediaDir(ctx).deleteRecursively()
                mediaBak.renameTo(mediaDir(ctx))
            }
        }
    }

    private fun readBackupBlob(ctx: Context): ByteArray =
        readRemote(ctx, CHATS_BACKUP_FILE)
            ?: throw ChatsError("No chats backup found in Drive ($CHATS_BACKUP_FILE is missing). Nothing was restored.")

    // ---------------- Key handling (no local key cache, by design) --------

    /**
     * Same semantics as the session backup: the key file in Drive WINS
     * when present (so backups stay readable by the very file a future
     * restore will use); otherwise a fresh random key, uploaded as the
     * key file (meta is added only after a successful backup upload).
     * An unreadable key file must not be silently overwritten.
     */
    private fun resolveKeyForBackup(ctx: Context): ByteArray {
        readRemote(ctx, CHATS_KEY_FILE)?.let { raw ->
            val k = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()?.let { parseKeyJson(it) }
            if (k != null) return k
            throw ChatsError("The chats key file in Drive ($CHATS_KEY_FILE) cannot be read. Backup stopped so nothing is overwritten.")
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        writeKeyFile(ctx, key, null)
        return key
    }

    private fun writeKeyFile(ctx: Context, key: ByteArray, meta: JSONObject?) {
        val j = JSONObject().put("v", 1).put("k", Base64.encodeToString(key, Base64.NO_WRAP))
        if (meta != null) j.put("meta", meta)
        writeRemote(ctx, CHATS_KEY_FILE, KEY_MIME, j.toString().toByteArray(Charsets.UTF_8))
    }

    private fun parseKeyJson(j: JSONObject): ByteArray? = try {
        Base64.decode(j.optString("k", ""), Base64.NO_WRAP).takeIf { it.size == 32 }
    } catch (e: Exception) {
        null
    }

    // ---------------- Crypto (AES-256-GCM, random IV per write) ----------
    // Binary envelope: 12-byte IV || ciphertext. NOT the session
    // backup's JSON envelope — a 300 MB bundle must not be held twice
    // as base64 text. Same cipher, same key size, same separate file.

    private fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > 12) { "short blob" }
        val iv = blob.copyOfRange(0, 12)
        val data = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }

    // ---------------- Drive REST backend (mirrors SessionBackup's) --------

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
        throw ChatsError(e.message ?: GoogleDriveAuth.MSG_SIGN_IN_AGAIN)
    }

    private fun readRemote(ctx: Context, name: String): ByteArray? =
        withToken(ctx) { token -> driveRead(token, name) }

    private fun writeRemote(ctx: Context, name: String, mime: String, bytes: ByteArray) {
        withToken(ctx) { token -> driveWrite(token, name, mime, bytes) }
    }

    private fun driveFileMeta(ctx: Context, name: String): DriveMeta? =
        withToken(ctx) { token ->
            val folderId = driveFolderId(token)
            val id = driveFindChildId(token, folderId, name) ?: return@withToken null
            val (code, bytes) = driveHttp("GET", "$DRIVE_API/files/$id?fields=${enc("id,name,size,modifiedTime")}", token)
            if (code !in 200..299) throw DriveHttpException(code)
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            DriveMeta(
                size = j.optLong("size", 0L),
                modifiedTime = runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .parse(j.optString("modifiedTime", "").take(19))?.time ?: 0L
                }.getOrDefault(0L),
            )
        }

    private data class DriveMeta(val size: Long, val modifiedTime: Long)

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

    private fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
