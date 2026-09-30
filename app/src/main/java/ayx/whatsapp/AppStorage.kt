package ayx.whatsapp

import android.content.Context
import java.io.File

/**
 * Storage layout + cleanup helpers.
 *
 * Node-owned (via NodeRuntime env):
 *   filesDir/media           -> downloaded/cached media (re-downloadable) — the thing that grows
 *   filesDir/nodejs-project  -> extracted node runtime (re-extracts on update)
 *   noBackupFilesDir/auth    -> Baileys session (ESSENTIAL)
 *   noBackupFilesDir/*.json  -> messages/statuses/settings/names
 * App-owned:
 *   filesDir/wallpaper.jpg   -> user wallpaper (keep unless full reset)
 *   cacheDir                 -> node compile cache + temp
 *   SharedPreferences        -> theme, chatstyle, setname, contactstore, statusdata, wagw
 *
 * Clear Cache = re-downloadable media + cache only (no logout, no settings/chat loss).
 * Clear Data  = destructive full reset (node /cleardata + local prefs + wallpaper).
 */
object AppStorage {
    private val PREF_NAMES = listOf("theme", "chatstyle", "setname", "contactstore", "statusdata", "wagw")

    fun mediaDir(ctx: Context) = File(ctx.filesDir, "media")
    fun nodeDir(ctx: Context) = File(ctx.filesDir, "nodejs-project")
    fun wallpaperFile(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    fun dirSize(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        return runCatching { f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }

    fun mediaSize(ctx: Context) = dirSize(mediaDir(ctx))
    fun cacheSize(ctx: Context) = dirSize(ctx.cacheDir)
    fun runtimeSize(ctx: Context) = dirSize(nodeDir(ctx))
    fun totalData(ctx: Context) = dirSize(ctx.filesDir) + dirSize(ctx.noBackupFilesDir) + dirSize(ctx.cacheDir)

    /** local cache wipe (media handled node-side via /clearcache). Safe. */
    fun clearCacheLocal(ctx: Context) {
        runCatching { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    /** local part of a full reset: wallpaper + prefs + cache. Node side is /cleardata. */
    fun clearDataLocal(ctx: Context) {
        runCatching { wallpaperFile(ctx).delete() }
        PREF_NAMES.forEach { runCatching { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().apply() } }
        runCatching { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    fun fmtSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
}
