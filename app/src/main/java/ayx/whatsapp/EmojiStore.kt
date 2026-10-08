package ayx.whatsapp

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloadable emoji styles. Each is an EmojiCompat-format emoji font fetched from the user's API,
 * saved to Download/WhatsAyX/fonts/emoji (visible to the user) + app-internal storage (for loading),
 * then applied app-wide through EmojiCompat. EmojiCompat.init is process-wide and one-shot, so a
 * newly-picked style renders everywhere from the NEXT app launch.
 */
object EmojiStore {
    data class Style(val name: String, val url: String, val key: String)

    const val SYSTEM = "System default"

    // Endpoints exactly as provided: api.imayx.in/font1 (iOS), /font2 (FluentUI), /3 (JoyPixel), /4 (OneUI).
    val styles: List<Style> = listOf(
        Style("iOS", "https://api.imayx.in/font1", "ios"),
        Style("FluentUI", "https://api.imayx.in/font2", "fluentui"),
        Style("JoyPixel", "https://api.imayx.in/3", "joypixel"),
        Style("OneUI", "https://api.imayx.in/4", "oneui"),
    )

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

    // Downloads the emoji font to app-internal storage (for EmojiCompat) + public Download/WhatsAyX/fonts/emoji.
    suspend fun download(ctx: Context, style: Style): Boolean = withContext(Dispatchers.IO) {
        try {
            val c = (URL(style.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
            }
            if (c.responseCode !in 200..299) return@withContext false
            val bytes = c.inputStream.use { it.readBytes() }
            if (bytes.size < 256) return@withContext false
            fontFile(ctx, style.key).writeBytes(bytes)
            runCatching { savePublic(ctx, bytes, style.name + ".ttf") }
            true
        } catch (e: Exception) { false }
    }

    private fun savePublic(ctx: Context, bytes: ByteArray, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "font/ttf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhatsAyX/fonts/emoji")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }

    fun choose(name: String) { selected = name; prefs?.edit()?.putString("style", name)?.apply() }

    // Apply the selected emoji font via EmojiCompat. Call once at startup (one-shot per process).
    fun initEmojiCompat(ctx: Context) {
        try {
            val st = styleByName(selected) ?: return
            val f = fontFile(ctx, st.key)
            if (!f.exists() || f.length() == 0L) return
            val tf = Typeface.createFromFile(f)
            val meta = MetadataRepo.create(tf, FileInputStream(f))
            val loader = object : EmojiCompat.MetadataRepoLoader {
                override fun load(cb: EmojiCompat.MetadataRepoLoaderCallback) { cb.onLoaded(meta) }
            }
            EmojiCompat.init(object : EmojiCompat.Config(loader) {})
        } catch (e: Throwable) {
            // font isn't EmojiCompat-format, or EmojiCompat already inited this process — ignore.
        }
    }
}
