package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Message Motion — entrance animations for chat message bubbles, written
 * fresh for this app: a message animates the first time it appears within
 * one chat-open session, with a per-direction effect (incoming vs my
 * messages), a bottom-up "wave" when a chat opens, and a separate gentler
 * effect for bubbles that first appear while scrolling. Seen state is
 * scoped to ONE chat-open session — within a session, recycling, poll
 * refreshes and scroll-backs never re-animate a bubble; leaving the chat
 * ends the session, so reopening replays the open-cascade and new messages
 * always animate on arrival. Only graphicsLayer transforms are animated
 * (never layout size), so the list cannot jump, overlap or flicker because
 * of an animation.
 *
 * Everything is driven by [MessageMotionSettings] (SharedPreferences
 * "ayx_message_motion") and edited from
 * Settings → Appearance → Message Motion. Master off = stock bubbles.
 * Voice messages are excluded — they keep their usual behavior.
 */
object MessageMotionSettings {
    const val OFF = 0
    const val POP = 1
    const val SMOOTH = 2
    const val SLIDE = 3
    const val FADE = 4

    val enabled = mutableStateOf(true)

    // incoming
    val inStyle = mutableStateOf(POP)
    val inSpeed = mutableStateOf(1)        // index into SPEED_LABELS

    // my messages
    val outStyle = mutableStateOf(POP)
    val outSpeed = mutableStateOf(1)

    // chat opening
    val cascade = mutableStateOf(true)

    // scrolling
    val scrollStyle = mutableStateOf(SMOOTH)
    val scrollSpeed = mutableStateOf(0)

    val STYLE_LABELS = listOf("Off", "Pop (spring)", "Smooth", "Slide", "Fade")
    val SPEED_LABELS = listOf("Fast", "Normal", "Slow")
    private val SPEED_FACTORS = listOf(0.75f, 1.0f, 1.35f)

    fun factor(speedIdx: Int): Float = SPEED_FACTORS[speedIdx.coerceIn(0, SPEED_FACTORS.lastIndex)]

    /** Alpha ramp duration (ms, before the speed factor) per style. */
    fun alphaDurationMs(style: Int): Int = when (style) {
        POP -> 160
        SMOOTH -> 240
        SLIDE -> 200
        else -> 260
    }

    /** Scale a bubble starts from, per style (1f = no scale track). */
    fun startScale(style: Int): Float = when (style) {
        POP -> 0.55f
        SMOOTH -> 0.92f
        else -> 1f
    }

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_message_motion", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", true)
        inStyle.value = p.getInt("in_style", POP)
        inSpeed.value = p.getInt("in_speed", 1)
        outStyle.value = p.getInt("out_style", POP)
        outSpeed.value = p.getInt("out_speed", 1)
        cascade.value = p.getBoolean("cascade", true)
        scrollStyle.value = p.getInt("scroll_style", SMOOTH)
        scrollSpeed.value = p.getInt("scroll_speed", 0)
        // A stored style outside the known range (older/newer build) falls
        // back to Fade instead of breaking the settings page or the list.
        if (inStyle.value !in OFF..FADE) inStyle.value = FADE
        if (outStyle.value !in OFF..FADE) outStyle.value = FADE
        if (scrollStyle.value !in OFF..FADE) scrollStyle.value = FADE
        inSpeed.value = inSpeed.value.coerceIn(0, SPEED_LABELS.lastIndex)
        outSpeed.value = outSpeed.value.coerceIn(0, SPEED_LABELS.lastIndex)
        scrollSpeed.value = scrollSpeed.value.coerceIn(0, SPEED_LABELS.lastIndex)
    }

    private fun putB(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }
    private fun putI(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }

    fun setEnabled(v: Boolean) { enabled.value = v; putB("enabled", v) }
    fun setInStyle(v: Int) { inStyle.value = v; putI("in_style", v) }
    fun setInSpeed(v: Int) { inSpeed.value = v; putI("in_speed", v) }
    fun setOutStyle(v: Int) { outStyle.value = v; putI("out_style", v) }
    fun setOutSpeed(v: Int) { outSpeed.value = v; putI("out_speed", v) }
    fun setCascade(v: Boolean) { cascade.value = v; putB("cascade", v) }
    fun setScrollStyle(v: Int) { scrollStyle.value = v; putI("scroll_style", v) }
    fun setScrollSpeed(v: Int) { scrollSpeed.value = v; putI("scroll_speed", v) }
}

