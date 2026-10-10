package ayx.whatsapp.group

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * AyX Group sync: register → bootstrap → incremental /v1/sync.
 *
 * Content is cached as JSON in the app-private files dir
 * (files/ayx_group/announcements.json, statuses.json) so the AyX updates screen
 * works offline. The sync cursor is persisted ONLY after the content writes
 * succeed, so a crash can never skip deltas. Upserts are idempotent by id, so
 * re-running a sync (e.g. the periodic worker) never duplicates or re-notifies.
 */
object AyxGroupSync {
    data class SyncResult(
        /** Announcements that arrived with THIS sync and were not previously known. */
        val newAnnouncements: List<Announcement>,
        /** True when this was a first-run bootstrap (caller should not notify for these). */
        val fullRefresh: Boolean,
    )

    private const val DIR = "ayx_group"
    private const val MEDIA_DIR = "ayx_group/media"
    private const val MAX_MEDIA_BYTES = 25L * 1024 * 1024 // per-file download cap + total cache cap
    private const val FOREGROUND_DEBOUNCE_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var lastForegroundAttempt = 0L

    // ---- files ----

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun mediaDir(ctx: Context): File = File(ctx.filesDir, MEDIA_DIR).apply { mkdirs() }
    private fun announcementsFile(ctx: Context): File = File(dir(ctx), "announcements.json")
    private fun statusesFile(ctx: Context): File = File(dir(ctx), "statuses.json")

    fun cachedAnnouncements(ctx: Context): List<Announcement> =
        readList(announcementsFile(ctx)) { Announcement.fromJson(it) }
            .sortedByDescending { it.publishedAt }

    fun cachedStatuses(ctx: Context): List<AyxStatus> =
        readList(statusesFile(ctx)) { AyxStatus.fromJson(it) }
            .filterNot { it.isExpired }
            .sortedByDescending { it.createdAt }

    private fun <T> readList(file: File, parse: (org.json.JSONObject) -> T): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
        } catch (_: Exception) {
            emptyList() // corrupt cache → treat as empty; next sync with cursor=0 reseeds
        }
    }

    private fun writeList(file: File, items: List<org.json.JSONObject>) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.writeText(JSONArray(items).toString())
        // Atomic replace: the cursor is only advanced after BOTH files land.
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Snippet for the pinned chat-list row: latest announcement title, or the default line. */
    fun latestSnippet(ctx: Context): String {
        val latest = cachedAnnouncements(ctx).firstOrNull()
        val s = latest?.let { if (it.title.isNotBlank()) it.title else it.body }?.trim().orEmpty()
        return if (s.isNotBlank()) s.take(80) else "Official updates from AyX"
    }

    // ---- sync entry points ----

    /** Debounced foreground sync — safe to call from Activity.onResume. */
    fun foregroundSync(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastForegroundAttempt < FOREGROUND_DEBOUNCE_MS) return
        lastForegroundAttempt = now
        scope.launch { runCatching { syncNow(ctx.applicationContext) } }
    }

    /**
     * Run one full sync pass. Serialized with a mutex so the worker and the
     * foreground trigger can never interleave.
     */
    suspend fun syncNow(ctx: Context): SyncResult = mutex.withLock {
        val appCtx = ctx.applicationContext
        if (!AyxGroupStore.isRegistered(appCtx)) {
            val reg = AyxGroupApi.register()
            AyxGroupStore.saveRegistration(appCtx, reg.installationId, reg.accessToken)
        }
        val token = AyxGroupStore.accessToken(appCtx) ?: error("AyX Group not registered")
        try {
            doSync(appCtx, token)
        } catch (e: InstallationRevokedException) {
            // Server disabled this installation: wipe everything, re-register once,
            // and bootstrap fresh. Never retry with the dead token.
            wipeAll(appCtx)
            val reg = AyxGroupApi.register()
            AyxGroupStore.saveRegistration(appCtx, reg.installationId, reg.accessToken)
            doSync(appCtx, reg.accessToken)
        }
    }

    private suspend fun doSync(ctx: Context, token: String): SyncResult {
        val cursor = AyxGroupStore.cursor(ctx)
        return if (cursor == NO_CURSOR) {
            val b = AyxGroupApi.bootstrap(token)
            writeList(announcementsFile(ctx), b.announcements.map { it.toJson() })
            writeList(statusesFile(ctx), b.statuses.map { it.toJson() })
            // Cursor advances only after the content is safely on disk.
            AyxGroupStore.saveCursor(ctx, b.cursor)
            SyncResult(emptyList(), fullRefresh = true)
        } else {
            val d = AyxGroupApi.sync(token, cursor)
            val oldAnn = cachedAnnouncements(ctx)
            val oldIds = oldAnn.map { it.id }.toSet()
            val mergedAnn = (oldAnn.filter { it.id !in d.announcementsDeleted } + d.announcementsUpsert)
                .associateBy { it.id }.values.sortedByDescending { it.publishedAt }
            val mergedSt = (cachedStatuses(ctx).filter { it.id !in d.statusesDeleted } + d.statusesUpsert)
                .associateBy { it.id }.values.sortedByDescending { it.createdAt }
            writeList(announcementsFile(ctx), mergedAnn.map { it.toJson() })
            writeList(statusesFile(ctx), mergedSt.map { it.toJson() })
            d.statusesDeleted.forEach { deleteMediaFor(ctx, it) }
            // Cursor advances only after the content is safely on disk.
            AyxGroupStore.saveCursor(ctx, d.cursor)
            SyncResult(d.announcementsUpsert.filter { it.id !in oldIds }, fullRefresh = false)
        }
    }

    /** Wipe credentials + cached content (revocation / identity reset). */
    fun wipeAll(ctx: Context) {
        AyxGroupStore.wipe(ctx)
        val appCtx = ctx.applicationContext
        announcementsFile(appCtx).delete()
        statusesFile(appCtx).delete()
        mediaDir(appCtx).listFiles()?.forEach { it.delete() }
    }

    // ---- status media ----

    /**
     * Bitmap for an image status: served from the app-private media cache, else
     * downloaded with the installation Bearer token (25MB per-file cap, 25MB
     * total cache cap with oldest-first eviction). Only https://api.imayx.in URLs
     * are ever fetched.
     */
    suspend fun statusImageBitmap(ctx: Context, status: AyxStatus): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val url = status.mediaUrl?.takeIf { it.startsWith("https://api.imayx.in/") }
                ?: return@withContext null
            if (status.kind != "image") return@withContext null
            val file = mediaFile(ctx, status.id)
            if (!file.exists()) {
                val token = AyxGroupStore.accessToken(ctx) ?: return@withContext null
                if (!downloadMedia(url, file, token)) return@withContext null
            }
            runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
        }

    private fun mediaFile(ctx: Context, statusId: String): File {
        val safe = statusId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)
        return File(mediaDir(ctx), safe)
    }

    private fun deleteMediaFor(ctx: Context, statusId: String) {
        mediaFile(ctx, statusId).delete()
    }

    private fun downloadMedia(url: String, dest: File, token: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                // Token is sent, never logged.
                setRequestProperty("Authorization", "Bearer $token")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return false
            val tmp = File(dest.parent, dest.name + ".tmp")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_MEDIA_BYTES) {
                            tmp.delete()
                            return false
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                tmp.renameTo(dest)
            }
            enforceMediaCap(dest.parentFile ?: return true)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun enforceMediaCap(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_MEDIA_BYTES) break
            total -= f.length()
            f.delete()
        }
    }
}
