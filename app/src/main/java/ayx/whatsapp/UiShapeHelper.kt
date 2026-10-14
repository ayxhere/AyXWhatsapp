package ayx.whatsapp

import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Maps a hardcoded corner size to the active UI style's equivalent.
 *
 * Call this instead of `RoundedCornerShape(N.dp)` so the selected UI
 * style actually changes the interface:
 *
 *   Large (20dp+)  → style's card corner
 *   Medium (12-18dp) → style's button corner
 *   Small (<12dp)  → style's chip corner
 *
 * Reads UiStyleStore directly (not a @Composable) so it works from
 * any context. In @Composable callers, reading the state value
 * subscribes to changes and recomposes on style switch.
 */
fun uiShapeFor(originalDp: Int): RoundedCornerShape {
    val style = uiStyleById(UiStyleStore.uiStyle.value)
    return when {
        originalDp >= 20 -> RoundedCornerShape(style.cardCorner)
        originalDp >= 12 -> RoundedCornerShape(style.buttonCorner)
        else -> RoundedCornerShape(style.chipCorner)
    }
}