/** Ease-out quint — the "settle" curve for the Smooth and Slide tracks. */
private val MotionEaseOutQuint = Easing { t ->
    val u = 1f - t
    1f - u * u * u * u * u
}

/**
 * Decides, once per message per chat-open session, whether and how it
 * animates. "Seen" keys are scoped to ONE chat-open session (per chat, not
 * process-wide and not per-composition): within a session, LazyColumn
 * recycling, poll refreshes and scroll-backs never re-animate a bubble;
 * leaving the chat ends the session, so reopening replays the open-cascade
 * and new messages always animate on arrival. Chat-open state and live
 * scroll state are fed in by the chat screen.
 */
object MessageMotionTracker {
    class Plan(val style: Int, val factor: Float, val delayMs: Int) {
        val animated: Boolean get() = style != MessageMotionSettings.OFF
    }

    private val none = Plan(MessageMotionSettings.OFF, 1f, 0)

    private const val CASCADE_LIMIT = 12
    private const val CASCADE_STEP_MS = 35
    private const val CASCADE_WINDOW_MS = 1500L
    private const val ECHO_GUARD_MS = 20_000L

    private val seenByChat = ConcurrentHashMap<String, MutableSet<String>>()
    private val sessionIds = ConcurrentHashMap<String, Int>()
    private val openedAt = ConcurrentHashMap<String, Long>()
    private val cascadeSlots = ConcurrentHashMap<String, AtomicInteger>()
    private val scrollingChats = Collections.synchronizedSet(mutableSetOf<String>())
    // chat|textHash -> when an outgoing bubble with that text last animated.
    // The gateway echo of a just-sent message arrives under a NEW id; without
    // this guard the same bubble would pop twice (optimistic + echo).
    private val recentOut = ConcurrentHashMap<String, Long>()

    /** Monotonic per-chat session counter — bumped on every (re)open so
     *  MessageMotionItem can key its animation state by session. */
    fun sessionId(chat: String): Int = sessionIds[chat] ?: 0

    /** Called by the chat screen when a chat is (re)opened. */
    fun chatOpened(chat: String) {
        openedAt[chat] = System.currentTimeMillis()
        cascadeSlots[chat] = AtomicInteger(0)
        // Fresh session: forget everything this chat has shown so the
        // open-cascade replays and new messages animate again.
        seenByChat[chat] = Collections.synchronizedSet(mutableSetOf())
        sessionIds[chat] = (sessionIds[chat] ?: 0) + 1
    }

    /** Called by the chat screen when it leaves the composition: the next
     *  open starts a fresh motion session for this chat. */
    fun chatClosed(chat: String) {
        seenByChat.remove(chat)
        openedAt.remove(chat)
        cascadeSlots.remove(chat)
        scrollingChats.remove(chat)
    }

    /** Called by the chat screen whenever its list's scroll state changes. */
    fun setScrolling(chat: String, scrolling: Boolean) {
        if (scrolling) scrollingChats.add(chat) else scrollingChats.remove(chat)
    }

