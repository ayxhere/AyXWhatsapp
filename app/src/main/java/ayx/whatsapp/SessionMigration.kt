package ayx.whatsapp

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *  - This object WAS the one-time bridge for existing users (closed in
 *    v175 — see below). If a legacy public backup is still on the device
 *    it is dealt with exactly once:
 *      * private session already present -> the public file is a stale
 *        duplicate: delete it, touch nothing else.
 *      * no private session -> the public file is DELETED, never imported
 *        (v175). The v148 import bridge had become a zombie path: devices
 *        still holding the old ayx-session.json got that long-dead
 *        snapshot written back as a "live" session after every reinstall,
 *        and the app then sat on "Restoring your session…" forever. A
 *        fresh install now honestly means: no session -> link screen.
 *      * after a v148 logout/reset ("no import" flag) -> the same: just
 *        delete any leftover copy.
 *  - Runs synchronously from NodeRuntime.ensureStarted, BEFORE node reads
 *    the auth dir, so the purge lands on the same boot.
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
        // v175: no private creds — and nothing is imported to fill the gap.
        // A legacy public backup still lying in Downloads is deleted on
        // sight, never read: an install with no private session is simply
        // not linked, and the link screen is the honest outcome.
        if (uri != null) deletePublicCopies(ctx)
        setNoImport(ctx)
        Log.i(TAG, "no private creds - legacy public backup purged, never imported (v175)")
        return "purged-noimport"
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

    private fun setNoImport(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NO_IMPORT, true).apply()
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
