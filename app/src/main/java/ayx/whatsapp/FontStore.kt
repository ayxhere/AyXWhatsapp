package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.FontStyle
import android.graphics.fonts.SystemFonts
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import android.graphics.fonts.FontFamily as AFontFamily

// App + status-lyrics fonts: bundled ones under assets/fonts/, plus any .ttf/.otf the user uploads
// (kept in filesDir/userfonts). Used by the app-wide font picker (Settings → General → Font & Emoji)
// and the status lyrics font picker.
object FontStore {
    // display name -> asset path under assets/fonts ("" = system default)
    val bundled: LinkedHashMap<String, String> = linkedMapOf(
        "Default" to "",
        "Lobster" to "fonts/Lobster-Regular.otf",
        "Cabin" to "fonts/Cabin-Regular.otf",
        "Comic Neue" to "fonts/ComicNeue-Regular.otf",
        "Adelia Shawn" to "fonts/adelia_shawn.otf",
        "I Hate Comic Sans" to "fonts/i_hate_comic_sans.ttf",
        "Kiddosy" to "fonts/kiddosy_regular.ttf",
        "Kiddosy Outline" to "fonts/kiddosy_outline.ttf",
        "Komika Display" to "fonts/kmkdsp__.ttf",
        "Komika Bold" to "fonts/kmkdspb_.ttf",
        "Komika Wide" to "fonts/kmkdspw_.ttf",
        "Komika Kaps" to "fonts/kmkdspk_.ttf",
        "Komika Shadow" to "fonts/kmkdspsh.ttf",
        "Komika Tight" to "fonts/kmkdspt_.ttf",
        "Ogonek" to "fonts/ogonek_regular.ttf",
        "Ogonek Bold" to "fonts/ogonek_bold.ttf",
        "Ogonek Heavy" to "fonts/ogonek_heavy.ttf",
        "Ogonek Italic" to "fonts/ogonek_italic.ttf",
        "Ogonek Light" to "fonts/ogonek_light.ttf",
        "Ogonek Unicase" to "fonts/ogonek_unicase.ttf",
        "Yaelah" to "fonts/yaelah.ttf",
    )

    // user-uploaded fonts: display name -> absolute file path. Observable so the picker updates live.
    private val custom = mutableStateMapOf<String, String>()

    // bundled + custom, in that order
    val fonts: LinkedHashMap<String, String> get() = LinkedHashMap(bundled).apply { putAll(custom) }

