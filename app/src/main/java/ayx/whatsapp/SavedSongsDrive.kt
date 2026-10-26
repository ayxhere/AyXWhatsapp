package ayx.whatsapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Saved-songs list sync to Google Drive (v192) — deliberately SEPARATE
 * from DriveBackup (settings) and SessionBackup (session): those files
 * and their logic are untouched. Only the "AyX Backup" folder is shared.
 *
 * File: ayx-saved-songs.json, PLAIN JSON (song titles are not
 * sensitive — no encryption, per spec):
 * {"v":1,"updatedAt":ms,"songs":[{"title":..,"artist":..}, ...]}.
 *
 * Uploads are debounced 6 s (same pattern as DriveBackup auto-sync)
 * and silent: not signed in → skip; failure → log only, never a toast.
 * [upload] and [download] are BLOCKING (call on IO);
 * [onFavouritesChanged] only schedules.
 */
object SavedSongsDrive {
    private const val TAG = "SavedSongsDrive"
    const val FILE_NAME = "ayx-saved-songs.json"
    private const val FILE_MIME = "application/json"
    private const val FOLDER_NAME = "AyX Backup"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val DEBOUNCE_MS = 6_000L

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ayx-saved-songs").apply { isDaemon = true }
    }

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /** Debounced upload after any favourites change; silent when not signed in. */
    fun onFavouritesChanged(ctx: Context, favourites: List<Pair<String, String>>) {
        val appCtx = ctx.applicationContext
        if (GoogleDriveAuth.signedInAccount(appCtx) == null) return
        val snapshot = favourites.toList()
        synchronized(this) {
            pending?.cancel(false)
            pending = scheduler.schedule({
                runCatching { upload(appCtx, snapshot) }
                    .onFailure { Log.w(TAG, "saved-songs upload failed: ${it.message}") }
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Blocking upload of one snapshot (call on IO). Throws when unsigned. */
    fun upload(ctx: Context, favourites: List<Pair<String, String>>) {
        val token = GoogleDriveAuth.accessToken(ctx)
        val songs = JSONArray()
        for ((t, a) in favourites) songs.put(JSONObject().put("title", t).put("artist", a))
        val json = JSONObject().put("v", 1).put("updatedAt", System.currentTimeMillis()).put("songs", songs)
        driveWrite(token, FILE_NAME, FILE_MIME, json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Blocking download; null when unsigned, missing, or unparsable. Call on IO. */
    fun download(ctx: Context): List<Pair<String, String>>? {
        return try {
            val token = GoogleDriveAuth.accessToken(ctx)
            val raw = driveRead(token, FILE_NAME) ?: return null
            val arr = JSONObject(String(raw, Charsets.UTF_8)).optJSONArray("songs") ?: return null
            val out = ArrayList<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("title", "")
                val a = o.optString("artist", "")
                if (t.isNotBlank()) out.add(t to a)
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    // ---- minimal Drive v3 REST (same shapes as DriveBackup; separate
    // copy so that file stays untouched). No 401 retry here — a failed
    // debounced upload simply retries on the next favourites change,
    // and a failed download leaves the local list alone.

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

    private class DriveHttpException(val code: Int) : Exception("Drive HTTP $code")
}
