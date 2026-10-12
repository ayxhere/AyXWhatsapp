package ayx.whatsapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

// Premium animation specs - matches reference video feel
// All GPU-accelerated (alpha, scale, translation only) for 60-120fps

private const val PAGE_DURATION = 280
private const val FADE_DURATION = 200
private const val PRESS_DURATION = 120

// Page enter: slide from right + fade (like reference video settings navigation)
val PageEnter = slideInHorizontally(
    animationSpec = tween(PAGE_DURATION, easing = FastOutSlowInEasing),
    initialOffsetX = { it / 5 }
) + fadeIn(animationSpec = tween(FADE_DURATION))

val PageExit = slideOutHorizontally(
    animationSpec = tween(PAGE_DURATION, easing = FastOutSlowInEasing),
    targetOffsetX = { -it / 5 }
) + fadeOut(animationSpec = tween(FADE_DURATION))

// Content enter: subtle slide up + fade
val ContentEnter = slideInVertically(
    animationSpec = tween(260, easing = FastOutSlowInEasing),
    initialOffsetY = { it / 6 }
) + fadeIn(animationSpec = tween(FADE_DURATION))

// Dialog/popup: scale + fade
val DialogEnter = scaleIn(
    animationSpec = tween(220, easing = FastOutSlowInEasing),
    initialScale = 0.92f
) + fadeIn(animationSpec = tween(180))

/**
 * Wraps screen content with premium page transition (slide + fade).
 * Drop-in replacement - does not modify the content itself.
 */
@Composable
fun PremiumPage(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(30)
        visible = true
    }
    AnimatedVisibility(visible = visible, enter = PageEnter, exit = PageExit) {
        Box { content() }
    }
}

/**
 * Wraps content with subtle slide-up + fade (for lists, cards).
 */
@Composable
fun PremiumContent(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(40)
        visible = true
    }
    AnimatedVisibility(visible = visible, enter = ContentEnter) {
        Box { content() }
    }
}

/**
 * Staggered list item - slides up with delay based on index.
 * Use for settings rows, chat list items.
 */
@Composable
fun PremiumListItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((30 + index * 35).coerceAtMost(350).toLong())
        visible = true
    }
    AnimatedVisibility(visible = visible, enter = ContentEnter) {
        Box { content() }
    }
}

/**
 * Pressable wrapper - subtle scale on press (like reference video buttons).
 * Wraps existing clickable content without changing its appearance.
 */
@Composable
fun PremiumPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(
            PRESS_DURATION,
            easing = FastOutSlowInEasing
        ),
        label = "pressScale"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        content()
    }
}

/**
 * Toggle switch wrapper - smooth thumb slide animation.
 * The Switch composable itself handles the animation, this ensures
 * consistent timing across the app.
 */
object PremiumToggle {
    // Use these specs for custom toggle implementations
    val ThumbSpec = tween<Float>(200, easing = FastOutSlowInEasing)
    val TrackSpec = tween<Float>(200)
}
