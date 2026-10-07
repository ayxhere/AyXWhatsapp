package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

// App + status-lyrics fonts, bundled under assets/fonts/. Used by the app-wide font picker
// (Settings → General → App font) and by the status lyrics font picker.
object FontStore {
    // display name -> asset path under assets/fonts ("" = system default)
    val fonts: LinkedHashMap<String, String> = linkedMapOf(
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

    var appFont by mutableStateOf("Default")
    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, FontFamily?>()

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("fonts", Context.MODE_PRIVATE)
        prefs = p
        appFont = p.getString("app", "Default") ?: "Default"
    }

    fun setAppFont(name: String) { appFont = name; prefs?.edit()?.putString("app", name)?.apply() }

    // FontFamily for a name (null = system default). Cached; failures fall back to null (system).
    fun family(ctx: Context, name: String): FontFamily? {
        val path = fonts[name] ?: return null
        if (path.isBlank()) return null
        return cache.getOrPut(name) { runCatching { FontFamily(Font(path, ctx.assets)) }.getOrNull() }
    }

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
