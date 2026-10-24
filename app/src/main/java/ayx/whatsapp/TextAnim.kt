package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.text.BreakIterator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * Text Animation — typing effects for the chat composer, in the spirit of the
 * classic Telegram typing-animation plugins but written fresh for this app:
 * newly typed letters animate in (blur / slide / grow / rotate with selectable
 * easing curves), deleted letters fade out as ghosts and/or scatter into
 * particles, the text bursts into the send button on send, and the caret is a
 * smooth gliding cursor with shapes, colours, liquid stretch and a trail.
 *
 * Everything is driven by [TextAnimSettings] (SharedPreferences
 * "ayx_text_animation") and edited from Settings → Appearance → Text Animation.
 * When the master switch is off the composer uses the plain Material field.
 */
object TextAnimSettings {
    val enabled = mutableStateOf(true)

    // appearance
    val speed = mutableStateOf(1)          // index into SPEEDS (ms)
    val curve = mutableStateOf(0)          // index into CURVE_LABELS
    val blur = mutableStateOf(true)
    val slide = mutableStateOf(true)
    val scale = mutableStateOf(false)
    val rotate = mutableStateOf(false)

    // deletion
    val deleteFade = mutableStateOf(true)
    val deleteScatter = mutableStateOf(true)
    val burstStyle = mutableStateOf(0)     // index into STYLE_LABELS
    val burstAmount = mutableStateOf(1)    // index into AMOUNTS
    val sendBurst = mutableStateOf(true)

    // cursor
    val cursor = mutableStateOf(true)
    val cursorPace = mutableStateOf(1)     // index into PACES
    val blink = mutableStateOf(true)
    val liquidCursor = mutableStateOf(false)
    val liquidSelection = mutableStateOf(true)
    val cursorShape = mutableStateOf(0)    // index into SHAPE_LABELS
    val cursorColor = mutableStateOf(0)    // index into COLOR_LABELS
    val cursorTrail = mutableStateOf(false)

    // feedback
    val haptic = mutableStateOf(false)

    val SPEEDS = listOf(180, 300, 450, 650)
    val SPEED_LABELS = listOf("Fast", "Normal", "Smooth", "Slow")
    val CURVE_LABELS = listOf("Soft", "Linear", "Smooth", "Overshoot", "Elastic", "Bounce")
    val PACES = listOf(12, 25, 45, 70)
    val PACE_LABELS = listOf("Smooth", "Normal", "Fast", "Very fast")
    val AMOUNTS = listOf(3, 5, 8, 12)
    val AMOUNT_LABELS = listOf("Few", "Normal", "Many", "Maximum")
    val STYLE_LABELS = listOf("Dust", "Snowflakes", "Sakura petals", "Letters", "Falling letters")
    val SHAPE_LABELS = listOf("Normal", "Thin", "Bold", "Block", "Underline")
    val COLOR_LABELS = listOf("Theme", "Accent", "Rainbow")

    fun durationMs(): Int = SPEEDS[speed.value.coerceIn(0, SPEEDS.lastIndex)]
    fun paceMs(): Int = PACES[cursorPace.value.coerceIn(0, PACES.lastIndex)]
    fun amount(): Int = AMOUNTS[burstAmount.value.coerceIn(0, AMOUNTS.lastIndex)]
    fun anyAppear(): Boolean = blur.value || slide.value || scale.value || rotate.value

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_text_animation", Context.MODE_PRIVATE)
        prefs = p
        enabled.value = p.getBoolean("enabled", true)
        speed.value = p.getInt("speed", 1)
        curve.value = p.getInt("curve", 0)
        blur.value = p.getBoolean("blur", true)
        slide.value = p.getBoolean("slide", true)
        scale.value = p.getBoolean("scale", false)
        rotate.value = p.getBoolean("rotate", false)
        deleteFade.value = p.getBoolean("delete_fade", true)
        deleteScatter.value = p.getBoolean("delete_scatter", true)
        burstStyle.value = p.getInt("burst_style", 0)
        burstAmount.value = p.getInt("burst_amount", 1)
        sendBurst.value = p.getBoolean("send_burst", true)
        cursor.value = p.getBoolean("cursor", true)
        cursorPace.value = p.getInt("cursor_pace", 1)
        blink.value = p.getBoolean("blink", true)
        liquidCursor.value = p.getBoolean("liquid_cursor", false)
        liquidSelection.value = p.getBoolean("liquid_selection", true)
        cursorShape.value = p.getInt("cursor_shape", 0)
        cursorColor.value = p.getInt("cursor_color", 0)
        cursorTrail.value = p.getBoolean("cursor_trail", false)
        haptic.value = p.getBoolean("haptic", false)
    }

    private fun putB(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }
    private fun putI(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }

    fun setEnabled(v: Boolean) { enabled.value = v; putB("enabled", v) }
    fun setSpeed(v: Int) { speed.value = v; putI("speed", v) }
    fun setCurve(v: Int) { curve.value = v; putI("curve", v) }
    fun setBlur(v: Boolean) { blur.value = v; putB("blur", v) }
    fun setSlide(v: Boolean) { slide.value = v; putB("slide", v) }
    fun setScale(v: Boolean) { scale.value = v; putB("scale", v) }
    fun setRotate(v: Boolean) { rotate.value = v; putB("rotate", v) }
    fun setDeleteFade(v: Boolean) { deleteFade.value = v; putB("delete_fade", v) }
    fun setDeleteScatter(v: Boolean) { deleteScatter.value = v; putB("delete_scatter", v) }
    fun setBurstStyle(v: Int) { burstStyle.value = v; putI("burst_style", v) }
    fun setBurstAmount(v: Int) { burstAmount.value = v; putI("burst_amount", v) }
    fun setSendBurst(v: Boolean) { sendBurst.value = v; putB("send_burst", v) }
    fun setCursor(v: Boolean) { cursor.value = v; putB("cursor", v) }
    fun setCursorPace(v: Int) { cursorPace.value = v; putI("cursor_pace", v) }
    fun setBlink(v: Boolean) { blink.value = v; putB("blink", v) }
    fun setLiquidCursor(v: Boolean) { liquidCursor.value = v; putB("liquid_cursor", v) }
    fun setLiquidSelection(v: Boolean) { liquidSelection.value = v; putB("liquid_selection", v) }
    fun setCursorShape(v: Int) { cursorShape.value = v; putI("cursor_shape", v) }
    fun setCursorColor(v: Int) { cursorColor.value = v; putI("cursor_color", v) }
    fun setCursorTrail(v: Boolean) { cursorTrail.value = v; putB("cursor_trail", v) }
    fun setHaptic(v: Boolean) { haptic.value = v; putB("haptic", v) }
}

