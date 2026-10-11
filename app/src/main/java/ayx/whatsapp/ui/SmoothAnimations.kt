package ayx.whatsapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

// 144fps smooth page animations - visible movement, no lag
// Only uses GPU-accelerated properties (alpha, scale, translation)

// Slide up + fade in (for page content)
private val SlideUpFade = slideInVertically(
    animationSpec = tween(300, easing = FastOutSlowInEasing),
    initialOffsetY = { it / 3 }
) + fadeIn(animationSpec = tween(250))

// Slide in from right (for chat/settings pages)
private val SlideRightFade = slideInHorizontally(
    animationSpec = tween(300, easing = FastOutSlowInEasing),
    initialOffsetX = { it / 4 }
) + fadeIn(animationSpec = tween(250))

// Scale + fade (for dialogs, popups)
private val ScaleFade = scaleIn(
    animationSpec = tween(250, easing = FastOutSlowInEasing),
    initialScale = 0.9f
) + fadeIn(animationSpec = tween(200))

/**
 * Wraps page content with smooth slide-up entrance animation.
 * Usage: AnimatedPage { YourScreenContent() }
 */
@Composable
fun AnimatedPage(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50) // Small delay for smooth cascade
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = SlideUpFade,
    ) {
        Box { content() }
    }
}

/**
 * Wraps page content with slide-from-right entrance (for drill-in navigation).
 * Usage: AnimatedPageSlide { YourScreenContent() }
 */
@Composable
fun AnimatedPageSlide(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = SlideRightFade,
    ) {
        Box { content() }
    }
}

/**
 * Staggered list item animation - items slide up one after another.
 * Usage: AnimatedListItem(index = 0) { YourItemContent() }
 */
@Composable
fun AnimatedListItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index * 40).coerceAtMost(400).toLong()) // Stagger by 40ms
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = SlideUpFade,
    ) {
        Box { content() }
    }
}
