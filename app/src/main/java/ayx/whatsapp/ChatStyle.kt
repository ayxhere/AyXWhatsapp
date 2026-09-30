package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * Chat appearance preferences — bubble style, custom bubble colors, and chat-header frost.
 * Persisted locally and observable, so changes apply to chats immediately.
 */
object ChatStyle {
    // ios | whatsapp | material | rounded | compact | gb
    val bubbleStyle = mutableStateOf("ios")
    // header frost intensity in dp: 0 (off), 5, 10, 15, 20, 30, 40
    val headerBlurDp = mutableStateOf(10)
    // custom bubble colors as ARGB Int; 0 = use the style's default color
    val sentColor = mutableStateOf(0)   // right side (my messages)
    val recvColor = mutableStateOf(0)   // left side (their messages)
    // show the "Forwarded" tag on forwarded messages (UI-only, default on)
    val showForwardTag = mutableStateOf(true)
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

    // swatch palette for the sent/received color pickers (0 = default/auto)
    val bubblePalette: List<Int> = listOf(
        0,                       // Default (follow style)
        0xFF0A84FF.toInt(),      // iOS blue
        0xFF128C7E.toInt(),      // WA teal
        0xFF005C4B.toInt(),      // WA dark green
        0xFF7C4DFF.toInt(),      // purple
        0xFFE91E63.toInt(),      // pink
        0xFFEF5350.toInt(),      // red
        0xFFFF9800.toInt(),      // orange
        0xFF43A047.toInt(),      // green
        0xFF00ACC1.toInt(),      // cyan
        0xFF546E7A.toInt(),      // slate
        0xFF2C2C2E.toInt(),      // dark grey
        0xFFE9E9EB.toInt(),      // light grey
        0xFFFFFFFF.toInt(),      // white
    )

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("chatstyle", Context.MODE_PRIVATE)
        bubbleStyle.value = prefs!!.getString("bubble", "ios") ?: "ios"
        headerBlurDp.value = prefs!!.getInt("headerBlur", 10)
        sentColor.value = prefs!!.getInt("sentColor", 0)
        recvColor.value = prefs!!.getInt("recvColor", 0)
        showForwardTag.value = prefs!!.getBoolean("showForwardTag", true)
    }

    fun setBubble(s: String) { bubbleStyle.value = s; prefs?.edit()?.putString("bubble", s)?.apply() }
    fun setHeaderBlur(dp: Int) { headerBlurDp.value = dp; prefs?.edit()?.putInt("headerBlur", dp)?.apply() }
    fun setSentColor(c: Int) { sentColor.value = c; prefs?.edit()?.putInt("sentColor", c)?.apply() }
    fun setRecvColor(c: Int) { recvColor.value = c; prefs?.edit()?.putInt("recvColor", c)?.apply() }
    fun setShowForwardTag(b: Boolean) { showForwardTag.value = b; prefs?.edit()?.putBoolean("showForwardTag", b)?.apply() }

    fun bubbleLabel(key: String): String = bubbleStyles.firstOrNull { it.first == key }?.second ?: "iOS"

    /** Header container opacity from blur setting: Off = solid, more dp = more translucent frost. */
    fun headerAlpha(): Float = when (headerBlurDp.value) {
        0 -> 1f; 5 -> 0.94f; 10 -> 0.86f; 15 -> 0.80f; 20 -> 0.72f; 30 -> 0.62f; 40 -> 0.5f; else -> 0.86f
    }

    /** Readable text color (black/white) for a given bubble fill. */
    fun textOn(argb: Int): Color {
        val c = Color(argb)
        val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
        return if (lum > 0.6f) Color.Black else Color.White
    }
}
