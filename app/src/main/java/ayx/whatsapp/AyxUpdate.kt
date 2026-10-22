package ayx.whatsapp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Force-update check (v147). On each cold launch the app fetches a tiny
 * JSON from the site:
 *
 *   https://imayx.in/ayx/whatsapp/update.json
 *
 *   {
 *     "versionCode": 148,
 *     "versionName": "6.88",
 *     "apkUrl": "https://imayx.in/ayx/whatsapp/app.apk",   // optional
 *     "changelog": "Fixed status upload\nFaster chats",    // optional
 *     "force": true                                        // optional, default true
 *   }
 *
 * If the remote versionCode is higher than the installed one, MainActivity
 * shows the update dialog (AyxUpdateDialog): changelog + a Download
 * button that opens apkUrl (or the update page when apkUrl is absent).
 * "force" != false makes the dialog non-dismissible — the app behind it
 * stays unusable until the user updates.
 *
 * The check is FAIL-OPEN by design: any network error, missing file or
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

    fun updateJsonUrl(): String = siteBase() + "/ayx/whatsapp/update.json"

    fun updatePageUrl(): String = siteBase() + "/ayx/whatsapp"

    fun installedVersionCode(ctx: Context): Int = CrashReporter.versionCode(ctx)

    /** Blocking fetch — call off the main thread. Null on ANY failure. */
    fun fetch(): Info? {
        return try {
            val conn = (URL(updateJsonUrl()).openConnection() as HttpURLConnection).apply {
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
}