    fun planFor(chat: String, key: String, fromMe: Boolean, text: String): Plan {
        val cfg = MessageMotionSettings
        val full = "$chat|$key"
        val seen = seenByChat.computeIfAbsent(chat) { Collections.synchronizedSet(mutableSetOf()) }
        synchronized(seen) {
            if (full in seen) return none
            // Evict the OLDEST ~500 keys past this chat's cap (never
            // clear-all — that would re-animate everything on screen).
            if (seen.size > 3000) {
                val it = seen.iterator()
                var n = 0
                while (it.hasNext() && n < 500) { it.next(); it.remove(); n++ }
            }
            seen.add(full)
        }
        val ownStyle = if (fromMe) cfg.outStyle.value else cfg.inStyle.value
        val ownFactor = cfg.factor(if (fromMe) cfg.outSpeed.value else cfg.inSpeed.value)
        val now = System.currentTimeMillis()

        // Chat just opened: the first CASCADE_LIMIT bubbles compose in
        // newest-first order (bottom-up on screen) and get a stagger.
        val opened = openedAt[chat]
        if (cfg.cascade.value && opened != null && now - opened < CASCADE_WINDOW_MS) {
            val slot = cascadeSlots[chat]?.getAndIncrement() ?: Int.MAX_VALUE
            if (slot < CASCADE_LIMIT) {
                val plan = Plan(ownStyle, ownFactor, slot * CASCADE_STEP_MS)
                if (fromMe && plan.animated) recentOut["$chat|${text.hashCode()}"] = now
                return plan
            }
        }

        // Gateway echo of a message whose optimistic bubble just animated:
        // same text, outgoing, fresh server id — show it settled.
        if (fromMe && !key.startsWith("opt-")) {
            val last = recentOut["$chat|${text.hashCode()}"]
            if (last != null && now - last < ECHO_GUARD_MS) return none
        }

        val plan = if (scrollingChats.contains(chat)) {
            Plan(cfg.scrollStyle.value, cfg.factor(cfg.scrollSpeed.value), 0)
        } else {
            Plan(ownStyle, ownFactor, 0)
        }
        if (fromMe && plan.animated) recentOut["$chat|${text.hashCode()}"] = now
        return plan
    }
}

/**
 * Wraps one chat bubble and plays its entrance motion the first time it
 * appears. When the master switch is off (or the message is a voice note)
 * the content renders untouched.
 */
@Composable
fun MessageMotionItem(
    chatKey: String,
    motionKey: String,
    fromMe: Boolean,
    isAudio: Boolean,
    text: String,
    content: @Composable () -> Unit,
) {
    val cfg = MessageMotionSettings
    if (!cfg.enabled.value || isAudio) { content(); return }
    val session = MessageMotionTracker.sessionId(chatKey)
    val plan = remember(chatKey, motionKey, session) {
        MessageMotionTracker.planFor(chatKey, motionKey, fromMe, text)
    }
    if (!plan.animated) { content(); return }

    val density = LocalDensity.current
    val shiftPx = with(density) { 48.dp.toPx() }
    val scale = remember(chatKey, motionKey, session) { Animatable(cfg.startScale(plan.style)) }
    val alphaA = remember(chatKey, motionKey, session) { Animatable(0f) }
    val shift = remember(chatKey, motionKey, session) {
        Animatable(if (plan.style == MessageMotionSettings.SLIDE) (if (fromMe) shiftPx else -shiftPx) else 0f)
    }
    LaunchedEffect(chatKey, motionKey, session) {
        if (plan.delayMs > 0) delay(plan.delayMs.toLong())
        val f = plan.factor
        launch {
            alphaA.animateTo(
                1f,
                tween(
                    durationMillis = (cfg.alphaDurationMs(plan.style) * f).toInt().coerceAtLeast(1),
                    easing = FastOutSlowInEasing,
                ),
            )
        }
        when (plan.style) {
            MessageMotionSettings.POP -> launch {
                // Damped spring ≈ the pop: quick grow with a soft overshoot.
                scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f / (f * f)))
            }
            MessageMotionSettings.SMOOTH -> launch {
                scale.animateTo(1f, tween(durationMillis = (320 * f).toInt().coerceAtLeast(1), easing = MotionEaseOutQuint))
            }
            MessageMotionSettings.SLIDE -> launch {
                shift.animateTo(0f, tween(durationMillis = (340 * f).toInt().coerceAtLeast(1), easing = MotionEaseOutQuint))
            }
        }
    }
    Box(
        Modifier.graphicsLayer {
            this.alpha = alphaA.value
            scaleX = scale.value
            scaleY = scale.value
            translationX = shift.value
            transformOrigin = when (plan.style) {
                MessageMotionSettings.POP -> TransformOrigin(if (fromMe) 1f else 0f, 0.5f)
                else -> TransformOrigin(0.5f, 0.5f)
            }
        },
    ) { content() }
}

