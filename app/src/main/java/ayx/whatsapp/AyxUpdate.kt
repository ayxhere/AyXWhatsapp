package ayx.whatsapp

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Force-update check (v147; release API in v150, dialog-link flow in
 * v151). On each cold launch the app fetches the current release JSON
 * from the backend:
 *
 *   GET https://api.imayx.in/v1/release
 *
 *   {
 *     "versionCode": 151,
 *     "versionName": "6.91",
 *     "apkUrl": "https://imayx.in/ayx/whatsapp",   // dialog download link
 *     "changelog": "Fixed status upload\nFaster chats",
 *     "force": true
 *   }
 *
 * The release is published/deleted by the admin from the AyX Group panel
 * (App release page): he sets the download link + What's New there and
 * the dialog below appears directly in the app — no static file on the
 * website is involved. A 404 from the endpoint simply means "no release
 * published".
 *
 * If the remote versionCode is higher than the installed one, MainActivity
 * shows the update dialog (AyxUpdateDialog): the What's New lines + a
 * Download button that opens apkUrl — the user downloads the update from
 * the website, exactly where the admin's link points. "force" != false
 * makes the dialog non-dismissible — the app behind it stays unusable
 * until the user updates.
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
            conn.connect()
            // v190 (B): pin the release fetch exactly like ayxInstallPost —
            // the JSON drives a force-update dialog, so an unpinned fetch
            // could be MITM'd into a fake forced release pointing at an
            // attacker's APK link. Pin mismatch returns null (no dialog,
            // user never locked out — same as any fetch failure), never
            // a forged release.
            val https = conn as? javax.net.ssl.HttpsURLConnection
            if (https == null || !releasePinOk(https)) { conn.disconnect(); return null }
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

    // v190 (B): SPKI pins for the AyX API — MUST match MainActivity.ayxPinOk
    // (same leaf + Google Trust Services issuer fallback). Kept as a local
    // copy so the proven install path stays byte-untouched; if the pins
    // ever rotate, update both places.
    private fun releasePinOk(conn: javax.net.ssl.HttpsURLConnection): Boolean {
        return try {
            val certs = conn.serverCertificates
            if (certs.isEmpty()) return false
            val md = java.security.MessageDigest.getInstance("SHA-256")
            fun spki(c: java.security.cert.Certificate): String =
                android.util.Base64.encodeToString(md.digest(c.publicKey.encoded), android.util.Base64.NO_WRAP)
            if (spki(certs[0]) == "240JyWTikJS+GftCUvTs0CVe9zNWlaAeD0Bt8vkHgWA=") return true
            certs.any { spki(it) == "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=" }
        } catch (e: Exception) { false }
    }
}
