package ayx.whatsapp

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted WhatsApp SESSION backup to the user's Google Drive (v191c).
 *
 * Authorized 2026-10-26 by the user, REVERSING the old settings-only rule:
 * the Baileys login in noBackupFilesDir/auth may now be backed up — but
 * ONLY under these non-negotiable rules:
 *  - TAP-GATED ONLY. Backup runs from the Sync page "Backup session"
 *    button; restore runs from the LinkScreen "Restore session from
 *    Drive" button. There is NO auto-sync, NO auto-restore, NO auto-popup,
 *    and no call to this object from any automatic path (startup, poll,
 *    mirror update, or otherwise). Search the callers to prove it.
 *  - ENCRYPTED ONLY. The payload is AES-256-GCM with a random key; the
 *    KEY FILE lives in Drive beside the backup as a SEPARATE file
 *    (ayx-session-backup-key.ayxkey), exactly like the settings backup.
 *    Plaintext session JSON NEVER touches Drive, logs, or UI text.
 *  - OWN FILES. The session pair lives in the SAME "AyX Backup" folder
 *    as the settings pair but under DIFFERENT filenames, so a session
 *    backup can be deleted independently and never collides with the
 *    settings backup or its key. [DriveBackup] is NOT modified at all —
 *    its Drive REST + crypto helpers are duplicated here (small cost,
 *    zero risk to the settings flow).
 *  - SESSION ONLY. Only files under noBackupFilesDir/auth are read.
 *    Messages, settings, chats, and names are NEVER included.
 *  - PAIRED ONLY. Backup refuses unless the local creds carry a paired
 *    identity (creds.json with me.id, the same signal localPairedSession
 *    uses) — an unpaired QR-waiting husk is never backed up.
 *  - RESTORE NEEDS A RESTART. A running gateway never re-reads the auth
 *    dir, and the gateway has no reload endpoint (main.js is untouched).
 *    So the Restore tap writes the creds and restarts the app at once:
 *    the fresh process boots node, node reads the restored creds, and the
 *    login connects with no QR scan. (Restarting immediately, rather than
 *    showing a second dialog, is deliberate: the status poll recomputes
 *    localPaired on the next cycle and would dispose LinkScreen — and any
 *    dialog inside it — before the user could tap. If the launcher intent
 *    is missing, the process still exits and reopening the app by hand
 *    connects, since the restored creds are already in place.)
 *
 * All functions are BLOCKING (network + crypto + disk); callers run them
 * on IO. No function here logs session content — at most a file count.
 */
object SessionBackup {
    private const val TAG = "SessionBackup"
    const val SESSION_BACKUP_FILE = "ayx-session-backup.ayxbak"
    const val SESSION_KEY_FILE = "ayx-session-backup-key.ayxkey"
    private const val BACKUP_MIME = "application/octet-stream"
    private const val KEY_MIME = "application/json"

    private const val FOLDER_NAME = "AyX Backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"

    private const val PREFS = "ayx_session_backup"
    private const val P_LAST_OK = "last_session_backup_ok_ms"
    private const val P_LAST_ERROR = "last_session_error"

    // Sanity caps: a Baileys auth dir holds creds.json plus per-chat /
    // per-device key files (sessions, sender keys, pre-keys, app-state
    // keys) — thousands for an active user, so these caps are absurdity
    // guards only (a corrupt or attacker-grown dir), never normal-size
    // limits. The old 500-file cap false-tripped on real accounts
    // (2026-10-27: "unexpectedly many files" on a live session).
    private const val MAX_FILES = 50000
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    // v198: peek→restore ran the full multi-MB Drive download + decrypt
    // TWICE (peek for the dialog date, restore for the write), each with
    // its own token + folder + find round trips. The peeked bytes are
    // cached in memory (10 min) so the restore that follows the dialog
    // skips the second download entirely. The cache is consumed on
    // restore success and refreshed by every peek, so a peek always
    // describes exactly the bytes its restore would write.
    private var cachedBackupRaw: ByteArray? = null
    private var cachedKey: ByteArray? = null
    private var cachedAt: Long = 0L
    private const val PEEK_CACHE_MS = 10 * 60 * 1000L

    /** A failure with a user-readable message (shown as-is in the UI). */
    class SessionError(message: String) : Exception(message)

