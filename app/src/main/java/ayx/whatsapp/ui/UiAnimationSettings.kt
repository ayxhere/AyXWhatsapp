package ayx.whatsapp.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Fresh implementation of "User Interface" animation preferences.
 *
 * Inspired by the GENERAL idea seen in the reference dialer screenshot
 * (blur toggles, zoom page transitions, motion-blur-during-zoom, scroll
 * fade-in) — all code below is written from scratch for AyXWhatsapp.
 * No code, assets, or resources were copied from any other app.
 *
 * Persisted in SharedPreferences "ayx_ui_animation" so settings survive
 * restarts. Defaults: blur ON, motion blur ON, scroll animation ON,
 * animation style None (fresh users only — a saved choice always wins).
 */

/** UI surfaces that may receive the background blur effect. */
enum class BlurElement(val label: String) {
    NAVIGATION("Navigation"),
    MENUS("Menus"),
    DIALOGS("Dialogs"),
    BOTTOM_SHEET("Bottom Sheet");
}

/** Page-transition animation style. */
enum class AnimationStyle(val label: String, val subtitle: String) {
    ZOOM("Zoom", "Zoom (in/out)"),
    FADE("Fade", "Fade in/out"),
    SLIDE("Slide", "Slide from right"),
    SLIDE_UP("Slide Up", "Slide from bottom"),
    SCALE("Scale", "Grow from center"),
    FADE_SLIDE("Fade+Slide", "Fade with slide-up"),
    NONE("None", "No animation");
}

object UiAnimationSettings {
    val materialBlurEnabled = mutableStateOf(true)
    val blurElements = mutableStateOf(setOf(BlurElement.NAVIGATION, BlurElement.MENUS))
    val animationStyle = mutableStateOf(AnimationStyle.NONE)
    val motionBlurEnabled = mutableStateOf(true)
    val scrollAnimationEnabled = mutableStateOf(true)

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("ayx_ui_animation", Context.MODE_PRIVATE)
        val p = prefs!!
        materialBlurEnabled.value = p.getBoolean("material_blur", true)
        val saved = p.getStringSet("blur_elements", setOf("NAVIGATION", "MENUS")) ?: emptySet()
        blurElements.value = saved.mapNotNull { name ->
            runCatching { BlurElement.valueOf(name) }.getOrNull()
        }.toSet().ifEmpty { setOf(BlurElement.NAVIGATION, BlurElement.MENUS) }
        animationStyle.value = runCatching {
            AnimationStyle.valueOf(p.getString("animation_style", "NONE") ?: "NONE")
        }.getOrDefault(AnimationStyle.NONE)
        motionBlurEnabled.value = p.getBoolean("motion_blur", true)
        scrollAnimationEnabled.value = p.getBoolean("scroll_animation", true)
    }

    private fun save() {
        prefs?.edit()
            ?.putBoolean("material_blur", materialBlurEnabled.value)
            ?.putStringSet("blur_elements", blurElements.value.map { it.name }.toSet())
            ?.putString("animation_style", animationStyle.value.name)
            ?.putBoolean("motion_blur", motionBlurEnabled.value)
            ?.putBoolean("scroll_animation", scrollAnimationEnabled.value)
            ?.apply()
    }

    fun setMaterialBlur(enabled: Boolean) {
        materialBlurEnabled.value = enabled
        save()
    }

    fun toggleBlurElement(element: BlurElement) {
        val current = blurElements.value.toMutableSet()
        if (!current.add(element)) current.remove(element)
        if (current.isEmpty()) current.add(BlurElement.NAVIGATION)
        blurElements.value = current
        save()
    }

    fun setAnimationStyle(style: AnimationStyle) {
        animationStyle.value = style
        save()
    }

    fun setMotionBlur(enabled: Boolean) {
        motionBlurEnabled.value = enabled
        save()
    }

    fun setScrollAnimation(enabled: Boolean) {
        scrollAnimationEnabled.value = enabled
        save()
    }

    /** True when [element] should be blurred and the master blur toggle is ON. */
    fun blurAppliesTo(element: BlurElement): Boolean =
        materialBlurEnabled.value && blurElements.value.contains(element)
}