/** Standard easing curves (textbook formulas), indexed like the settings list. */
internal object TextAnimEasing {
    fun apply(curve: Int, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (curve) {
            1 -> x // linear
            2 -> { val u = 1f - x; 1f - u * u * u } // smooth (ease-out cubic)
            3 -> backOut(x) // overshoot
            4 -> elasticOut(x) // elastic
            5 -> bounceOut(x) // bounce
            else -> { val u = 1f - x; 1f - u * u * u * u * u } // soft (ease-out quint)
        }
    }

    private fun backOut(x: Float): Float {
        val c = 1.70158f
        val u = x - 1f
        return 1f + (c + 1f) * u * u * u + c * u * u
    }

    private fun elasticOut(x: Float): Float {
        if (x == 0f || x == 1f) return x
        val period = (2.0 * Math.PI / 3.0)
        return (Math.pow(2.0, -10.0 * x) * Math.sin((x * 10.0 - 0.75) * period) + 1.0).toFloat()
    }

    private fun bounceOut(x: Float): Float {
        val n = 7.5625f
        val d = 2.75f
        return when {
            x < 1f / d -> n * x * x
            x < 2f / d -> { val u = x - 1.5f / d; n * u * u + 0.75f }
            x < 2.5f / d -> { val u = x - 2.25f / d; n * u * u + 0.9375f }
            else -> { val u = x - 2.625f / d; n * u * u + 0.984375f }
        }
    }

    /** Glyph "sharpens in": invisible for the first fifth, then ramps to full. */
    fun sharp(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x <= 0.2f) 0f else (x - 0.2f) / 0.8f
    }

    fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

/** Split into grapheme clusters (emoji / combining marks stay one unit). */
internal fun graphemeRanges(s: String): List<IntRange> {
    if (s.isEmpty()) return emptyList()
    val bi = BreakIterator.getCharacterInstance()
    bi.setText(s)
    val out = ArrayList<IntRange>()
    var start = bi.first()
    var end = bi.next()
    while (end != BreakIterator.DONE) {
        out.add(start until end)
        start = end
        end = bi.next()
    }
    return out
}

/** Union of the per-character boxes of one cluster, in text-layout coordinates. */
internal fun clusterRect(lay: TextLayoutResult, range: IntRange): Rect? {
    var acc: Rect? = null
    val limit = lay.layoutInput.text.length
    for (i in range) {
        if (i < 0 || i >= limit) break
        val b = runCatching { lay.getBoundingBox(i) }.getOrNull() ?: continue
        val a = acc
        acc = if (a == null) b else Rect(
            min(a.left, b.left), min(a.top, b.top),
            max(a.right, b.right), max(a.bottom, b.bottom)
        )
    }
    return acc
}

/**
 * Bridge between the composer field and the send button: the button reports
 * its window position here and bumps [sendTick] when a send fires, so the
 * field can burst its text into the button.
 */
class TextAnimBus {
    var sendTick by mutableStateOf(0)
    var sendTargetWindow: Offset? = null
    fun fireSend() { sendTick++ }
}

internal class GlyphAnim(val start: Int, val len: Int, val startNanos: Long)

internal class GhostGlyph(
    val text: String,
    val rect: Rect, // field coordinates (padding already applied)
    val startNanos: Long,
)

internal class AnimParticle(
    val style: Int,
    val ch: String?,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val bornNanos: Long,
    val lifeMs: Float,
    val sizePx: Float,
    val color: Color,
    var rot: Float,
    val spin: Float,
    val phase: Float,
    val gravity: Float,
    val drag: Float,
    val target: Offset? = null,
    val pull: Float = 0f,
)

internal class TrailMark(val x: Float, val y: Float, val h: Float, val bornNanos: Long)

internal class TextAnimEngine {
    val glyphs: SnapshotStateList<GlyphAnim> = mutableStateListOf()
    val ghosts: SnapshotStateList<GhostGlyph> = mutableStateListOf()
    val particles: SnapshotStateList<AnimParticle> = mutableStateListOf()
    val trail: SnapshotStateList<TrailMark> = mutableStateListOf()
    fun hasTransient(): Boolean =
        glyphs.isNotEmpty() || ghosts.isNotEmpty() || particles.isNotEmpty() || trail.isNotEmpty()
}

