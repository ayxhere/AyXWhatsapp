package ayx.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Force-update check (v147; release API + direct install in v150). On each
 * cold launch the app fetches the current release JSON from the backend:
 *
 *   GET https://api.imayx.in/v1/release
 *
 *   {
 *     "versionCode": 150,
 *     "versionName": "6.90",
 *     "apkUrl": "https://…/AyXWhatsapp.apk",   // direct APK link
 *     "changelog": "Fixed status upload\nFaster chats",
 *     "force": true
 *   }
 *
 * The release is published/deleted by the admin from the AyX Group panel
 * (App release page) — no static file on the website is involved anymore.
 * A 404 from the endpoint simply means "no release published".
 *
 * If the remote versionCode is higher than the installed one, MainActivity
 * shows the update dialog (AyxUpdateDialog): changelog + a Download
 * button. When apkUrl is a direct .apk link the button downloads the APK
 * inside the app and opens the system installer — the user never lands
 * on the website. Non-APK links keep the old behavior (open in browser).
 * "force" != false makes the dialog non-dismissible — the app behind it
 * stays unusable until the user updates.
 *
 * The check is FAIL-OPEN by design: any network error, missing release or
 * malformed JSON returns null and the user is never locked out because
 * an update check could not run.
 */
object AyxUpdate {
    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val force: Boolean,
    )

    // The site host is derived from the pinned API base (api.imayx.in ->
    // imayx.in) so no second endpoint literal is introduced.
    private fun siteBase(): String = AyxEndpoints.base().replace("://api.", "://")

    fun releaseUrl(): String = AyxEndpoints.base() + "/v1/release"

    fun updatePageUrl(): String = siteBase() + "/ayx/whatsapp"

    fun installedVersionCode(ctx: Context): Int = CrashReporter.versionCode(ctx)

    /** Blocking fetch — call off the main thread. Null on ANY failure. */
    fun fetch(): Info? {
        return try {
            val conn = (URL(releaseUrl()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                val code = o.optInt("versionCode", -1)
                if (code <= 0) return null
                Info(
                    versionCode = code,
                    versionName = o.optString("versionName").ifBlank { code.toString() },
                    apkUrl = o.optString("apkUrl").ifBlank { updatePageUrl() },
                    changelog = o.optString("changelog").trim(),
                    force = o.optBoolean("force", true),
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True when the URL path ends in .apk — a direct download link. */
    fun looksLikeApk(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').trim().lowercase().endsWith(".apk")

    /**
     * Blocking APK download to cacheDir/updates/ (call on IO dispatcher).
     * Reports progress 0..100 when the server sends a content length,
     * otherwise -1. Returns the finished file, or null on any failure —
     * the caller falls back to opening the link in the browser.
     */
    fun downloadApk(ctx: Context, url: String, onProgress: (Int) -> Unit): File? {
        return try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val part = File(dir, "AyXWhatsapp-update.apk.part")
            val target = File(dir, "AyXWhatsapp-update.apk")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.android.package-archive")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -2
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            // Sanity: a real APK is never a few KB of error page.
            if (!part.exists() || part.length() < 100_000L) {
                part.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return null
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Intent that opens the system installer for a downloaded APK. */
    fun installerIntent(ctx: Context, file: File): Intent? {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".fileprovider",
                file,
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            null
        }
    }
}
