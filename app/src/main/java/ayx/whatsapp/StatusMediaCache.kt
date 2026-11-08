package ayx.whatsapp

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v259: local cache of status media so a deleted status still plays.
 *
 * Root cause: a status the sender deletes arrives as a tombstone
 * (sender + ts, NO media). StatusData.merge (v146) preserves the
 * cached copy's media linkage onto the tombstone — but only when the
 * app saw the status while it was still live AND the media bytes
 * were on this device. Media was only ever fetched when the viewer
 * opened a status, so a status posted and deleted before anyone
 * opened it had no local copy at all: the viewer fell through to
 * the text branch (teal background + "…") with nothing to show.
 *
 * Fix: prefetch every live status's media in the background as soon
 * as the status list refreshes (the Status tab polls every 5s), and
 * make the viewer read the local file first. After a revoke the
 * gateway copy is gone, so the prefetched file is the only copy
 * left — the viewer keeps playing it under "Status Deleted".
 * Files are written via .tmp + rename (never a half file), failed
 * downloads retry after 60s (the gateway itself may still be
 * fetching the media), and files for statuses gone from the list
 * are pruned once older than 24h (the statuses themselves expire
 * then too).
 */
object StatusMediaCache {
    private const val DIR = "status-media"
    private const val RETRY_MS = 60_000L
    private const val MAX_AGE_MS = 24L * 3600L * 1000L

    private val running = AtomicBoolean(false)
    private val lastAttempt = HashMap<String, Long>()

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun fileFor(ctx: Context, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir(ctx), safe)
    }

    fun hasLocal(ctx: Context, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val f = fileFor(ctx, name)
        return f.exists() && f.length() > 0
    }

    /** Local file URI for playback (ExoPlayer / MediaPlayer), null when not cached. */
    fun localUri(ctx: Context, name: String?): String? =
        if (hasLocal(ctx, name)) Uri.fromFile(fileFor(ctx, name!!)).toString() else null

    /** Decode a cached image off the main thread, null when absent/corrupt. */
    suspend fun localBitmap(ctx: Context, name: String?): android.graphics.Bitmap? {
        if (name.isNullOrBlank()) return null
        val f = fileFor(ctx, name)
        if (!f.exists() || f.length() == 0L) return null
        return withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        }
    }

    /** Best-effort background prefetch of every live status's media. */
    suspend fun prefetch(ctx: Context, items: List<GatewayClient.StatusItem>) {
        if (!running.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                val live = HashSet<String>()
                val now = System.currentTimeMillis()
                for (s in items) {
                    val name = s.mediaName ?: continue
                    if (s.deleted) continue
                    val f = fileFor(ctx, name)
                    live.add(f.name)
                    if (f.exists() && f.length() > 0) continue
                    val last = synchronized(lastAttempt) { lastAttempt[name] ?: 0L }
                    if (now - last < RETRY_MS) continue
                    synchronized(lastAttempt) { lastAttempt[name] = now }
                    runCatching {
                        val bytes = GatewayClient.mediaBytes(name) ?: return@runCatching
                        if (bytes.isEmpty()) return@runCatching
                        val tmp = File(dir(ctx), f.name + ".tmp")
                        tmp.writeBytes(bytes)
                        tmp.renameTo(f)
                    }
                }
                // prune: drop cached files for statuses no longer live,
                // once older than 24h (deleted statuses keep playing until then)
                runCatching {
                    val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                    dir(ctx).listFiles()?.forEach { f ->
                        if (f.isFile && !f.name.endsWith(".tmp") && !live.contains(f.name) && f.lastModified() < cutoff) f.delete()
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }
}