private val SAKURA_COLORS = listOf(
    Color(0xFFF8BBD0), Color(0xFFF48FB1), Color(0xFFFCE4EC), Color(0xFFF06292)
)
private val SNOW_COLOR = Color(0xFFF2F7FF)

/** Spawn the delete-scatter particles for one deleted cluster. */
internal fun spawnScatter(
    engine: TextAnimEngine,
    style: Int,
    amount: Int,
    cluster: String,
    fieldRect: Rect,
    rng: java.util.Random,
    dpPx: Float,
    textColor: Color,
    now: Long,
) {
    val cx = fieldRect.center.x
    val cy = fieldRect.center.y
    repeat(amount) {
        val a = rng.nextFloat() * (Math.PI * 2).toFloat()
        when (style) {
            1 -> { // snowflakes: gentle upward pop, then a slow drifting fall
                engine.particles.add(AnimParticle(
                    style = 1, ch = null,
                    x = cx + (rng.nextFloat() - 0.5f) * fieldRect.width,
                    y = cy + (rng.nextFloat() - 0.5f) * fieldRect.height,
                    vx = (rng.nextFloat() - 0.5f) * 30f * dpPx,
                    vy = -(10f + rng.nextFloat() * 30f) * dpPx,
                    bornNanos = now, lifeMs = 1300f + rng.nextFloat() * 900f,
                    sizePx = (1.4f + rng.nextFloat() * 1.8f) * dpPx,
                    color = SNOW_COLOR,
                    rot = 0f, spin = 0f, phase = rng.nextFloat() * 6.28f,
                    gravity = 26f * dpPx, drag = 0.4f,
                ))
            }
            2 -> { // sakura petals: pink ellipses fluttering down
                engine.particles.add(AnimParticle(
                    style = 2, ch = null,
                    x = cx + (rng.nextFloat() - 0.5f) * fieldRect.width,
                    y = cy,
                    vx = (rng.nextFloat() - 0.5f) * 44f * dpPx,
                    vy = (6f + rng.nextFloat() * 22f) * dpPx,
                    bornNanos = now, lifeMs = 1500f + rng.nextFloat() * 900f,
                    sizePx = (3f + rng.nextFloat() * 3f) * dpPx,
                    color = SAKURA_COLORS[rng.nextInt(SAKURA_COLORS.size)],
                    rot = rng.nextFloat() * 360f, spin = (rng.nextFloat() - 0.5f) * 260f,
                    phase = rng.nextFloat() * 6.28f,
                    gravity = 30f * dpPx, drag = 0.5f,
                ))
            }
            3 -> { // letters: the deleted characters themselves, tossed with spin
                engine.particles.add(AnimParticle(
                    style = 3, ch = cluster,
                    x = cx, y = cy,
                    vx = (rng.nextFloat() - 0.5f) * 120f * dpPx,
                    vy = -(60f + rng.nextFloat() * 130f) * dpPx,
                    bornNanos = now, lifeMs = 650f + rng.nextFloat() * 400f,
                    sizePx = fieldRect.height,
                    color = textColor,
                    rot = (rng.nextFloat() - 0.5f) * 40f, spin = (rng.nextFloat() - 0.5f) * 300f,
                    phase = 0f, gravity = 460f * dpPx, drag = 0.15f,
                ))
            }
            4 -> { // falling letters: characters drop straight down
                engine.particles.add(AnimParticle(
                    style = 4, ch = cluster,
                    x = cx + (rng.nextFloat() - 0.5f) * fieldRect.width * 0.5f,
                    y = cy,
                    vx = (rng.nextFloat() - 0.5f) * 24f * dpPx,
                    vy = 10f * dpPx,
                    bornNanos = now, lifeMs = 800f + rng.nextFloat() * 350f,
                    sizePx = fieldRect.height,
                    color = textColor,
                    rot = (rng.nextFloat() - 0.5f) * 24f, spin = (rng.nextFloat() - 0.5f) * 120f,
                    phase = 0f, gravity = 900f * dpPx, drag = 0.05f,
                ))
            }
            else -> { // dust: tiny specks bursting outward, quick fade
                val sp = (26f + rng.nextFloat() * 95f) * dpPx
                engine.particles.add(AnimParticle(
                    style = 0, ch = null,
                    x = cx, y = cy,
                    vx = kotlin.math.cos(a) * sp,
                    vy = kotlin.math.sin(a) * sp - 14f * dpPx,
                    bornNanos = now, lifeMs = 420f + rng.nextFloat() * 420f,
                    sizePx = (1.1f + rng.nextFloat() * 2.1f) * dpPx,
                    color = textColor,
                    rot = 0f, spin = 0f, phase = rng.nextFloat() * 6.28f,
                    gravity = 60f * dpPx, drag = 2.4f,
                ))
            }
        }
    }
    while (engine.particles.size > 260) engine.particles.removeAt(0)
}

