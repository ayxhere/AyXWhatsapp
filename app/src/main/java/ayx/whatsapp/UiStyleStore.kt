package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * UI style preferences — separate from Appearance/theme settings.
 *
 * Stores the selected interface style (default | neumorphism |
 * glassmorphism | skeuomorphism | claymorphism). Persisted independently
 * so changing the UI style never resets theme mode, accent color,
 * or AMOLED settings.
 */
object UiStyleStore {
    val uiStyle = mutableStateOf("default")
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("ui_style", Context.MODE_PRIVATE)
        uiStyle.value = prefs!!.getString("style", "default") ?: "default"
    }

    fun setUiStyle(id: String) {
        uiStyle.value = id
        prefs?.edit()?.putString("style", id)?.apply()
    }
}
