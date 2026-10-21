package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * iOS-style message selection — the long-press message menu effect.
 *
 * Fresh AyX implementation of the behaviour popularised by iOS Telegram
 * (and the exteraGram "IOS-like selection" plugin, used as a behavioural
 * reference only — no plugin code is copied): long-pressing a message
 * blurs the chat behind it, the pressed message stays floating at its own
 * position over the blur, the reaction pill sits on top of it and the
 * action menu below it, everything popping in with a short scale/fade
 * animation on the message's own side of the screen.
 *
 * When OFF, the long-press menu keeps the plain presentation (centered
 * sheet, no blur, no pop-in) exactly as before this setting existed.
 *
 * Persisted in SharedPreferences ("ayx_ios_selection"), observable so the
 * chat re-renders the moment the toggle flips.
 */
object IosSelectionSettings {
    val enabled = mutableStateOf(true)

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_ios_selection", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", true)
    }

    fun setEnabled(v: Boolean) {
        enabled.value = v
        prefs?.edit()?.putBoolean("enabled", v)?.apply()
    }
}
