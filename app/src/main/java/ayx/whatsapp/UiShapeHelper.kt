package ayx.whatsapp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Maps a hardcoded corner size to the active UI style's equivalent.
 *
 * CRITICAL: When "default" (Original WhatsApp Style) is selected,
 * returns the EXACT original corner value to preserve the original UI.
 *
 * Only when a custom style is selected does it map:
 *   Large (20dp+)  → style's card corner
 *   Medium (12-18dp) → style's button corner
 *   Small (<12dp)  → style's chip corner
 *
 * Reads UiStyleStore directly (not a @Composable) so it works from
 * any context. In @Composable callers, reading the state value
 * subscribes to changes and recomposes on style switch.
 */
fun uiShapeFor(originalDp: Int): RoundedCornerShape {
    val styleId = UiStyleStore.uiStyle.value
    // Original style: preserve exact original corners - DO NOT MODIFY
    if (styleId == "default") {
        return RoundedCornerShape(originalDp.dp)
    }
    val style = uiStyleById(styleId)
    return when {
        originalDp >= 20 -> RoundedCornerShape(style.cardCorner)
        originalDp >= 12 -> RoundedCornerShape(style.buttonCorner)
        else -> RoundedCornerShape(style.chipCorner)
    }
}
