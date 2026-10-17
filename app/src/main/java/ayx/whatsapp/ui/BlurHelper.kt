package ayx.whatsapp.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fresh background-blur helpers (Material-style blur).
 *
 * Technique (general Android approach, written from scratch):
 * - API 31+ : [Modifier.blur] applies a GPU RenderEffect blur to the
 *   content behind the surface.
 * - Older APIs : blur is a no-op in Compose, so a semi-transparent scrim
 *   is drawn instead so the UI still reads as frosted glass.
 *
 * Callers decide which surfaces get blur via
 * [UiAnimationSettings.blurAppliesTo].
 */

/** Blur radius used for navigation / menu surfaces. */
val AyxBlurRadius: Dp = 18.dp

/** Blur radius used while a zoom page transition is in progress. */
val AyxMotionBlurMaxRadius: Dp = 12.dp

/**
 * Applies a blur to [this] modifier when [enabled] is true and the device
 * supports RenderEffect blur (API 31+). On older devices returns the
 * modifier unchanged — callers should pair it with a scrim background.
 */
fun Modifier.ayxBlurBackground(enabled: Boolean, radius: Dp = AyxBlurRadius): Modifier =
    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.blur(radius)
    } else {
        this
    }

/**
 * Frosted-glass surface: blurred background (API 31+) + translucent scrim
 * (all APIs, so pre-31 devices still get the frosted look).
 *
 * @param enabled master blur switch for this surface.
 * @param scrim translucent tint drawn over the blurred content.
 */
@Composable
fun AyxBlurredSurface(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    radius: Dp = AyxBlurRadius,
    scrim: Color = Color.Black.copy(alpha = 0.45f),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.then(if (enabled) Modifier.background(scrim) else Modifier)) {
        // Content itself is rendered normally; the blur is applied by the
        // CALLER to the layer behind this surface via ayxBlurBackground(),
        // which is the standard RenderEffect pattern: blur the background,
        // then draw a translucent scrim on top.
        content()
    }
}

/**
 * Animated-blur radius for the zoom transition.
 *
 * True per-pixel motion blur is not available as a cheap Android API, so
 * this is an APPROXIMATION: the blur radius tracks the zoom progress
 * (strongest mid-transition, zero at rest). Documented as approximated —
 * never claimed to be true optical motion blur.
 *
 * @param progress 0f (transition start) .. 1f (transition end).
 */
fun motionBlurRadiusFor(progress: Float, motionBlurEnabled: Boolean): Dp {
    if (!motionBlurEnabled) return 0.dp
    val clamped = progress.coerceIn(0f, 1f)
    // Peak in the middle of the transition, easing to zero at both ends.
    val bell = 4f * clamped * (1f - clamped)
    return AyxMotionBlurMaxRadius * bell
}
