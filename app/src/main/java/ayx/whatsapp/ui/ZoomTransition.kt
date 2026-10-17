package ayx.whatsapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.delay

/**
 * Fresh zoom page transitions (Zoom in/out style).
 *
 * General technique: page scales 0.95 -> 1.0 over ~300ms with
 * FastOutSlowIn easing, combined with a fade. Enter/exit pairs are
 * provided for AnimatedContent / AnimatedVisibility, plus a simple
 * [AyxZoomPage] wrapper for full-screen content.
 *
 * Motion-blur-during-zoom is APPROXIMATED: a RenderEffect blur whose
 * radius peaks mid-transition (see [motionBlurRadiusFor]). This is a
 * visual approximation, not true optical motion blur.
 */

private const val ZOOM_DURATION_MS = 300
private const val ZOOM_FADE_MS = 250
private const val ZOOM_START_SCALE = 0.95f

/** Enter transition for zoom-style navigation. */
val AyxZoomEnter: EnterTransition = scaleIn(
    animationSpec = tween(ZOOM_DURATION_MS, easing = FastOutSlowInEasing),
    initialScale = ZOOM_START_SCALE,
) + fadeIn(animationSpec = tween(ZOOM_FADE_MS))

/** Exit transition for zoom-style navigation. */
val AyxZoomExit: ExitTransition = scaleOut(
    animationSpec = tween(ZOOM_DURATION_MS, easing = FastOutSlowInEasing),
    targetScale = ZOOM_START_SCALE,
) + fadeOut(animationSpec = tween(ZOOM_FADE_MS))

/**
 * Wraps page content with a zoom-in entrance animation.
 *
 * @param motionBlur when true, an animated blur radius is applied that
 * peaks mid-transition (approximation — see [motionBlurRadiusFor]).
 */
@Composable
fun AyxZoomPage(
    motionBlur: Boolean = false,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    // 0f -> 1f progress drives the approximated motion-blur radius.
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(ZOOM_DURATION_MS, easing = FastOutSlowInEasing),
        label = "ayxZoomProgress",
    )
    LaunchedEffect(Unit) {
        delay(30)
        visible = true
    }
    AnimatedVisibility(visible = visible, enter = AyxZoomEnter) {
        Box(
            Modifier
                .graphicsLayer { scaleX = 1f; scaleY = 1f }
                .then(
                    if (motionBlur) Modifier.blur(motionBlurRadiusFor(progress, true))
                    else Modifier,
                ),
        ) {
            content()
        }
    }
}
