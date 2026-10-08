package ayx.whatsapp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Backs up the WhatsApp login/session (Baileys auth creds + settings + messages) to the public
 * Download/WhatsAyX/session folder, which survives app reinstall / clear-data / factory-reset-restore.
 * On launch, if this device isn't linked, the app restores it automatically — no re-scan needed.
 */
object SessionBackup {
    private const val FILE = "ayx-session.json"
    private val REL = Environment.DIRECTORY_DOWNLOADS + "/WhatsAyX/session"

    suspend fun save(ctx: Context, json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            deleteExisting(ctx)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, REL)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: return@withContext false
            true
        } catch (e: Exception) { false }
    }

    suspend fun load(ctx: Context): String? = withContext(Dispatchers.IO) {
        try {
            val uri = findUri(ctx) ?: return@withContext null
            ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        } catch (e: Exception) { null }
    }

    suspend fun exists(ctx: Context): Boolean = withContext(Dispatchers.IO) { runCatching { findUri(ctx) != null }.getOrDefault(false) }

    suspend fun delete(ctx: Context) = withContext(Dispatchers.IO) { deleteExisting(ctx) }

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

    private fun deleteExisting(ctx: Context) {
        runCatching { findUri(ctx)?.let { ctx.contentResolver.delete(it, null, null) } }
    }
}