    /** A Drive HTTP failure; [code] is the HTTP status. */
    private class DriveHttpException(val code: Int) : Exception("Drive HTTP $code")

    data class Peek(val createdAt: Long, val appVersion: String)

    // ---------------- UI status (plain prefs: nothing secret lives here) ---

    fun statusLine(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val err = p.getString(P_LAST_ERROR, null)?.ifEmpty { null }
        if (err != null) return "Session backup failed: $err"
        val at = p.getLong(P_LAST_OK, 0L)
        if (at > 0L) {
            return "Session backup: " + java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.US)
                .format(java.util.Date(at))
        }
        return "Session backup: none yet — back up once, then a new phone restores without a QR scan."
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
            ?: throw SessionError("Add your Google account first — tap “Google account” in Sync, then back up the session.")

    // ---------------- Backup ----------------

    /**
     * Read the live auth dir, encrypt it, and upload it (+ the key file,
     * if Drive has none yet) into the "AyX Backup" folder. Returns a
     * success message; throws [SessionError] with a clean message on any
     * failure. Nothing is uploaded unless the payload built and encrypted
     * successfully.
     */
    fun backupSession(ctx: Context): String {
        try {
            requireAccount(ctx)
            val authDir = File(ctx.noBackupFilesDir, "auth")
            val files = collectAuthFiles(authDir)
            val key = resolveKeyForBackup(ctx)
            val payload = JSONObject()
                .put("v", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("appVersion", appVersion(ctx))
                .put("files", files)
            val envelope = encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
            writeRemote(ctx, SESSION_BACKUP_FILE, BACKUP_MIME, envelope.toString().toByteArray(Charsets.UTF_8))
            recordSuccess(ctx)
            Log.i(TAG, "session backup uploaded (${files.length()} files)")
            return "Session backup saved to Drive."
        } catch (e: SessionError) {
            recordError(ctx, e.message ?: "Session backup failed")
            throw e
        } catch (e: Exception) {
            val msg = friendly(e)
            recordError(ctx, msg)
            throw SessionError(msg)
        }
    }

    /**
     * Collect the auth dir as {relativePath: base64Content}. Validates the
     * paired identity (creds.json with me.id) and refuses unpaired husks.
     * Throws [SessionError] on any problem; nothing is uploaded then.
     */
    private fun collectAuthFiles(authDir: File): JSONObject {
        val creds = File(authDir, "creds.json")
        if (!creds.isFile || creds.length() == 0L) {
            throw SessionError("No WhatsApp session on this phone to back up — link WhatsApp first, then back up the session.")
        }
        val credsText = runCatching { creds.readText() }.getOrNull()
            ?: throw SessionError("The session file could not be read right now — try again.")
        val meId = runCatching { JSONObject(credsText).optJSONObject("me")?.optString("id") }.getOrNull()
        if (meId.isNullOrBlank()) {
            throw SessionError("No linked WhatsApp session on this phone yet — link first (QR scan), then back up the session.")
        }
        val out = JSONObject()
        var total = 0L
        var count = 0
        val stack = ArrayDeque<File>().apply { add(authDir) }
        while (stack.isNotEmpty()) {
            val dir = stack.removeFirst()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    stack.add(f)
                    continue
                }
                if (!f.isFile || f.length() == 0L) continue
                val rel = f.relativeTo(authDir).path.replace(File.separatorChar, '/')
                if (rel.isBlank() || rel.split('/').any { it == ".." || it.isEmpty() }) continue
                val bytes = runCatching { f.readBytes() }.getOrNull()
                    ?: throw SessionError("A session file could not be read right now — try again.")
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) throw SessionError("The session is unexpectedly large — backup stopped, nothing was uploaded.")
                out.put(rel, Base64.encodeToString(bytes, Base64.NO_WRAP))
                count++
                if (count > MAX_FILES) throw SessionError("The session has unexpectedly many files — backup stopped, nothing was uploaded.")
            }
        }
        if (out.length() == 0) {
            throw SessionError("No WhatsApp session on this phone to back up — link WhatsApp first, then back up the session.")
        }
        return out
    }

    // ---------------- Restore ----------------

    /** Decrypt the session backup in Drive WITHOUT writing anything. */
    fun peekSession(ctx: Context): Peek {
        try {
            requireAccount(ctx)
            val payload = readPayload(ctx)
            return Peek(
                createdAt = payload.optLong("createdAt", 0L),
                appVersion = payload.optString("appVersion", "?"),
            )
        } catch (e: SessionError) {
            throw e
        } catch (e: Exception) {
            throw SessionError(friendly(e))
        }
    }

    /**
     * Full restore: decrypt + validate EVERYTHING first; only then write
     * into the private auth dir, verifying creds.json landed. A corrupt
     * backup can never half-apply: a mid-write failure rolls the written
     * files back. On success the caller tries GatewayClient.reloadAuth()
     * first (direct login, no restart); only gateways without that
     * endpoint need [restartApp] — the running gateway never re-reads
     * the auth dir on its own.
     */
    fun restoreSession(ctx: Context): String {
        try {
            requireAccount(ctx)
            // v198: the dialog's peek already downloaded + verified these
            // exact bytes — reuse them (10-min cache) instead of a second
            // full Drive download. Without a preceding peek this just
            // downloads fresh (empty cache falls through).
            val payload = readPayload(ctx, useCache = true)
            val files = payload.optJSONObject("files")
                ?: throw SessionError("The session backup is damaged (no login inside). Nothing was restored.")
            validatePayloadFiles(files)
            val authDir = File(ctx.noBackupFilesDir, "auth").apply { mkdirs() }
            val written = mutableListOf<File>()
            try {
                val names = files.names() ?: throw SessionError("The session backup is damaged (no login inside). Nothing was restored.")
                for (i in 0 until names.length()) {
                    val rel = names.getString(i)
                    val target = File(authDir, rel)
                    // The payload was validated, but never trust a path twice.
                    if (!target.canonicalPath.startsWith(authDir.canonicalPath + File.separator)) {
                        throw SessionError("The session backup is damaged (bad file path). Nothing was restored.")
                    }
                    target.parentFile?.mkdirs()
                    val bytes = Base64.decode(files.getString(rel), Base64.NO_WRAP)
                    target.writeBytes(bytes)
                    written.add(target)
                }
            } catch (e: SessionError) {
                written.forEach { runCatching { it.delete() } }
                throw e
            } catch (e: Exception) {
                written.forEach { runCatching { it.delete() } }
                throw SessionError("The session could not be written — nothing was restored. (${e.message ?: "write failed"})")
            }
            val creds = File(authDir, "creds.json")
            if (!creds.isFile || creds.length() == 0L) {
                written.forEach { runCatching { it.delete() } }
                throw SessionError("The session did not land — nothing was restored.")
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(P_LAST_ERROR).apply()
            // v198: the peek cache is consumed — a later restore must
            // re-download, never reuse bytes from an older backup.
            cachedBackupRaw = null; cachedKey = null; cachedAt = 0L
            Log.i(TAG, "session restored (${written.size} files) — app restart required")
            return "Session restored from Drive."
        } catch (e: SessionError) {
            throw e
        } catch (e: Exception) {
            throw SessionError(friendly(e))
        }
    }

    /**
     * Validate the payload's file map BEFORE any write: version, a
     * non-empty creds.json carrying a paired identity, sane paths, and
     * decodable content.
     */
    private fun validatePayloadFiles(files: JSONObject) {
        if (files.length() == 0 || files.length() > MAX_FILES) {
            throw SessionError("The session backup is damaged (bad file list). Nothing was restored.")
        }
        val names = files.names() ?: throw SessionError("The session backup is damaged (no login inside). Nothing was restored.")
        var sawCreds = false
        for (i in 0 until names.length()) {
            val rel = names.getString(i)
            if (rel.isBlank() || rel.startsWith("/") || rel.split('/').any { it == ".." || it.isEmpty() }) {
                throw SessionError("The session backup is damaged (bad file path). Nothing was restored.")
            }
            val b64 = files.optString(rel, "")
            if (b64.isEmpty()) throw SessionError("The session backup is damaged (empty file). Nothing was restored.")
            runCatching { Base64.decode(b64, Base64.NO_WRAP) }.onFailure {
                throw SessionError("The session backup is damaged (unreadable file). Nothing was restored.")
            }
            if (rel == "creds.json") sawCreds = true
        }
        if (!sawCreds) throw SessionError("The session backup is damaged (no login inside). Nothing was restored.")
        val meId = runCatching {
            JSONObject(String(Base64.decode(files.getString("creds.json"), Base64.NO_WRAP), Charsets.UTF_8))
                .optJSONObject("me")?.optString("id")
        }.getOrNull()
        if (meId.isNullOrBlank()) {
            throw SessionError("This backup holds no linked login (no identity inside). Nothing was restored.")
        }
    }

    private fun readPayload(ctx: Context, useCache: Boolean = false): JSONObject {
        val now = System.currentTimeMillis()
        val (key, raw) = if (useCache && cachedBackupRaw != null && cachedKey != null && now - cachedAt < PEEK_CACHE_MS) {
            cachedKey!! to cachedBackupRaw!!
        } else {
            val k = resolveKeyForRestore(ctx)
            val r = readRemote(ctx, SESSION_BACKUP_FILE)
                ?: throw SessionError("No session backup found in Drive ($SESSION_BACKUP_FILE is missing). Nothing was restored.")
            k to r
        }
        val envelope = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SessionError("The session backup is damaged and cannot be read. Nothing was restored.")
        }
        val plain = try {
            decrypt(key, envelope)
        } catch (e: Exception) {
            throw SessionError("This session backup cannot be opened with the key file in Drive — the backup and its key file do not belong together. Nothing was restored.")
        }
        val payload = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SessionError("The session backup is damaged (bad contents). Nothing was restored.")
        }
        if (payload.optInt("v", 0) != 1) {
            throw SessionError("This session backup was made by a newer app version. Update AyX WhatsApp first. Nothing was restored.")
        }
        // Cache the verified bytes for the restore that follows a peek.
        cachedBackupRaw = raw; cachedKey = key; cachedAt = now
        return payload
    }

    /**
     * Restart the app so the fresh process boots node against the restored
     * auth dir. Always exits the process; if the launcher intent is
     * missing, reopening the app by hand still connects (the restored
     * creds are already in place). Only needed when the gateway lacks
     * /reload-auth (see GatewayClient.reloadAuth) — otherwise the
     * restore connects directly with no restart.
     */
    fun restartApp(ctx: Context) {
        runCatching {
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (intent != null) ctx.startActivity(intent)
        }
        Process.killProcess(Process.myPid())
    }

    // ---------------- Key handling (no local key cache, by design) --------

    /**
     * The key for a session backup write: the key file in Drive WINS when
     * present (so backups stay readable by the very file a future restore
     * will use); otherwise a fresh random key, uploaded as the key file.
     * Unlike the settings backup, the session key is NEVER cached in app
     * prefs — Drive's key file is the only copy besides the live backup.
     */
    private fun resolveKeyForBackup(ctx: Context): ByteArray {
        readRemote(ctx, SESSION_KEY_FILE)?.let { raw ->
            val k = parseKeyFile(raw)
            if (k != null) return k
            // An unreadable key file must not be silently overwritten —
            // it may belong to backups this phone hasn't seen.
            throw SessionError("The session key file in Drive ($SESSION_KEY_FILE) cannot be read. Backup stopped so nothing is overwritten.")
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keyJson = JSONObject().put("v", 1).put("k", Base64.encodeToString(key, Base64.NO_WRAP))
        writeRemote(ctx, SESSION_KEY_FILE, KEY_MIME, keyJson.toString().toByteArray(Charsets.UTF_8))
        return key
    }

    private fun resolveKeyForRestore(ctx: Context): ByteArray {
        val raw = readRemote(ctx, SESSION_KEY_FILE)
            ?: throw SessionError("Session key file not found in Drive ($SESSION_KEY_FILE). Restore is impossible without it — the backup and its key file must stay together.")
        return parseKeyFile(raw)
            ?: throw SessionError("The session key file in Drive ($SESSION_KEY_FILE) cannot be read. Nothing was restored.")
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

    // ---------------- Drive REST backend (mirrors DriveBackup's) --------

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
        throw SessionError(e.message ?: GoogleDriveAuth.MSG_SIGN_IN_AGAIN)
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

    private fun appVersion(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
