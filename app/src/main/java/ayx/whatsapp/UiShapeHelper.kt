package ayx.whatsapp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable

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
 * Must be called from a @Composable context.
 */
@Composable
fun uiShapeFor(originalDp: Int): RoundedCornerShape {
    val style = UiStyleProvider.current
    return when {
        originalDp >= 20 -> RoundedCornerShape(style.cardCorner)
        originalDp >= 12 -> RoundedCornerShape(style.buttonCorner)
        else -> RoundedCornerShape(style.chipCorner)
    }
}
