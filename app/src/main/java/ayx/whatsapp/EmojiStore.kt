package ayx.whatsapp

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * Downloadable emoji styles (plain colour-emoji .ttf fonts from the user's API). Saved to
 * Download/WhatsAyX/fonts/emoji (visible to the user) + app-internal storage (for loading).
 * Applied app-wide as a glyph FALLBACK of the app font (see FontStore.family) — works with ordinary
 * emoji fonts and applies instantly, no restart.
 *
 * Link security: HTTPS only on every hop (no downgrade), bounded redirects, size cap, font-magic
 * validation, atomic write, and a SHA-256 integrity seal checked every time the font is loaded.
 */
object EmojiStore {
    data class Style(val name: String, val url: String, val key: String)

    const val SYSTEM = "System default"

    // Endpoints: api.imayx.in/font/1 (iOS), /font/2 (FluentUI), /font/3 (JoyPixel), /font/4 (OneUI).
    val styles: List<Style> = listOf(
        Style("iOS", "https://api.imayx.in/font/1", "ios"),
        Style("FluentUI", "https://api.imayx.in/font/2", "fluentui"),
        Style("JoyPixel", "https://api.imayx.in/font/3", "joypixel"),
        Style("OneUI", "https://api.imayx.in/font/4", "oneui"),
    )

    private const val MAX_BYTES = 120L * 1024 * 1024
    private val UA = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 AyX/6.63"

    var selected by mutableStateOf(SYSTEM)
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE)
        prefs = p
        selected = p.getString("style", SYSTEM) ?: SYSTEM
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "emoji").apply { mkdirs() }
    fun fontFile(ctx: Context, key: String): File = File(dir(ctx), "$key.ttf")
    fun isDownloaded(ctx: Context, key: String): Boolean = fontFile(ctx, key).let { it.exists() && it.length() > 0 }
    fun styleByName(name: String): Style? = styles.firstOrNull { it.name == name }

    private fun kind(h: ByteArray): String {
        if (h.size < 4) return "bad"
        val m = String(h, 0, 4, Charsets.ISO_8859_1)
        return when {
            m == "\u0000\u0001\u0000\u0000" || m == "true" || m == "OTTO" || m == "ttcf" -> "font"
            m.startsWith("PK") -> "zip"
            m == "wOFF" || m == "wOF2" -> "woff"
            m.startsWith("<") -> "html"
            else -> "bad"
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val b = ByteArray(64 * 1024); var n = i.read(b); while (n > 0) { md.update(b, 0, n); n = i.read(b) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // HTTPS-only GET with manual redirects (every hop must stay https), streamed to [out].
    private fun fetch(url0: String, out: File): String? {
        var url = url0
        for (hop in 0..4) {
            val u = URL(url)
            if (u.protocol != "https") return "Blocked insecure link"
            val c = (u.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 20000; readTimeout = 90000
                instanceFollowRedirects = false; useCaches = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return "Bad redirect"
                    url = URL(u, loc).toString(); continue
                }
                if (code !in 200..299) return "Server said HTTP $code"
                val len = c.contentLengthLong
                if (len > MAX_BYTES) return "File too large"
                var total = 0L
                c.inputStream.use { i -> FileOutputStream(out).use { o ->
                    val b = ByteArray(64 * 1024); var n = i.read(b)
                    while (n >= 0) { total += n; if (total > MAX_BYTES) return "File too large"; o.write(b, 0, n); n = i.read(b) }
                } }
                val want = c.getHeaderField("X-Content-SHA256")?.trim()?.lowercase()
                if (!want.isNullOrBlank() && want != sha256(out)) return "Integrity check failed"
                return null
            } finally { c.disconnect() }
        }
        return "Too many redirects"
    }

    private fun head(f: File): ByteArray = f.inputStream().use { i -> ByteArray(4).also { i.read(it) } }

    // Downloads the emoji font. Returns null on success, or a short human-readable error.
    suspend fun download(ctx: Context, style: Style): String? = withContext(Dispatchers.IO) {
        val dst = fontFile(ctx, style.key)
        val tmp = File(dst.parentFile, dst.name + ".part")
        try {
            val err = fetch(style.url, tmp)
            if (err != null) return@withContext err
            if (tmp.length() < 256) return@withContext "Empty response"
            var k = kind(head(tmp))
            if (k == "zip") {   // some servers wrap the font in a zip — pull the first .ttf/.otf out
                val ex = File(dst.parentFile, dst.name + ".zipout")
                var found = false
                ZipInputStream(tmp.inputStream()).use { z ->
                    var e = z.nextEntry
                    while (e != null && !found) {
                        val n = e.name.lowercase()
                        if (!e.isDirectory && (n.endsWith(".ttf") || n.endsWith(".otf"))) {
                            FileOutputStream(ex).use { o -> z.copyTo(o) }; found = true
                        } else e = z.nextEntry
                    }
                }
                if (!found) { tmp.delete(); return@withContext "No font inside zip" }
                tmp.delete(); ex.renameTo(tmp); k = kind(head(tmp))
            }
            when (k) {
                "font" -> {}
                "woff" -> { tmp.delete(); return@withContext "Server sent WOFF — need a .ttf" }
                "html" -> { tmp.delete(); return@withContext "Server sent a web page, not a font" }
                else -> { tmp.delete(); return@withContext "Not a valid font file" }
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
            prefs?.edit()?.putString("sha_" + style.key, sha256(dst))?.apply()   // integrity seal
            cachedKey = null; cachedFont = null
            runCatching { savePublic(ctx, dst, style.name + ".ttf") }
            null
        } catch (e: javax.net.ssl.SSLException) { tmp.delete(); "Secure connection failed"
        } catch (e: java.net.SocketTimeoutException) { tmp.delete(); "Timed out — try again"
        } catch (e: java.net.UnknownHostException) { tmp.delete(); "No internet"
        } catch (e: Exception) { tmp.delete(); "Download failed (${e.javaClass.simpleName})" }
    }

    private fun savePublic(ctx: Context, src: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "font/ttf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhatsAyX/fonts/emoji")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
    }

    fun choose(name: String) { selected = name; prefs?.edit()?.putString("style", name)?.apply() }

    private var cachedKey: String? = null
    private var cachedFont: android.graphics.fonts.Font? = null

    // The currently selected emoji font as an Android Font (null = system emoji / unsupported / tampered / invalid).
    // Needs API 29+ (custom font fallback chains).
    fun activeFont(ctx: Context): android.graphics.fonts.Font? {
        if (Build.VERSION.SDK_INT < 29) return null
        val st = styleByName(selected) ?: return null
        if (cachedKey == st.key) return cachedFont
        val f = fontFile(ctx, st.key)
        var font: android.graphics.fonts.Font? = null
        if (f.exists() && f.length() > 0) {
            val seal = prefs?.getString("sha_" + st.key, null)
            val intact = seal == null || seal == runCatching { sha256(f) }.getOrNull()   // no seal = older download, accept
            if (intact) font = runCatching { android.graphics.fonts.Font.Builder(f).build() }.getOrNull()
            else f.delete()   // modified on disk → discard, user must re-download
        }
        cachedKey = st.key; cachedFont = font
        return font
    }
}
