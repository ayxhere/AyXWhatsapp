package ayx.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Applies the active UI style's full visual treatment to a surface.
 *
 * @param kind "card", "button", "input", "dialog", or "chip"
 *
 * Each style renders differently:
 * - Neumorphism: dual shadows (light/dark), no elevation, soft corners
 * - Glassmorphism: translucency, subtle border, layered depth
 * - Skeuomorphism: gradient, border, strong shadow, tactile depth
 * - Claymorphism: high elevation, large corners, inflated soft shadow
 * - Default: standard Material treatment
 */
@Composable
fun Modifier.uiSurface(kind: String = "card"): Modifier {
    val style = UiStyleProvider.current
    val corner: Dp = when (kind) {
        "button" -> style.buttonCorner
        "input" -> style.inputCorner
        "dialog" -> style.cardCorner
        "chip" -> style.chipCorner
        else -> style.cardCorner
    }
    val elevation: Dp = when (kind) {
        "button" -> style.buttonElevation
        "dialog" -> style.dialogElevation
        else -> style.cardElevation
    }
    val shape = RoundedCornerShape(corner)

    var mod = this
    // shadow / elevation
    if (elevation > 0.dp || style.useDualShadow) {
        mod = mod.shadow(
            elevation = if (style.useDualShadow) 8.dp else elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = style.shadowIntensity),
            spotColor = Color.Black.copy(alpha = style.shadowIntensity),
        )
    }
    // glass translucency handled by caller via background alpha;
    // border for glass/skeuomorphism
    if (style.borderWidth > 0.dp) {
        mod = mod.border(
            width = style.borderWidth,
            color = Color.White.copy(alpha = style.borderAlpha),
            shape = shape
        )
    }
    mod = mod.clip(shape)
    return mod
}

/**
 * Returns a background brush for the active style.
 * Skeuomorphism gets a subtle vertical gradient; others get solid color.
 */
@Composable
fun uiSurfaceBrush(base: Color): Brush {
    val style = UiStyleProvider.current
    return if (style.useGradient) {
        Brush.verticalGradient(
            colors = listOf(
                base.copy(alpha = 1f),
                base.copy(alpha = 0.85f),
            )
        )
    } else {
        Brush.linearGradient(listOf(base, base))
    }
}

/**
 * Alpha multiplier for glassmorphism translucency.
 * Returns 1f for non-glass styles.
 */
@Composable
fun uiGlassAlpha(): Float {
    val style = UiStyleProvider.current
    return if (style.useGlass) style.glassAlpha else 1f
}
