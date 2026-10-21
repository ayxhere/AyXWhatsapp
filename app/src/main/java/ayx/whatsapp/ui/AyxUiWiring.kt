package ayx.whatsapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import kotlinx.coroutines.delay

/**
 * Wiring for the Settings → User Interface options.
 *
 * BUG (fixed here): the settings screen + helpers existed, but NOTHING read
 * them — prefs changed, the UI never did. Every screen switch, list item
 * and glass surface below reads [UiAnimationSettings] live, so toggling a
 * setting visibly changes the UI immediately.
 *
 * - Page transitions: [AyxAnimatedScreen] wraps the main content and picks
 *   its transition from the saved [AnimationStyle] (Zoom / Fade / Slide /
 *   None). Zoom = scale 0.95 → 1.0 over 300ms + fade, FastOutSlowIn.
 * - Motion blur: APPROXIMATION — a RenderEffect blur whose radius is at max
 *   when the transition starts and decays to 0 over ~300ms. This is a
 *   visual approximation, not true optical motion blur.
 * - Frosted glass: [ayxGlassAlpha] lowers a glass surface's alpha when the
 *   Material Blur toggle applies to that element, so the frosted look
 *   appears/disappears with the toggle.
 * - Scroll fade-in: [AyxFadeInListItem] renders content directly when
 *   scroll animation is OFF, else delegates to [AyxFadeInItem].
 */

/** Glass-surface alpha: lower (more frosted) when blur applies to [element]. */
fun ayxGlassAlpha(baseAlpha: Float, element: BlurElement): Float =
    if (UiAnimationSettings.blurAppliesTo(element)) (baseAlpha - 0.30f).coerceAtLeast(0.45f)
    else baseAlpha

/** Menu container alpha: more translucent when blur applies to Menus. */
fun ayxMenuAlpha(baseAlpha: Float = 1f): Float =
    if (UiAnimationSettings.blurAppliesTo(BlurElement.MENUS)) (baseAlpha - 0.22f).coerceAtLeast(0.6f)
    else baseAlpha

/** Dialog container alpha: more translucent when blur applies to Dialogs. */
fun ayxDialogAlpha(baseAlpha: Float = 1f): Float =
    if (UiAnimationSettings.blurAppliesTo(BlurElement.DIALOGS)) (baseAlpha - 0.18f).coerceAtLeast(0.65f)
    else baseAlpha

/**
 * Wraps a whole screen switch with the saved page-transition animation.
 *
 * @param screenKey changes whenever the visible screen changes
 * (e.g. "chats|<openChat>|<settingsPage>") so every navigation animates.
 * @param isChat true for full-screen chat content: chat switches ALWAYS
 * use fade-only (no scale, no motion blur). AnimatedContent lays old +
 * new content out together during a scale transition, so a Zoom switch
 * between two chats showed both composers/bubbles overlapped and the
 * input field's old text ghosting over the new chat ("garbled leftover
 * text"). Fade-only keeps exactly one chat visible at a time.
 */
/**
 * State-driven variant: the content lambda receives the state OF ITS OWN
 * animation slot, not whatever the caller's live variable holds right now.
 *
 * This matters because AnimatedContent composes the outgoing AND incoming
 * slots at the same time during a transition. A caller whose content reads
 * a captured live variable (e.g. `storyView`) renders the SAME new content
 * in both slots — for the status viewer that meant two viewers (two video
 * players) stacked during every open, the hidden one ghosting/playing
 * behind the visible one. Callers whose content depends on per-state data
 * must use this variant; purely static content can keep the simple one.
 */
@Composable
fun <T> AyxAnimatedScreen(
    targetState: T,
    isChat: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val style = UiAnimationSettings.animationStyle.value
    val motionBlur = UiAnimationSettings.motionBlurEnabled.value && !isChat
    // Transient motion-blur: max radius at switch, decays to 0 over ~300ms.
    // Skipped for chat screens (see isChat) and for non-Zoom styles.
    var blurActive by remember(targetState) { mutableStateOf(motionBlur && style == AnimationStyle.ZOOM) }
    LaunchedEffect(targetState) {
        if (motionBlur && style == AnimationStyle.ZOOM) {
            blurActive = true
            delay(300)
            blurActive = false
        } else {
            blurActive = false
        }
    }
    AnimatedContent(
        targetState = targetState,
        transitionSpec = { ayxTransitionFor(if (isChat) AnimationStyle.FADE else style) },
        label = "ayxScreen",
    ) { slotState ->
        Box(
            Modifier.then(
                if (blurActive) Modifier.blur(AyxMotionBlurMaxRadius) else Modifier,
            ),
        ) {
            content(slotState)
        }
    }
}

@Composable
fun AyxAnimatedScreen(
    screenKey: Any,
    isChat: Boolean = false,
    content: @Composable () -> Unit,
) {
    AyxAnimatedScreen(targetState = screenKey, isChat = isChat) { content() }
}

private fun ayxTransitionFor(style: AnimationStyle) =
    when (style) {
        AnimationStyle.ZOOM -> (
            scaleIn(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                initialScale = 0.95f,
            ) + fadeIn(animationSpec = tween(250))
            ) togetherWith (
            scaleOut(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                targetScale = 0.95f,
            ) + fadeOut(animationSpec = tween(250))
            )
        AnimationStyle.FADE -> fadeIn(animationSpec = tween(250)) togetherWith
            fadeOut(animationSpec = tween(250))
        AnimationStyle.SLIDE -> (
            slideInHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            ) { it / 4 } + fadeIn(animationSpec = tween(250))
            ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            ) { -it / 4 } + fadeOut(animationSpec = tween(250))
            )
        AnimationStyle.SLIDE_UP -> (
            slideInVertically(
                animationSpec = tween(320, easing = FastOutSlowInEasing),
            ) { it / 3 } + fadeIn(animationSpec = tween(250))
            ) togetherWith (
            slideOutVertically(
                animationSpec = tween(320, easing = FastOutSlowInEasing),
            ) { it / 3 } + fadeOut(animationSpec = tween(250))
            )
        AnimationStyle.SCALE -> (
            scaleIn(
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                initialScale = 0.85f,
            ) + fadeIn(animationSpec = tween(250))
            ) togetherWith (
            scaleOut(
                animationSpec = tween(320, easing = FastOutSlowInEasing),
                targetScale = 1.08f,
            ) + fadeOut(animationSpec = tween(250))
            )
        AnimationStyle.FADE_SLIDE -> (
            slideInVertically(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
            ) { it / 5 } + fadeIn(animationSpec = tween(280))
            ) togetherWith (
            fadeOut(animationSpec = tween(280)) +
                slideOutVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) { -it / 5 }
            )
        AnimationStyle.NONE -> EnterTransition.None togetherWith ExitTransition.None
    }

/**
 * List-item wrapper: fade-in + slide-up on first appearance when scroll
 * animation is ON, plain content when OFF. Use for chat-list rows and
 * message bubbles so the Scroll Animation toggle visibly changes lists.
 */
@Composable
fun AyxFadeInListItem(
    key: Any,
    index: Int = 0,
    delayMs: Int = 0,
    content: @Composable () -> Unit,
) {
    if (UiAnimationSettings.scrollAnimationEnabled.value) {
        AyxFadeInItem(key = key, index = index, delayMs = delayMs, content = content)
    } else {
        content()
    }
}
