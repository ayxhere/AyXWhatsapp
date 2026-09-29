package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Chat appearance preferences — bubble style + chat-header blur/frost.
 * Persisted locally and observable, so changes apply to chats immediately.
 */
object ChatStyle {
    // ios | whatsapp | material | rounded | compact | gb
    val bubbleStyle = mutableStateOf("ios")
    // header frost intensity in dp: 0 (off), 5, 10, 15, 20, 30, 40
    val headerBlurDp = mutableStateOf(10)
    private var prefs: android.content.SharedPreferences? = null

    val bubbleStyles: List<Pair<String, String>> = listOf(
        "ios" to "iOS",
        "whatsapp" to "Normal WhatsApp",
        "material" to "Material",
        "rounded" to "Rounded",
        "compact" to "Compact",
        "gb" to "GB style",
    )
    val blurSteps: List<Int> = listOf(0, 5, 10, 15, 20, 30, 40)

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("chatstyle", Context.MODE_PRIVATE)
        bubbleStyle.value = prefs!!.getString("bubble", "ios") ?: "ios"
        headerBlurDp.value = prefs!!.getInt("headerBlur", 10)
    }

    fun setBubble(s: String) { bubbleStyle.value = s; prefs?.edit()?.putString("bubble", s)?.apply() }
    fun setHeaderBlur(dp: Int) { headerBlurDp.value = dp; prefs?.edit()?.putInt("headerBlur", dp)?.apply() }

    fun bubbleLabel(key: String): String = bubbleStyles.firstOrNull { it.first == key }?.second ?: "iOS"

    /** Header container opacity from blur setting: Off = solid, more dp = more translucent frost. */
    fun headerAlpha(): Float = when (headerBlurDp.value) {
        0 -> 1f; 5 -> 0.94f; 10 -> 0.86f; 15 -> 0.80f; 20 -> 0.72f; 30 -> 0.62f; 40 -> 0.5f; else -> 0.86f
    }
}