/** Send burst: one particle per cluster, pulled into the send button. */
internal fun spawnBurst(
    engine: TextAnimEngine,
    text: String,
    lay: TextLayoutResult,
    padX: Float,
    padY: Float,
    target: Offset,
    letterStyle: Boolean,
    rng: java.util.Random,
    textColor: Color,
    accentColor: Color,
    now: Long,
) {
    val ranges = graphemeRanges(text)
    val step = if (ranges.size > 72) (ranges.size + 71) / 72 else 1
    var i = 0
    while (i < ranges.size) {
        val r = ranges[i]
        i += step
        val rect = clusterRect(lay, r) ?: continue
        val cx = rect.center.x + padX
        val cy = rect.center.y + padY
        val cluster = text.substring(r)
        engine.particles.add(AnimParticle(
            style = if (letterStyle && cluster.isNotBlank()) 3 else 0,
            ch = if (letterStyle) cluster else null,
            x = cx, y = cy,
            vx = (rng.nextFloat() - 0.5f) * 90f,
            vy = (rng.nextFloat() - 0.5f) * 90f,
            bornNanos = now, lifeMs = 950f,
            sizePx = if (letterStyle) rect.height else 3.2f + rng.nextFloat() * 2.4f,
            color = if (rng.nextBoolean()) accentColor else textColor,
            rot = 0f, spin = (rng.nextFloat() - 0.5f) * 200f,
            phase = rng.nextFloat() * 6.28f,
            gravity = 0f, drag = 0.6f,
            target = target, pull = 1500f,
        ))
    }
    while (engine.particles.size > 320) engine.particles.removeAt(0)
}

/** Advance all particles/ghosts/glyphs by one frame. */
internal fun stepEngine(engine: TextAnimEngine, now: Long, dtMs: Float) {
    val cfg = TextAnimSettings
    val dt = (dtMs / 1000f).coerceIn(0f, 0.05f)
    val durNanos = cfg.durationMs() * 1_000_000L
    engine.glyphs.removeAll { now - it.startNanos > durNanos }
    engine.ghosts.removeAll { (now - it.startNanos).toFloat() / durNanos.toFloat() > 1.15f }
    engine.trail.removeAll { now - it.bornNanos > 260_000_000L }
    val pit = engine.particles.toList()
    val dead = ArrayList<AnimParticle>()
    for (p in pit) {
        val ageMs = (now - p.bornNanos) / 1_000_000f
        if (ageMs > p.lifeMs) { dead.add(p); continue }
        val tgt = p.target
        if (tgt != null) {
            val dx = tgt.x - p.x
            val dy = tgt.y - p.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < 18f) { dead.add(p); continue }
            p.vx += dx / dist * p.pull * dt
            p.vy += dy / dist * p.pull * dt
            val damp = exp(-p.drag * dt)
            p.vx *= damp; p.vy *= damp
        } else {
            p.vy += p.gravity * dt
            val damp = exp(-p.drag * dt)
            p.vx *= damp; p.vy *= damp
            if (p.style == 1 || p.style == 2) {
                // snow / sakura sway sideways while falling
                p.x += kotlin.math.sin(ageMs * 0.004f + p.phase) * 22f * dt
            }
        }
        p.x += p.vx * dt
        p.y += p.vy * dt
        p.rot += p.spin * dt
    }
    if (dead.isNotEmpty()) engine.particles.removeAll(dead)
}


/** Fake-softness for the blur effect: faint copies around the glyph while it sharpens in. */
private fun DrawScope.drawBlurGhosts(
    cl: TextLayoutResult, color: Color, x: Float, y: Float, alpha: Float, pRaw: Float, spreadPx: Float,
) {
    val off = (1f - pRaw) * spreadPx
    val a = alpha * 0.16f
    if (a <= 0.01f || off <= 0.3f) return
    drawText(cl, color = color, topLeft = Offset(x - off, y), alpha = a)
    drawText(cl, color = color, topLeft = Offset(x + off, y), alpha = a)
    drawText(cl, color = color, topLeft = Offset(x, y - off), alpha = a)
    drawText(cl, color = color, topLeft = Offset(x, y + off), alpha = a)
}

/**
 * The animated message field. A foundation BasicTextField does the real
 * editing/IME work; when an appear effect is on, its text is drawn
 * transparent and an overlay Canvas paints every grapheme cluster itself
 * (from the field's own TextLayoutResult, so positions are exact), plus the
 * ghost/particle layers and the custom gliding caret.
 *
 * Safety fallbacks: while the text is scrolled (taller than the visible
 * field) the overlay cannot know the scroll offset, so rendering falls back
 * to the native text/caret until the text fits again. Bulk inserts (paste)
 * skip the appear animation. IME composition is never animated.
 */