    var appFont by mutableStateOf("Default")
    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, FontFamily?>()

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("fonts", Context.MODE_PRIVATE)
        prefs = p
        appFont = p.getString("app", "Default") ?: "Default"
        val names = p.getStringSet("custom", emptySet()) ?: emptySet()
        for (n in names) { val f = File(userDir(ctx), sanitize(n) + ".ttf"); if (f.exists()) custom[n] = f.absolutePath }
    }

    fun chooseFont(name: String) { appFont = name; prefs?.edit()?.putString("app", name)?.apply() }

    private fun userDir(ctx: Context) = File(ctx.filesDir, "userfonts").apply { mkdirs() }
    private fun sanitize(n: String) = n.replace(Regex("[^A-Za-z0-9_ -]"), "_").trim().ifBlank { "font" }

    private fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // Copy a picked .ttf/.otf into app storage and register it. Returns the display name (to select it), or null.
    fun addCustomFont(ctx: Context, uri: Uri): String? = runCatching {
        val raw = queryName(ctx, uri)?.substringBeforeLast('.')?.trim().orEmpty()
        val disp = (if (raw.isNotBlank()) raw else "Custom ${custom.size + 1}")
        val file = File(userDir(ctx), sanitize(disp) + ".ttf")
        ctx.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: return null
        if (file.length() == 0L) return null
        custom[disp] = file.absolutePath
        val cur = prefs?.getStringSet("custom", emptySet())?.toMutableSet() ?: mutableSetOf()
        cur.add(disp); prefs?.edit()?.putStringSet("custom", cur)?.apply()
        cache.remove(disp)
        comboCache.keys.removeAll { it.startsWith(disp + "|") }
        disp
    }.getOrNull()

    // FontFamily for a name (null = system default). Cached; failures fall back to null (system).
    // When an emoji style is selected, the emoji font is chained in as the glyph fallback (API 29+),
    // so emojis render in that style everywhere this family is used (text keeps the chosen font).
    fun family(ctx: Context, name: String): FontFamily? {
        val path = fonts[name] ?: ""
        val emoji = EmojiStore.activeFont(ctx)          // reads EmojiStore.selected -> recomposes on change
        if (emoji != null && Build.VERSION.SDK_INT >= 29) {
            val ck = name + "|" + EmojiStore.selected
            val hit = comboCache[ck]
            if (hit != null) return hit
            val fam = combined(ctx, path, emoji)
            if (fam != null) { comboCache[ck] = fam; return fam }
        }
        if (path.isBlank()) return null
        return cache.getOrPut(name) {
            runCatching {
                if (path.startsWith("/")) FontFamily(Font(File(path))) else FontFamily(Font(path, ctx.assets))
            }.getOrNull()
        }
    }

    private val comboCache = HashMap<String, FontFamily>()
    private var sysPrimary: android.graphics.fonts.Font? = null
    private var sysPrimaryDone = false

    // The phone's regular UI font, found by matching Typeface.DEFAULT's metrics. Needed so the emoji font
    // (which also carries digits/#/* for keycaps) never takes over normal text.
    @RequiresApi(29)
    private fun systemPrimary(): android.graphics.fonts.Font? {
        if (sysPrimaryDone) return sysPrimary
        sysPrimaryDone = true
        runCatching {
            val probe = "Hamburgefonstiv 0123456789 gjpqy"
            val paint = Paint()
            paint.typeface = Typeface.DEFAULT
            val ref = paint.measureText(probe)
            for (f in SystemFonts.getAvailableFonts()) {
                if (f.style.weight != 400 || f.style.slant != FontStyle.FONT_SLANT_UPRIGHT) continue
                val tf = Typeface.CustomFallbackBuilder(AFontFamily.Builder(f).build()).build()
                paint.typeface = tf
                if (kotlin.math.abs(paint.measureText(probe) - ref) < 0.01f) { sysPrimary = f; break }
            }
        }
        return sysPrimary
    }

    @RequiresApi(29)
    private fun combined(ctx: Context, path: String, emoji: android.graphics.fonts.Font): FontFamily? = runCatching {
        val primary: android.graphics.fonts.Font? = when {
            path.isBlank() -> systemPrimary()
            path.startsWith("/") -> android.graphics.fonts.Font.Builder(File(path)).build()
            else -> android.graphics.fonts.Font.Builder(ctx.assets, path).build()
        }
        val emojiFam = AFontFamily.Builder(emoji).build()
        val b = if (primary != null)
            Typeface.CustomFallbackBuilder(AFontFamily.Builder(primary).build()).addCustomFallback(emojiFam)
        else Typeface.CustomFallbackBuilder(emojiFam)
        b.setSystemFallback("sans-serif")
        FontFamily(b.build())
    }.getOrNull()

    fun appFamily(ctx: Context): FontFamily? = family(ctx, appFont)

    // Build a Typography where every text style uses the chosen family (null = default Typography).
    fun typographyFor(fam: FontFamily?): Typography {
        val b = Typography()
        if (fam == null) return b
        return b.copy(
            displayLarge = b.displayLarge.copy(fontFamily = fam),
            displayMedium = b.displayMedium.copy(fontFamily = fam),
            displaySmall = b.displaySmall.copy(fontFamily = fam),
            headlineLarge = b.headlineLarge.copy(fontFamily = fam),
            headlineMedium = b.headlineMedium.copy(fontFamily = fam),
            headlineSmall = b.headlineSmall.copy(fontFamily = fam),
            titleLarge = b.titleLarge.copy(fontFamily = fam),
            titleMedium = b.titleMedium.copy(fontFamily = fam),
            titleSmall = b.titleSmall.copy(fontFamily = fam),
            bodyLarge = b.bodyLarge.copy(fontFamily = fam),
            bodyMedium = b.bodyMedium.copy(fontFamily = fam),
            bodySmall = b.bodySmall.copy(fontFamily = fam),
            labelLarge = b.labelLarge.copy(fontFamily = fam),
            labelMedium = b.labelMedium.copy(fontFamily = fam),
            labelSmall = b.labelSmall.copy(fontFamily = fam),
        )
    }
}
