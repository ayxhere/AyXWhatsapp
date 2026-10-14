package ayx.whatsapp

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The part that was missing: this is where the selected UI style (Neumorphism / Glassmorphism /
 * Skeuomorphism / Claymorphism) is actually DRAWN. Corner radii alone (what the app did before)
 * differ by only a few dp, so nothing looked different.
 *
 * Default style = untouched Material look.
 */
@Composable
fun Modifier.ayxStyled(shape: Shape, base: Color, elevation: Dp = 0.dp): Modifier {
    val s = LocalUiStyle.current
    if (s.isDefault) return this.background(base, shape)
    val dark = base.luminance() < 0.5f
    val lift = if (elevation > s.cardElevation) elevation else s.cardElevation
    return when {
        // Neumorphism: soft extruded face with a light + a dark shadow
        s.useDualShadow -> {
            val face = lerp(base.copy(alpha = 1f), if (dark) Color.White else Color.Black, if (dark) 0.06f else 0.03f)
            this.neuShadow(shape, face, dark)
                .clip(shape)
                .background(face)
                .border(0.6.dp, Color.White.copy(alpha = if (dark) 0.06f else 0.55f), shape)
        }
        // Glassmorphism: translucent frosted pane, bright edge, diagonal sheen
        s.useGlass -> {
            val face = lerp(base, Color.White, 0.07f).copy(alpha = (base.alpha * s.glassAlpha).coerceIn(0.25f, 1f))
            this.clip(shape)
                .background(face)
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.White.copy(alpha = 0.04f))), shape)
                .border(BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.50f), Color.White.copy(alpha = 0.06f)))), shape)
        }
        // Skeuomorphism: lit-from-above gradient, bevelled edge, hard drop shadow
        s.useGradient -> {
            this.shadow(lift, shape, ambientColor = Color.Black.copy(alpha = s.shadowIntensity), spotColor = Color.Black.copy(alpha = s.shadowIntensity))
                .clip(shape)
                .background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.12f), base, lerp(base, Color.Black, 0.22f))))
                .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.50f)))), shape)
        }
        // Claymorphism: puffy, inflated, soft highlight + shade
        else -> {
            this.shadow(if (lift > 6.dp) lift else 6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(shape)
                .background(lerp(base, Color.White, 0.05f))
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.22f))), shape)
                .border(1.5.dp, Color.White.copy(alpha = 0.12f), shape)
        }
    }
}

private fun Modifier.neuShadow(shape: Shape, face: Color, dark: Boolean): Modifier {
    // setShadowLayer on a hardware canvas needs API 28+
    if (Build.VERSION.SDK_INT < 28) {
        return this.shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
    }
    val light = if (dark) Color.White.copy(alpha = 0.10f) else Color.White
    val deep = if (dark) Color.Black.copy(alpha = 0.90f) else Color(0x44000000)
    return this.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val r = if (outline is Outline.Rounded) {
            val rr = outline.roundRect
            maxOf(maxOf(rr.topLeftCornerRadius.x, rr.topRightCornerRadius.x), maxOf(rr.bottomLeftCornerRadius.x, rr.bottomRightCornerRadius.x))
        } else 0f
        val blur = 10.dp.toPx()
        val off = 5.dp.toPx()
        drawIntoCanvas { canvas ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            p.color = face.toArgb()
            p.setShadowLayer(blur, -off, -off, light.toArgb())
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
            p.setShadowLayer(blur, off, off, deep.toArgb())
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
        }
    }
}

/** Drop-in for Material [Surface] that also renders the active UI style. */
@Composable
fun StyledSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val styled = !LocalUiStyle.current.isDefault
    val m = if (styled) modifier.ayxStyled(shape, color, shadowElevation) else modifier
    val c = if (styled) Color.Transparent else color
    val te = if (styled) 0.dp else tonalElevation
    val se = if (styled) 0.dp else shadowElevation
    val b = if (styled) null else border
    if (onClick != null) {
        Surface(onClick = onClick, modifier = m, shape = shape, color = c, contentColor = contentColor,
            tonalElevation = te, shadowElevation = se, border = b, content = content)
    } else {
        Surface(modifier = m, shape = shape, color = c, contentColor = contentColor,
            tonalElevation = te, shadowElevation = se, border = b, content = content)
    }
}
