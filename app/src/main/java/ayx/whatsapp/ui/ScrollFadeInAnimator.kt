package ayx.whatsapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import java.util.Collections

/**
 * Fresh scroll fade-in for list items.
 *
 * General technique: each item fades alpha 0 -> 1 and slides
 * translationY +16dp -> 0 over ~250ms with FastOutSlowIn easing.
 * The animation runs ONLY on first appearance for a given [key].
 *
 * BUG FIX (video: bubbles overlapping + lists flickering):
 * the old version used AnimatedVisibility (slideInVertically + fadeIn).
 * AnimatedVisibility animates LAYOUT SIZE: an item starts at 0 height and
 * expands while neighbours shift — inside a reverseLayout LazyColumn with
 * animateItem() that made bubbles overlap, jump and flicker, and every
 * poll-driven list rebuild re-triggered it. It also lost its "seen" state
 * when LazyColumn recycled an off-screen composition, so scroll-back
 * re-animated rows (flicker while scrolling).
 *
 * New version animates ONLY graphicsLayer alpha/translationY — the item
 * occupies its full layout size from frame 1, so neighbours NEVER move and
 * bubbles can NEVER overlap because of the animation. "Seen" keys live in
 * a process-wide set (not per-composition remember), so scroll-back,
 * recomposition and poll refreshes never re-animate an existing row.
 * No stagger delay: a delayed invisible-but-laid-out row reads as a
 * row that appears/disappears while scrolling.
 */
private object AyxSeenItems {
    private val keys = Collections.synchronizedSet(mutableSetOf<Any>())
    fun has(key: Any): Boolean = keys.contains(key)
    fun mark(key: Any) {
        if (keys.size > 1000) keys.clear()
        keys.add(key)
    }
}

@Composable
fun AyxFadeInItem(
    key: Any,
    index: Int = 0,
    // Entrance-cascade stagger (ms): home lists pass index * 35 (capped)
    // during the first ~900ms after mount so rows cascade in visibly.
    // Must be 0 for rows composed later (poll/scroll) — a delayed
    // invisible-but-laid-out row reads as a row that appears/disappears
    // while scrolling. Delay only shifts the start; the animation itself
    // is still graphicsLayer-only, never layout size.
    delayMs: Int = 0,
    content: @Composable () -> Unit,
) {
    // First appearance only, process-wide: survives LazyColumn recycling,
    // recomposition and poll refreshes. Seen items start fully visible
    // (no animation); new items start invisible and animate to visible.
    var targetVisible by remember(key) { mutableStateOf(AyxSeenItems.has(key)) }
    LaunchedEffect(key) {
        if (!AyxSeenItems.has(key)) {
            AyxSeenItems.mark(key)
            targetVisible = true
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (targetVisible) 1f else 0f,
        animationSpec = tween(250, delayMillis = delayMs, easing = FastOutSlowInEasing),
        label = "ayxFadeInAlpha",
    )
    val slideY by animateFloatAsState(
        targetValue = if (targetVisible) 0f else 32f,
        animationSpec = tween(250, delayMillis = delayMs, easing = FastOutSlowInEasing),
        label = "ayxFadeInSlide",
    )
    // graphicsLayer changes ONLY how the item is drawn, never its layout
    // size — neighbours stay put, so no overlap / jump / flicker.
    Box(Modifier.graphicsLayer { this.alpha = alpha; translationY = slideY }) {
        content()
    }
}