@Composable
fun AnimatedComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (FocusState) -> Unit = {},
    bus: TextAnimBus? = null,
    previewMode: Boolean = false,
    placeholderText: String = "Message",
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = Color.Unspecified,
    hintColor: Color = Color.Unspecified,
    accentColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    singleLine: Boolean = false,
) {
    val cfg = TextAnimSettings
    val density = LocalDensity.current
    val dpPx = density.density
    val layoutDirection = LocalLayoutDirection.current
    val padXPx = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }
    val padYPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val slidePx = 20f * dpPx
    val sinkPx = 2f * dpPx
    val blurSpreadPx = 2.2f * dpPx

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val textStyleBase = textStyle
    val drawColor = if (textColor != Color.Unspecified) textColor
        else if (textStyle.color != Color.Unspecified) textStyle.color
        else onSurface
    val accent = if (accentColor != Color.Unspecified) accentColor else primary
    val hint = if (hintColor != Color.Unspecified) hintColor else onSurfaceVariant
    val selectionColors = LocalTextSelectionColors.current
    val view = LocalView.current
    val measurer = rememberTextMeasurer()
    val rng = remember { java.util.Random() }

    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    var fieldWindow by remember { mutableStateOf(Offset.Zero) }
    var burstText by remember { mutableStateOf("") }
    var burstLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var frameNanos by remember { mutableStateOf(0L) }
    var lastActivityNanos by remember { mutableStateOf(0L) }
    var caretX by remember { mutableStateOf<Float?>(null) }
    var caretTop by remember { mutableStateOf(0f) }
    var caretHeight by remember { mutableStateOf(0f) }
    var caretVelX by remember { mutableStateOf(0f) }
    var caretVelY by remember { mutableStateOf(0f) }
    var lastTrailNanos by remember { mutableStateOf(0L) }
    val engine = remember { TextAnimEngine() }

    val fieldStyleDraw = textStyleBase.copy(color = drawColor)
    val measureCache = remember(fieldStyleDraw) { HashMap<String, TextLayoutResult>() }
    fun measureCluster(cluster: String): TextLayoutResult {
        measureCache[cluster]?.let { return it }
        val r = measurer.measure(cluster, fieldStyleDraw)
        if (measureCache.size > 400) measureCache.clear()
        measureCache[cluster] = r
        return r
    }

    fun spawnForDelete(clusters: List<String>, ranges: List<IntRange>, lay: TextLayoutResult?) {
        if (lay == null) return
        val now = System.nanoTime()
        for (i in clusters.indices) {
            val rect = clusterRect(lay, ranges[i]) ?: continue
            val fieldRect = Rect(
                rect.left + padXPx, rect.top + padYPx,
                rect.right + padXPx, rect.bottom + padYPx
            )
            if (cfg.deleteFade.value && clusters[i].isNotBlank()) {
                engine.ghosts.add(GhostGlyph(clusters[i], fieldRect, now))
            }
            if (cfg.deleteScatter.value) {
                spawnScatter(
                    engine, cfg.burstStyle.value, cfg.amount(), clusters[i],
                    fieldRect, rng, dpPx, drawColor, now
                )
            }
        }
        while (engine.ghosts.size > 60) engine.ghosts.removeAt(0)
    }

    /** Diff old -> new text: register appear animations, spawn delete effects. */
    fun handleChange(oldText: String, newText: String) {
        if (oldText == newText) return
        lastActivityNanos = System.nanoTime()
        val oldRanges = graphemeRanges(oldText)
        val newRanges = graphemeRanges(newText)
        var prefix = 0
        while (prefix < oldRanges.size && prefix < newRanges.size &&
            oldText.substring(oldRanges[prefix]) == newText.substring(newRanges[prefix])
        ) prefix++
        var suffix = 0
        while (suffix < (oldRanges.size - prefix) && suffix < (newRanges.size - prefix) &&
            oldText.substring(oldRanges[oldRanges.size - 1 - suffix]) ==
            newText.substring(newRanges[newRanges.size - 1 - suffix])
        ) suffix++
        val delCount = oldRanges.size - prefix - suffix
        val insCount = newRanges.size - prefix - suffix
        val now = System.nanoTime()
        if (delCount > 0) {
            val delClusters = (0 until delCount).map { oldText.substring(oldRanges[prefix + it]) }
            val delRanges = (0 until delCount).map { oldRanges[prefix + it] }
            spawnForDelete(delClusters, delRanges, layoutResult)
            val delStartChar = delRanges.first().first
            val delEndChar = delRanges.last().last + 1
            val insChars = (0 until insCount).sumOf { val r = newRanges[prefix + it]; r.last - r.first + 1 }
            val delta = insChars - (delEndChar - delStartChar)
            val kept = ArrayList<GlyphAnim>()
            for (g in engine.glyphs) {
                when {
                    g.start >= delEndChar -> kept.add(GlyphAnim(g.start + delta, g.len, g.startNanos))
                    g.start + g.len <= delStartChar -> kept.add(g)
                    // inside the deleted span: drop
                }
            }
            engine.glyphs.clear()
            engine.glyphs.addAll(kept)
        } else if (insCount > 0) {
            val insStartChar = newRanges[prefix].first
            val insChars = (0 until insCount).sumOf { val r = newRanges[prefix + it]; r.last - r.first + 1 }
            val shifted = engine.glyphs.map { g ->
                if (g.start >= insStartChar) GlyphAnim(g.start + insChars, g.len, g.startNanos) else g
            }
            engine.glyphs.clear()
            engine.glyphs.addAll(shifted)
        }
        if (insCount > 0 && insCount <= 24) {
            for (j in 0 until insCount) {
                val r = newRanges[prefix + j]
                engine.glyphs.add(GlyphAnim(r.first, r.last - r.first + 1, now))
            }
            if (cfg.haptic.value && insCount <= 3) {
                runCatching { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            }
        }
    }

    fun stepCaret(now: Long, dtMs: Float) {
        val lay = layoutResult
        if (!cfg.cursor.value || lay == null) { caretX = null; return }
        val sel = tfv.selection
        if (!sel.collapsed) { caretX = null; return }
        if (!focused && !previewMode) { caretX = null; return }
        val off = sel.start.coerceIn(0, tfv.text.length)
        val rect = runCatching { lay.getCursorRect(off) }.getOrNull() ?: return
        val tx = rect.left + padXPx
        val ty = rect.top + padYPx
        val th = max(rect.height, 1f)
        val cx = caretX
        if (cx == null) {
            caretX = tx; caretTop = ty; caretHeight = th
            caretVelX = 0f; caretVelY = 0f
            return
        }
        val tau = cfg.paceMs().toFloat().coerceAtLeast(1f)
        val k = 1f - exp(-dtMs / tau)
        val nx = cx + (tx - cx) * k
        val ny = caretTop + (ty - caretTop) * k
        if (cfg.cursorTrail.value &&
            (abs(tx - cx) > 46f * dpPx || abs(ty - caretTop) > th * 1.2f) &&
            now - lastTrailNanos > 90_000_000L
        ) {
            engine.trail.add(TrailMark(cx, caretTop, caretHeight, now))
            lastTrailNanos = now
            while (engine.trail.size > 10) engine.trail.removeAt(0)
        }
        caretVelX = (nx - cx) / max(dtMs, 1f) * 16f
        caretVelY = (ny - caretTop) / max(dtMs, 1f) * 16f
        caretX = nx; caretTop = ny; caretHeight = th
    }

    // External changes (send-clear, preview script, programmatic inserts):
    // animate them through the same diff path, then adopt the new value.
    LaunchedEffect(value) {
        if (value != tfv.text) {
            handleChange(tfv.text, value)
            tfv = TextFieldValue(value, TextRange(value.length))
        }
    }

    // Send burst: the send button fired — burst the last rendered text into it.
    val busTick = bus?.sendTick ?: 0
    LaunchedEffect(busTick) {
        if (bus == null || busTick == 0) return@LaunchedEffect
        if (!cfg.sendBurst.value) return@LaunchedEffect
        val lay = burstLayout ?: return@LaunchedEffect
        val txt = burstText
        if (txt.isEmpty()) return@LaunchedEffect
        val tw = bus.sendTargetWindow ?: return@LaunchedEffect
        val target = Offset(tw.x - fieldWindow.x, tw.y - fieldWindow.y)
        spawnBurst(
            engine, txt, lay, padXPx, padYPx, target,
            cfg.burstStyle.value == 3 || cfg.burstStyle.value == 4,
            rng, drawColor, accent, System.nanoTime()
        )
    }

    val loopActive = focused || previewMode || engine.hasTransient()
    LaunchedEffect(loopActive) {
        if (!loopActive) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                frameNanos = now
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 50f)
                last = now
                stepEngine(engine, now, dt)
                stepCaret(now, dt)
            }
        }
    }

    val layNow = layoutResult
    val scrollSafe = layNow == null || fieldSize.height == 0 ||
        layNow.size.height + padYPx * 2 <= fieldSize.height + 2
    val customText = cfg.anyAppear() && scrollSafe
    val customCaret = cfg.cursor.value && scrollSafe
    val fieldStyle = textStyleBase.copy(color = if (customText) Color.Transparent else drawColor)
    val effectiveSelectionColors = if (customText && cfg.liquidSelection.value) {
        TextSelectionColors(selectionColors.handleColor, Color.Transparent)
    } else selectionColors

    Box(
        modifier.onGloballyPositioned {
            fieldWindow = it.positionInWindow()
            fieldSize = it.size
        }
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides effectiveSelectionColors) {
            BasicTextField(
                value = tfv,
                onValueChange = { nv ->
                    val old = tfv
                    tfv = nv
                    if (nv.text != old.text) {
                        if (nv.composition == null) handleChange(old.text, nv.text)
                        else lastActivityNanos = System.nanoTime()
                        onValueChange(nv.text)
                    } else {
                        lastActivityNanos = System.nanoTime()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { fs ->
                        focused = fs.isFocused
                        if (fs.isFocused) lastActivityNanos = System.nanoTime()
                        onFocusChanged(fs)
                    },
                readOnly = previewMode,
                textStyle = fieldStyle,
                cursorBrush = if (customCaret) SolidColor(Color.Transparent) else SolidColor(accent),
                maxLines = maxLines,
                singleLine = singleLine,
                onTextLayout = { lr ->
                    layoutResult = lr
                    if (tfv.text.isNotEmpty()) {
                        burstText = tfv.text
                        burstLayout = lr
                    }
                },
                decorationBox = { inner ->
                    Box(Modifier.padding(contentPadding)) {
                        if (tfv.text.isEmpty()) {
                            Text(
                                placeholderText,
                                style = textStyleBase,
                                color = hint,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Canvas(Modifier.matchParentSize()) {
            val now = frameNanos
            val lay = layoutResult
            val durF = (cfg.durationMs() * 1_000_000L).toFloat()
            val text = tfv.text
            val sel = tfv.selection

            if (lay != null && scrollSafe) {
                // Liquid selection: our own highlight path (native one is hidden).
                if (customText && cfg.liquidSelection.value && !sel.collapsed && sel.end > sel.start) {
                    val s = sel.start.coerceIn(0, text.length)
                    val e = sel.end.coerceIn(0, text.length)
                    val path = runCatching { lay.getPathForRange(s, e) }.getOrNull()
                    if (path != null) {
                        path.translate(Offset(padXPx, padYPx))
                        val pulse = 0.92f + 0.08f * kotlin.math.sin(now / 90_000_000.0).toFloat()
                        val bg = selectionColors.backgroundColor
                        drawPath(path, color = bg.copy(alpha = (bg.alpha * pulse).coerceIn(0f, 1f)))
                    }
                }

                // Ghosts: deleted letters fading + sinking where they used to be.
                for (g in engine.ghosts) {
                    if (g.text.isBlank()) continue
                    val ageP = ((now - g.startNanos).toFloat() / durF).coerceIn(0f, 1.15f)
                    val a = (1f - ageP / 1.15f).coerceIn(0f, 1f) * 0.85f
                    if (a <= 0.01f) continue
                    val cl = measureCluster(g.text)
                    val clRect = clusterRect(cl, 0 until g.text.length)
                    val dx = g.rect.left - (clRect?.left ?: 0f)
                    val dy = g.rect.top - (clRect?.top ?: 0f) + sinkPx * min(ageP, 1f)
                    drawText(cl, color = drawColor, topLeft = Offset(dx, dy), alpha = a)
                }

                // Glyphs: the text itself, per cluster, with appear animations.
                if (customText) {
                    val ranges = graphemeRanges(text)
                    for (r in ranges) {
                        val cluster = text.substring(r)
                        if (cluster.all { it == ' ' || it == '\n' || it == '\t' }) continue
                        val rect = clusterRect(lay, r) ?: continue
                        val cl = measureCluster(cluster)
                        val clRect = clusterRect(cl, 0 until cluster.length)
                        val baseX = rect.left + padXPx - (clRect?.left ?: 0f)
                        val baseY = rect.top + padYPx - (clRect?.top ?: 0f)
                        val anim = engine.glyphs.firstOrNull { it.start == r.first }
                        if (anim == null) {
                            drawText(cl, color = drawColor, topLeft = Offset(baseX, baseY))
                        } else {
                            val pRaw = ((now - anim.startNanos).toFloat() / durF).coerceIn(0f, 1f)
                            val p = TextAnimEasing.apply(cfg.curve.value, pRaw)
                            var alpha = if (cfg.blur.value) TextAnimEasing.sharp(pRaw) else pRaw
                            alpha = alpha.coerceIn(0f, 1f)
                            val slideOff = if (cfg.slide.value) (p - 1f) * slidePx else 0f
                            val topLeft = Offset(baseX, baseY + slideOff)
                            if (cfg.scale.value || cfg.rotate.value) {
                                val pivot = Offset(
                                    rect.center.x + padXPx,
                                    rect.center.y + padYPx + slideOff
                                )
                                withTransform({
                                    if (cfg.scale.value) {
                                        val sc = max(p, 0.01f)
                                        scale(sc, sc, pivot)
                                    }
                                    if (cfg.rotate.value) rotate((1f - p) * -15f, pivot)
                                }) {
                                    drawText(cl, color = drawColor, topLeft = topLeft, alpha = alpha)
                                    if (cfg.blur.value && pRaw < 1f) {
                                        drawBlurGhosts(cl, drawColor, topLeft.x, topLeft.y, alpha, pRaw, blurSpreadPx)
                                    }
                                }
                            } else {
                                drawText(cl, color = drawColor, topLeft = topLeft, alpha = alpha)
                                if (cfg.blur.value && pRaw < 1f) {
                                    drawBlurGhosts(cl, drawColor, topLeft.x, topLeft.y, alpha, pRaw, blurSpreadPx)
                                }
                            }
                        }
                    }
                }
            }

            // Particles (field coordinates; may fly beyond the text area to the send button).
            for (pt in engine.particles) {
                val ageP = ((now - pt.bornNanos) / 1_000_000f / pt.lifeMs).coerceIn(0f, 1f)
                val fade = if (pt.target != null) 1f else 1f - ageP
                val a = (fade * fade).coerceIn(0f, 1f)
                if (a <= 0.01f) continue
                when (pt.style) {
                    2 -> withTransform({ rotate(pt.rot, Offset(pt.x, pt.y)) }) {
                        drawOval(
                            pt.color.copy(alpha = a),
                            topLeft = Offset(pt.x - pt.sizePx * 0.8f, pt.y - pt.sizePx * 0.5f),
                            size = Size(pt.sizePx * 1.6f, pt.sizePx),
                        )
                    }
                    3, 4 -> {
                        val ch = pt.ch
                        if (!ch.isNullOrBlank()) {
                            val cl = measureCluster(ch)
                            val clRect = clusterRect(cl, 0 until ch.length)
                            val dx = pt.x - (clRect?.center?.x ?: (pt.sizePx / 2f))
                            val dy = pt.y - (clRect?.center?.y ?: (pt.sizePx / 2f))
                            withTransform({ rotate(pt.rot, Offset(pt.x, pt.y)) }) {
                                drawText(cl, color = pt.color, topLeft = Offset(dx, dy), alpha = a)
                            }
                        } else if (!previewMode) { // v189: preview hides blank-cluster circles; real chats unchanged
                            drawCircle(pt.color.copy(alpha = a), radius = pt.sizePx * 0.35f, center = Offset(pt.x, pt.y))
                        }
                    }
                    else -> drawCircle(
                        pt.color.copy(alpha = a),
                        radius = pt.sizePx * (0.5f + 0.5f * (1f - ageP)),
                        center = Offset(pt.x, pt.y),
                    )
                }
            }

            // Caret (custom gliding cursor) + trail.
            val cxv = caretX
            if (cfg.cursor.value && customCaret && cxv != null && (focused || previewMode) &&
                tfv.selection.collapsed && lay != null && scrollSafe
            ) {
                val caretColor = when (cfg.cursorColor.value) {
                    1 -> ThemeStore.accentColor()
                    2 -> Color.hsv(((now / 1_000_000L % 3600L) / 10f), 0.65f, 1f)
                    else -> accent
                }
                var ca = 1f
                if (cfg.blink.value) {
                    val idleMs = (now - lastActivityNanos) / 1_000_000f
                    if (idleMs > 550f && now > 0L) {
                        val phase = ((now / 1_000_000L) % 1100L).toFloat() / 1100f
                        ca = 0.25f + 0.75f * (0.5f - 0.5f * kotlin.math.cos(phase * 2f * Math.PI.toFloat()))
                    }
                }
                val widthDp = when (cfg.cursorShape.value) {
                    1 -> 1.2f
                    2 -> 3.5f
                    else -> 2f
                }
                if (cfg.cursorTrail.value) {
                    for (tm in engine.trail) {
                        val ageP = ((now - tm.bornNanos).toFloat() / 260_000_000f).coerceIn(0f, 1f)
                        drawRoundRect(
                            caretColor.copy(alpha = (1f - ageP) * 0.32f * ca),
                            topLeft = Offset(tm.x - widthDp * dpPx / 2f, tm.y),
                            size = Size(widthDp * dpPx, tm.h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthDp * dpPx / 2f),
                        )
                    }
                }
                val nextW: Float = run {
                    val off = tfv.selection.start
                    if (off >= text.length) 9f * dpPx
                    else {
                        val rr = graphemeRanges(text).firstOrNull { off in it }
                        val rect = rr?.let { clusterRect(lay, it) }
                        max(rect?.width ?: (9f * dpPx), 2f)
                    }
                }
                val stretchY = if (cfg.liquidCursor.value) {
                    1f + min(abs(caretVelY) * 0.012f, 0.65f)
                } else 1f
                val squeezeX = if (cfg.liquidCursor.value) {
                    1f - min(abs(caretVelX) * 0.006f, 0.4f)
                } else 1f
                when (cfg.cursorShape.value) {
                    3 -> drawRoundRect( // block
                        caretColor.copy(alpha = 0.32f * ca),
                        topLeft = Offset(cxv, caretTop),
                        size = Size(nextW, caretHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * dpPx),
                    )
                    4 -> drawRoundRect( // underline
                        caretColor.copy(alpha = ca),
                        topLeft = Offset(cxv, caretTop + caretHeight - 2f * dpPx),
                        size = Size(nextW, 2f * dpPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f * dpPx),
                    )
                    else -> {
                        val w = widthDp * dpPx * squeezeX
                        val h = caretHeight * stretchY
                        drawRoundRect(
                            caretColor.copy(alpha = ca),
                            topLeft = Offset(cxv - w / 2f, caretTop + (caretHeight - h) / 2f),
                            size = Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                        )
                    }
                }
            }
        }
    }
}


/**
 * Live preview for the Text Animation settings page: a mini composer that
 * types, deletes and "sends" on a loop, driven through the exact same field
 * and settings as the real chat composer.
 */
@Composable
fun TextAnimPreview() {
    var text by remember { mutableStateOf("") }
    val bus = remember { TextAnimBus() }
    var targetWindow by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        val line1 = "Typing smoothly"
        val word2 = "smoothly"
        val line2 = "And a second line"
        while (true) {
            var cur = ""
            for (c in graphemeRanges(line1).map { line1.substring(it) }) {
                cur += c
                text = cur
                delay(120)
            }
            delay(850)
            // delete the last word letter by letter (ghost + scatter demo)
            val keep = line1.length - word2.length
            while (text.length > keep) {
                text = text.dropLast(1)
                delay(60)
            }
            delay(400)
            var cur2 = text + "\n"
            text = cur2
            for (c in graphemeRanges(line2).map { line2.substring(it) }) {
                cur2 += c
                text = cur2
                delay(105)
            }
            delay(1150)
            // send-burst demo: burst into the right edge of the preview box
            bus.sendTargetWindow = targetWindow
            bus.fireSend()
            text = ""
            delay(1300)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .onGloballyPositioned {
                val pos = it.positionInWindow()
                targetWindow = Offset(
                    pos.x + it.size.width - 26f * density.density,
                    pos.y + it.size.height / 2f
                )
            }
    ) {
        AnimatedComposerField(
            value = text,
            onValueChange = { text = it },
            maxLines = 3,
            bus = bus,
            previewMode = true,
        )
    }
}

/**
 * Reusable animated replacement for a Material3 OutlinedTextField: same
 * outlined shell (border that highlights on focus, optional leading icon,
 * optional filled container), but the text inside is [AnimatedComposerField],
 * so typing / delete / caret effects apply. Hosts use this only when the
 * Text Animation master switch is on and keep their original
 * OutlinedTextField otherwise, so the off state is pixel-identical to before.
 */
@Composable
fun AnimatedOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholderText: String = "",
    singleLine: Boolean = false,
    maxLines: Int = 4,
    shape: Shape = RoundedCornerShape(12.dp),
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    textColor: Color = Color.Unspecified,
    hintColor: Color = Color.Unspecified,
    accentColor: Color = Color.Unspecified,
    borderColor: Color = Color.Unspecified,
    focusedBorderColor: Color = Color.Unspecified,
    containerColor: Color = Color.Transparent,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val bOff = if (borderColor != Color.Unspecified) borderColor else outline
    val bOn = if (focusedBorderColor != Color.Unspecified) focusedBorderColor else primary
    Box(
        modifier
            .clip(shape)
            .background(containerColor)
            .border(if (focused) 2.dp else 1.dp, if (focused) bOn else bOff, shape)
    ) {
        Row(verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
            if (leadingIcon != null) {
                Box(
                    Modifier.padding(
                        start = 12.dp,
                        top = if (singleLine) 0.dp else 16.dp,
                    )
                ) { leadingIcon() }
            }
            AnimatedComposerField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                maxLines = maxLines,
                singleLine = singleLine,
                onFocusChanged = { focused = it.isFocused },
                placeholderText = placeholderText,
                textStyle = textStyle,
                textColor = textColor,
                hintColor = hintColor,
                accentColor = accentColor,
                contentPadding = if (leadingIcon != null) {
                    PaddingValues(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
                } else {
                    PaddingValues(horizontal = 16.dp, vertical = 16.dp)
                },
            )
        }
    }
}
