package ayx.whatsapp

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Theme-aware reusable components. All read [AyxThemeProvider.current]
 * so they restyle automatically when the appearance changes.
 */

@Composable
fun AyxCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val theme = AyxThemeProvider.current
    val shape = RoundedCornerShape(theme.shapes.cardCorner)
    val cardMod = modifier
        .shadow(
            elevation = theme.elevation.card,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = theme.elevation.shadowAlpha),
            spotColor = Color.Black.copy(alpha = theme.elevation.shadowAlpha),
        )
        .clip(shape)
        .background(
            if (theme.glass.enabled)
                theme.colors.cardBackground.copy(alpha = theme.glass.surfaceAlpha)
            else theme.colors.cardBackground
        )
        .then(
            if (theme.glass.enabled)
                Modifier.border(
                    1.dp,
                    theme.colors.border.copy(alpha = theme.glass.borderAlpha),
                    shape
                )
            else Modifier
        )
        .then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        )
    Box(modifier = cardMod) { content() }
}

@Composable
fun AyxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val theme = AyxThemeProvider.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val elevation by animateDpAsState(
        targetValue = if (pressed) 0.dp else theme.elevation.button,
        animationSpec = tween(150), label = "ayx-btn-elev"
    )
    val shape = RoundedCornerShape(theme.shapes.cornerMedium)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.shadow(elevation, shape),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = theme.colors.primary,
            contentColor = theme.colors.onPrimary,
            disabledContainerColor = theme.colors.surfaceVariant,
            disabledContentColor = theme.colors.textDisabled,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        interactionSource = interaction,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AyxSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val theme = AyxThemeProvider.current
    Box(
        modifier = modifier.background(theme.colors.background)
    ) { content() }
}

/** Gradient brush helper for skeuomorphic / premium surfaces. */
@Composable
fun ayxRaisedBrush(): Brush {
    val theme = AyxThemeProvider.current
    return Brush.verticalGradient(
        listOf(theme.colors.surfaceRaised, theme.colors.surface)
    )
}

@Composable
fun ayxInsetBrush(): Brush {
    val theme = AyxThemeProvider.current
    return Brush.verticalGradient(
        listOf(theme.colors.surfaceInset, theme.colors.surfaceVariant)
    )
}
