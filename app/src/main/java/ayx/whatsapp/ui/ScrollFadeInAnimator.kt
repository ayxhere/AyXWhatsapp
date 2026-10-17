package ayx.whatsapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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

/**
 * Fresh scroll fade-in for list items.
 *
 * General technique: each item fades alpha 0 -> 1 and slides
 * translationY +16dp -> 0 over ~250ms with FastOutSlowIn easing.
 * The animation runs ONLY on first appearance for a given [key] —
 * recompositions and scroll-back do not re-animate, so lists never
 * flicker while scrolling.
 *
 * Usage inside a LazyColumn item:
 * ```
 * AyxFadeInItem(key = chat.jid, index = index) { ChatRow(chat) }
 * ```
 * When [UiAnimationSettings.scrollAnimationEnabled] is false, callers
 * should render content directly without this wrapper.
 */
@Composable
fun AyxFadeInItem(
    key: Any,
    index: Int = 0,
    content: @Composable () -> Unit,
) {
    // Per-key "already shown" flag survives recomposition but is scoped to
    // this item's composition, so each list item animates once.
    val seen = remember(key) { mutableStateOf(false) }
    var visible by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!seen.value) {
            // Small stagger (capped) so items cascade in as the list appears.
            delay((index * 25).coerceAtMost(200).toLong())
            seen.value = true
            visible = true
        } else {
            visible = true
        }
    }
    if (!seen.value && !visible) {
        // Not yet shown: keep layout space but render nothing until the
        // stagger delay elapses. AnimatedVisibility handles the entrance.
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(250, easing = FastOutSlowInEasing),
            initialOffsetY = { 32 },
        ) + fadeIn(animationSpec = tween(250)),
    ) {
        Box { content() }
    }
}
