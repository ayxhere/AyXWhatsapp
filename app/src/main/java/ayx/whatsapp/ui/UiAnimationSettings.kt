package ayx.whatsapp.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import ayx.whatsapp.Lang

/**
 * Fresh implementation of "User Interface" animation preferences.
 *
 * Inspired by the GENERAL idea seen in the reference dialer screenshot
 * (blur toggles, zoom page transitions, motion-blur-during-zoom, scroll
 * fade-in) — all code below is written from scratch for AyXWhatsapp.
 * No code, assets, or resources were copied from any other app.
 *
 * Persisted in SharedPreferences "ayx_ui_animation" so settings survive
 * restarts. Defaults mirror the screenshot: blur ON, motion blur ON,
 * scroll animation ON, animation style Zoom.
 */

/** UI surfaces that may receive the background blur effect. */
enum class BlurElement(val labelKey: String) {
    NAVIGATION("blur_nav"),
    MENUS("blur_menus"),
    DIALOGS("blur_dialogs"),
    BOTTOM_SHEET("blur_bottom");
    val label: String get() = Lang.s(labelKey)
}

/** Page-transition animation style. */
enum class AnimationStyle(val labelKey: String, val subtitleKey: String) {
    ZOOM("anim_zoom", "anim_zoom_d"),
    FADE("anim_fade", "anim_fade_d"),
    SLIDE("anim_slide", "anim_slide_d"),
    SLIDE_UP("anim_slideup", "anim_slideup_d"),
    SCALE("anim_scale", "anim_scale_d"),
    FADE_SLIDE("anim_fadeslide", "anim_fadeslide_d"),
    NONE("anim_none", "anim_none_d");
    val label: String get() = Lang.s(labelKey)
    val subtitle: String get() = Lang.s(subtitleKey)
}

object UiAnimationSettings {
    val materialBlurEnabled = mutableStateOf(true)
    val blurElements = mutableStateOf(setOf(BlurElement.NAVIGATION, BlurElement.MENUS))
    val animationStyle = mutableStateOf(AnimationStyle.ZOOM)
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
            AnimationStyle.valueOf(p.getString("animation_style", "ZOOM") ?: "ZOOM")
        }.getOrDefault(AnimationStyle.ZOOM)
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
