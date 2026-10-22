package ayx.whatsapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v148 — session privacy hardening.
 *
 * Up to v147 the app copied the whole WhatsApp login (Baileys auth creds +
 * settings + messages) into the PUBLIC Download/WhatsAyX/session folder via
 * MediaStore and auto-restored it after a reinstall / clear-data. Anyone (or
 * any app) with storage access could lift that one JSON and take over the
 * account, and the copy even survived uninstall. That backup is abolished:
 *
 *  - The live session exists ONLY in noBackupFilesDir/auth (app-private:
 *    survives app updates, wiped by logout / uninstall — Android's rules).
 *  - This object is the ONE-TIME bridge for existing users. If a legacy
 *    public backup is still on the device it is dealt with exactly once:
 *      * private session already present -> the public file is a stale
 *        duplicate: delete it, touch nothing else.
 *      * no private session + import allowed -> write the backup's auth
 *        files into the private auth dir (messages/settings next to it —
 *        the gateway's own layout), verify creds.json landed, and only
 *        THEN delete the public file. If anything fails, roll the partial
 *        write back and leave the public file exactly as it was.
 *      * after a v148 logout/reset ("no import" flag) -> never resurrect a
 *        session from public storage; just delete any leftover copy.
 *  - Runs synchronously from NodeRuntime.ensureStarted, BEFORE node reads
 *    the auth dir, so an imported session is picked up on the same boot.
 */
object SessionMigration {
    private const val TAG = "SessionMigration"
    private const val FILE = "ayx-session.json"
    private const val PREFS = "ayx_session_privacy"
    private const val KEY_NO_IMPORT = "no_import_v148"

    private val ran = AtomicBoolean(false)

    /** Blocking one-shot; safe to call from any thread. Never throws. */
    fun runBlockingOnce(ctx: Context) {
        if (!ran.compareAndSet(false, true)) return
        runCatching { runOnce(ctx.applicationContext) }
            .onFailure { Log.w(TAG, "migration failed (public backup left untouched): ${it.message}") }
    }

    private fun runOnce(ctx: Context): String {
        val authDir = File(ctx.noBackupFilesDir, "auth")
        val uri = findUri(ctx)
        // v149: a private creds.json that merely EXISTS ends the discussion.
        // It is never inspected for a "registered" field — modern Baileys
        // leaves creds.registered=false on fully paired logins, so v148 read
        // a live session as "absent", imported the stale public snapshot
        // OVER it, and broke the very login it meant to protect. Existing
        // private creds are never overwritten by an import, full stop.
        if (privateCredsPresent(authDir)) {
            if (uri != null) deletePublicCopies(ctx)
            setNoImport(ctx)
            Log.i(TAG, "private creds present - import barred; public copy purged")
            return "purged-duplicate"
        }
        if (uri == null) return "no-backup"
        if (noImport(ctx)) {
            deletePublicCopies(ctx)
            Log.i(TAG, "import disabled after logout - legacy public backup deleted")
            return "purged-noimport"
        }
        val text = readText(ctx, uri) ?: return "left-as-is"
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "left-as-is"
        val auth = root.optJSONObject("auth") ?: return "left-as-is"
        authDir.mkdirs()
        val written = mutableListOf<File>()
        var ok = true
        val keys = auth.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // Basename only — never let a backup entry escape the auth dir.
            val name = File(key).name
            if (name.isBlank() || name == "." || name == "..") continue
            val content = auth.optString(key, "")
            if (content.isEmpty()) continue
            val f = File(authDir, name)
            runCatching { f.writeText(content); written.add(f) }.onFailure { ok = false }
        }
        if (ok) {
            // Mirror the gateway layout: messages/settings live NEXT TO the
            // auth dir (path.dirname(AUTH_DIR)), not inside it.
            val msgs = root.optString("messages", "")
            if (msgs.isNotEmpty()) runCatching { File(ctx.noBackupFilesDir, "messages.json").writeText(msgs) }
            val st = root.optString("settings", "")
            if (st.isNotEmpty()) runCatching { File(ctx.noBackupFilesDir, "settings.json").writeText(st) }
        }
        val creds = File(authDir, "creds.json")
        if (!ok || !creds.isFile || creds.length() == 0L) {
            // Import did not land — roll the partial write back and leave
            // the public backup exactly as it was (the user can still
            // QR-login; nothing is destroyed on a failed migration).
            written.forEach { runCatching { it.delete() } }
            Log.w(TAG, "import incomplete - public backup left untouched")
            return "left-as-is"
        }
        deletePublicCopies(ctx)
        setNoImport(ctx) // one-way door: this device never imports again
        Log.i(TAG, "legacy session imported into private auth dir; public backup deleted")
        return "imported"
    }

    /**
     * Logout/reset path: permanently delete any leftover public backup and
     * bar future imports, so a logged-out session can never be resurrected
     * from public storage. The private auth dir itself is wiped by the
     * gateway's /logout.
     */
    suspend fun purgePublic(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            setNoImport(ctx.applicationContext)
            deletePublicCopies(ctx.applicationContext)
        }
        Unit
    }

    /**
     * v149: presence is ALL that matters — a private creds.json that exists
     * and is non-empty bars any import, whatever its fields say. (v148 parsed
     * the JSON and demanded registered==true; real paired creds carry
     * registered==false with `me` present, so that gate never fired and the
     * import rolled live auth state back to the stale public snapshot.)
     */
    private fun privateCredsPresent(authDir: File): Boolean {
        val creds = File(authDir, "creds.json")
        return creds.isFile && creds.length() > 0L
    }

    private fun noImport(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NO_IMPORT, false)

    private fun setNoImport(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NO_IMPORT, true).apply()
    }

    private fun readText(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (e: Exception) {
        null
    }

    private fun findUri(ctx: Context): Uri? {
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(MediaStore.MediaColumns._ID)
        val sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?"
        val args = arrayOf("%WhatsAyX/session%", FILE)
        ctx.contentResolver.query(coll, proj, sel, args, null)?.use { cur ->
            if (cur.moveToFirst()) {
                val id = cur.getLong(cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                return ContentUris.withAppendedId(coll, id)
            }
        }
        return null
    }

    /**
     * Best-effort delete of the legacy public backup via EVERY mechanism:
     * the MediaStore row AND the direct file path. v148 used MediaStore only
     * and swallowed the outcome — where that delete throws (e.g. the row is
     * owned by an older install identity) the file silently stayed behind.
     * Never throws; callers set the no-import flag regardless of the result,
     * so a surviving copy can never be used again either way.
     */
    private fun deletePublicCopies(ctx: Context) {
        runCatching { findUri(ctx)?.let { ctx.contentResolver.delete(it, null, null) } }
            .onFailure { Log.w(TAG, "mediastore delete failed: ${it.message}") }
        runCatching {
            val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f = File(dl, "WhatsAyX/session/$FILE")
            if (f.exists() && !f.delete()) Log.w(TAG, "file delete returned false: ${f.path}")
            // tidy: drop the session folder itself when it is now empty
            f.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }.onFailure { Log.w(TAG, "file delete failed: ${it.message}") }
    }
}