/**
 * Live preview for the Message Motion settings page: two sample bubbles on
 * a stage. The demo plays when the page opens, whenever a motion setting
 * changes, and on tap — incoming first, outgoing a beat later.
 */
@Composable
fun MessageMotionPreview() {
    val cfg = MessageMotionSettings
    var replay by remember { mutableStateOf(0) }
    LaunchedEffect(cfg.enabled.value, cfg.inStyle.value, cfg.inSpeed.value, cfg.outStyle.value, cfg.outSpeed.value) {
        replay++
    }
    Column {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { replay++ }
                .padding(12.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    DemoMotionBubble("Hey! How does this look?", out = false, startDelayMs = 0, replay = replay)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    DemoMotionBubble("Super smooth ✨", out = true, startDelayMs = 380, replay = replay)
                }
            }
        }
        Text(
            "Tap to see it again",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun DemoMotionBubble(text: String, out: Boolean, startDelayMs: Int, replay: Int) {
    val cfg = MessageMotionSettings
    val style = if (out) cfg.outStyle.value else cfg.inStyle.value
    val factor = cfg.factor(if (out) cfg.outSpeed.value else cfg.inSpeed.value)
    val active = cfg.enabled.value && style != MessageMotionSettings.OFF
    val density = LocalDensity.current
    val shiftPx = with(density) { 48.dp.toPx() }
    val scale = remember(replay, style, out) { Animatable(if (active) cfg.startScale(style) else 1f) }
    val alphaA = remember(replay, style, out) { Animatable(if (active) 0f else 1f) }
    val shift = remember(replay, style, out) {
        Animatable(if (active && style == MessageMotionSettings.SLIDE) (if (out) shiftPx else -shiftPx) else 0f)
    }
    LaunchedEffect(replay, style, factor, active) {
        if (!active) return@LaunchedEffect
        if (startDelayMs > 0) delay(startDelayMs.toLong())
        launch {
            alphaA.animateTo(
                1f,
                tween(
                    durationMillis = (cfg.alphaDurationMs(style) * factor).toInt().coerceAtLeast(1),
                    easing = FastOutSlowInEasing,
                ),
            )
        }
        when (style) {
            MessageMotionSettings.POP -> launch {
                scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f / (factor * factor)))
            }
            MessageMotionSettings.SMOOTH -> launch {
                scale.animateTo(1f, tween(durationMillis = (320 * factor).toInt().coerceAtLeast(1), easing = MotionEaseOutQuint))
            }
            MessageMotionSettings.SLIDE -> launch {
                shift.animateTo(0f, tween(durationMillis = (340 * factor).toInt().coerceAtLeast(1), easing = MotionEaseOutQuint))
            }
        }
    }
    val shape = if (out) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    Box(
        Modifier.graphicsLayer {
            this.alpha = alphaA.value
            scaleX = scale.value
            scaleY = scale.value
            translationX = shift.value
            transformOrigin = when (style) {
                MessageMotionSettings.POP -> TransformOrigin(if (out) 1f else 0f, 0.5f)
                else -> TransformOrigin(0.5f, 0.5f)
            }
        }
            .clip(shape)
            .background(if (out) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = if (out) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
