package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * App theme preferences — accent color + theme mode.
 * Persisted locally and observable, so changing them in Appearance settings
 * re-themes the whole app immediately.
 *
 * NOTE: UI styles (neumorphism/glassmorphism/skeuomorphism/claymorphism)
 * are managed by UiStyleStore, the single source of truth. The legacy
 * appearanceStyle field is kept for migration only and delegates to
 * UiStyleStore.
 */
object ThemeStore {
    // system | light | amoled  ("dark" was removed — stored values migrate to amoled in init)
    val mode = mutableStateOf("system")
    // key into [accents]
    val accent = mutableStateOf("purple")

    /**
     * Legacy: delegates to UiStyleStore.uiStyle.
     * Do not use directly — use UiStyleStore instead.
     */
    @Deprecated("Use UiStyleStore.uiStyle instead", ReplaceWith("UiStyleStore.uiStyle.value"))
    var appearanceStyle: String
        get() = UiStyleStore.uiStyle.value.let {
            // map "default" to "neumorphism" for legacy callers
            if (it == "default") "neumorphism" else it
        }
        set(value) { UiStyleStore.setUiStyle(value) }

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
        "yellow" to Color(0xFFFFD54F),
        "lime" to Color(0xFFAED581),
        "cyan" to Color(0xFF4DD0E1),
        "indigo" to Color(0xFF7986CB),
        "deeppurple" to Color(0xFF9575CD),
        "magenta" to Color(0xFFE040FB),
        "brown" to Color(0xFFA1887F),
        "grey" to Color(0xFF90A4AE),
    )

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
        mode.value = prefs!!.getString("mode", "system") ?: "system"
        // The standalone "Dark" theme option was removed (user request,
        // 2026-10-21): a stored "dark" now means AMOLED. Migrate the stored
        // value itself so no selector ever has to display a mode that no
        // longer exists; rendering of any leftover "dark" is also mapped
        // to AMOLED at the theme-resolution site as a safety net.
        if (mode.value == "dark") {
            mode.value = "amoled"
            prefs!!.edit().putString("mode", "amoled").apply()
        }
        accent.value = prefs!!.getString("accent", "purple") ?: "purple"
        // Migrate legacy appearance_style to UiStyleStore if present
        val legacy = prefs!!.getString("appearance_style", null)
        if (legacy != null && UiStyleStore.uiStyle.value == "default") {
            // Only migrate if user hasn't already chosen a UI style
            UiStyleStore.setUiStyle(legacy)
        }
    }

    fun accentColor(): Color =
        accents.firstOrNull { it.first == accent.value }?.second ?: Color(0xFFB69DF8)

    fun setMode(m: String) {
        mode.value = m
        prefs?.edit()?.putString("mode", m)?.apply()
    }

    fun setAccent(a: String) {
        accent.value = a
        prefs?.edit()?.putString("accent", a)?.apply()
    }
}
