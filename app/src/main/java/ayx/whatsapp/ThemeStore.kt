package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * App theme preferences — accent color + theme mode + premium appearance style.
 * Persisted locally and observable, so changing them in Appearance settings
 * re-themes the whole app immediately.
 */
object ThemeStore {
    // system | light | dark | amoled
    val mode = mutableStateOf("system")
    // key into [accents]
    val accent = mutableStateOf("purple")
    // premium appearance style id: neumorphism | glassmorphism | skeuomorphism | claymorphism
    val appearanceStyle = mutableStateOf("neumorphism")
    private var prefs: android.content.SharedPreferences? = null

    // ordered so the Appearance swatches render in a nice sequence
    val accents: List<Pair<String, Color>> = listOf(
        "purple" to Color(0xFFB69DF8),
        "blue" to Color(0xFF82AAFF),
        "teal" to Color(0xFF4DD0C4),
        "green" to Color(0xFF7BD88F),
        "orange" to Color(0xFFFFB26B),
        "red" to Color(0xFFFF6B6B),
        "pink" to Color(0xFFFF7EB6),
    )

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
        mode.value = prefs!!.getString("mode", "system") ?: "system"
        accent.value = prefs!!.getString("accent", "purple") ?: "purple"
        appearanceStyle.value = prefs!!.getString("appearance_style", "neumorphism") ?: "neumorphism"
    }

    fun setMode(m: String) { mode.value = m; prefs?.edit()?.putString("mode", m)?.apply() }
    fun setAccent(a: String) { accent.value = a; prefs?.edit()?.putString("accent", a)?.apply() }
    fun setAppearanceStyle(id: String) {
        appearanceStyle.value = id
        prefs?.edit()?.putString("appearance_style", id)?.apply()
    }

    fun accentColor(): Color = accents.firstOrNull { it.first == accent.value }?.second ?: accents[0].second
    fun colorOf(key: String): Color = accents.firstOrNull { it.first == key }?.second ?: Color.Gray

    /** Resolve the active premium theme from the stored style id. */
    fun currentAyxTheme(): AyxTheme = ayxThemeById(appearanceStyle.value)
}
