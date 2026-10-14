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
    // system | light | dark | amoled
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
    )

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
        mode.value = prefs!!.getString("mode", "system") ?: "system"
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
