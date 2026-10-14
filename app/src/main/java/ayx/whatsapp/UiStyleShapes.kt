package ayx.whatsapp

import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Converts the active [UiStyle] into Material3 [Shapes].
 *
 * Apply this to the app's MaterialTheme so the selected UI style
 * actually changes the interface (not just the selection screen):
 *
 *   MaterialTheme(
 *       colorScheme = ...,
 *       shapes = rememberUiShapes(),
 *   ) { ... }
 */
@Composable
fun rememberUiShapes(): Shapes {
    val style = UiStyleProvider.current
    return Shapes(
        extraSmall = RoundedCornerShape(style.chipCorner),
        small = RoundedCornerShape(style.buttonCorner),
        medium = RoundedCornerShape(style.cardCorner),
        large = RoundedCornerShape(style.cardCorner),
        extraLarge = RoundedCornerShape(style.cardCorner),
    )
}
