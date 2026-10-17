package ayx.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Premium sticker system — ALL art is ORIGINAL, drawn fresh in code.
 *
 * No sticker art, characters, pack names, or images are copied from
 * WhatsApp, Telegram, or any other app. Every sticker is a vector
 * drawing (android.graphics Canvas) of an original character, with
 * its own colour / shape language, in 10 expressions.
 *
 * - 10 original packs x 10 expressions = 100 stickers.
 * - Animated with Compose infiniteTransition (cheap: graphicsLayer /
 *   alpha / offset only, never layout size).
 * - Outgoing stickers are sent as ordinary PNG IMAGES via the existing
 *   GatewayClient.sendMedia path (type "image") — never the sticker
 *   protocol, so any recipient sees a normal image.
 * - Incoming Baileys sticker messages (mediaType "sticker", WebP) render
 *   as images in chat bubbles (see MessageBubble handling).
 * - Custom pack: user adds up to 10 gallery images, persisted, sent as
 *   images too.
 */

// ---------------------------------------------------------------------------
// Model
// ---------------------------------------------------------------------------

/** The 10 expressions every pack has. */
enum class StickerExpression(val label: String) {
    HAPPY("Happy"),
    LOVE("Love"),
    LAUGH("Laugh"),
    ANGRY("Angry"),
    CRY("Cry"),
    WOW("Wow"),
    SLEEP("Sleep"),
    COOL("Cool"),
    SAD("Sad"),
    PARTY("Party"),
}

/** Ear / head-shape language per character. */
enum class EarStyle {
    BLOB,      // round blob, two tiny nubs
    CAT,       // pointed triangles
    CHICK,     // top tuft + beak mouth
    FROG,      // eye bumps on top
    ROBOT,     // antenna + side bolts
    FOX,       // tall pointed ears + cheek tufts
    PENGUIN,   // no ears, tall oval body
    DINO,      // back spikes
    GHOST,     // wavy bottom
    PANDA,     // round black ears
}

/** One original character pack. Colours are Compose colours; the drawing
 *  converts them to android.graphics ints. */
data class StickerPackDef(
    val id: String,
    val name: String,
    val tagline: String,
    val body: Color,
    val belly: Color,
    val accent: Color,
    val ears: EarStyle,
)

/** The full catalogue: 10 original characters x 10 expressions. */
object StickerCatalog {
    val packs: List<StickerPackDef> = listOf(
        StickerPackDef("blobbo", "Blobbo", "Squishy mint blob", Color(0xFF7ED6A8), Color(0xFFDFF7E9), Color(0xFF1F8A5B), EarStyle.BLOB),
        StickerPackDef("kitto", "Kitto", "Cheeky orange cat", Color(0xFFFFB347), Color(0xFFFFE6C4), Color(0xFFB45F06), EarStyle.CAT),
        StickerPackDef("pip", "Pip", "Tiny yellow chick", Color(0xFFFFE066), Color(0xFFFFF6C9), Color(0xFFF08C00), EarStyle.CHICK),
        StickerPackDef("frogo", "Frogo", "Chill green frog", Color(0xFF8BC34A), Color(0xFFEAF7D3), Color(0xFF33691E), EarStyle.FROG),
        StickerPackDef("bolt", "Bolt", "Little steel robot", Color(0xFF90A4AE), Color(0xFFECEFF1), Color(0xFF1565C0), EarStyle.ROBOT),
        StickerPackDef("foxy", "Foxy", "Sly orange fox", Color(0xFFFF8A5C), Color(0xFFFFE3D3), Color(0xFF9C4221), EarStyle.FOX),
        StickerPackDef("waddles", "Waddles", "Plump penguin", Color(0xFF5C7CFA), Color(0xFFF1F3F5), Color(0xFF1C2B4A), EarStyle.PENGUIN),
        StickerPackDef("dax", "Dax", "Baby green dino", Color(0xFF66BB6A), Color(0xFFE8F5E9), Color(0xFF1B5E20), EarStyle.DINO),
        StickerPackDef("boo", "Boo", "Friendly ghost", Color(0xFFF3F0FF), Color(0xFFFFFFFF), Color(0xFF5F3DC4), EarStyle.GHOST),
        StickerPackDef("pao", "Pao", "Sleepy panda", Color(0xFFFFFFFF), Color(0xFFF1F3F5), Color(0xFF212529), EarStyle.PANDA),
    )

    val expressions: List<StickerExpression> = StickerExpression.entries

    fun pack(id: String): StickerPackDef? = packs.firstOrNull { it.id == id }
}

// ---------------------------------------------------------------------------
// Vector drawing — ONE implementation, used by the Compose preview, the
// picker, the chat bubble AND the off-screen PNG export.
// ---------------------------------------------------------------------------

private fun fillPaint(argb: Int, alpha: Int = 255): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = argb
        this.alpha = alpha
    }

private fun strokePaint(argb: Int, width: Float, alpha: Int = 255): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = argb
        this.alpha = alpha
    }

private fun heartPath(cx: Float, cy: Float, s: Float): Path {
    // s = half-width of the heart.
    val p = Path()
    p.moveTo(cx, cy + s * 0.9f)
    p.cubicTo(cx - s * 1.25f, cy - s * 0.05f, cx - s * 0.62f, cy - s * 1.15f, cx, cy - s * 0.28f)
    p.cubicTo(cx + s * 0.62f, cy - s * 1.15f, cx + s * 1.25f, cy - s * 0.05f, cx, cy + s * 0.9f)
    p.close()
    return p
}

private fun sparklePath(cx: Float, cy: Float, s: Float): Path {
    // 4-point sparkle.
    val p = Path()
    p.moveTo(cx, cy - s)
    p.quadTo(cx + s * 0.18f, cy - s * 0.18f, cx + s, cy)
    p.quadTo(cx + s * 0.18f, cy + s * 0.18f, cx, cy + s)
    p.quadTo(cx - s * 0.18f, cy + s * 0.18f, cx - s, cy)
    p.quadTo(cx - s * 0.18f, cy - s * 0.18f, cx, cy - s)
    p.close()
    return p
}

private fun tearPath(cx: Float, cy: Float, s: Float): Path {
    // Teardrop, point on top.
    val p = Path()
    p.moveTo(cx, cy - s)
    p.cubicTo(cx + s * 0.7f, cy - s * 0.1f, cx + s * 0.55f, cy + s * 0.75f, cx, cy + s)
    p.cubicTo(cx - s * 0.55f, cy + s * 0.75f, cx - s * 0.7f, cy - s * 0.1f, cx, cy - s)
    p.close()
    return p
}

/**
 * Draws one sticker onto an android.graphics.Canvas.
 * @param phase 0..1 animation phase (drives floating extras).
 */
fun drawStickerArt(
    canvas: Canvas,
    pack: StickerPackDef,
    expression: StickerExpression,
    sizePx: Int,
    phase: Float,
) {
    val size = sizePx.toFloat().coerceAtLeast(1f)
    val u = size / 100f
    val cx = 50f * u
    val cy = 53f * u
    val r = 35f * u
    val body = pack.body.toArgb()
    val belly = pack.belly.toArgb()
    val accent = pack.accent.toArgb()
    val dark = 0xFF2B2B2B.toInt()
    val white = 0xFFFFFFFF.toInt()
    val pink = 0xFFFF8FAB.toInt()
    val red = 0xFFFF4D6D.toInt()
    val blue = 0xFF4DABF7.toInt()
    val yellow = 0xFFFFD43B.toInt()

    // --- head extras (behind body) ---
    when (pack.ears) {
        EarStyle.CAT, EarStyle.FOX -> {
            val earH = if (pack.ears == EarStyle.FOX) 22f * u else 18f * u
            val earW = 13f * u
            val earPaint = fillPaint(body)
            val innerPaint = fillPaint(pink, 220)
            for (sign in listOf(-1f, 1f)) {
                val ex = cx + sign * 21f * u
                val p = Path()
                p.moveTo(ex - earW, cy - r + 8f * u)
                p.lineTo(ex, cy - r - earH)
                p.lineTo(ex + earW, cy - r + 8f * u)
                p.close()
                canvas.drawPath(p, earPaint)
                val p2 = Path()
                p2.moveTo(ex - earW * 0.5f, cy - r + 6f * u)
                p2.lineTo(ex, cy - r - earH * 0.55f)
                p2.lineTo(ex + earW * 0.5f, cy - r + 6f * u)
                p2.close()
                canvas.drawPath(p2, innerPaint)
            }
        }
        EarStyle.CHICK -> {
            val tuft = strokePaint(accent, 3.2f * u)
            canvas.drawLine(cx - 4f * u, cy - r + 2f * u, cx - 8f * u, cy - r - 8f * u, tuft)
            canvas.drawLine(cx, cy - r + 2f * u, cx, cy - r - 10f * u, tuft)
            canvas.drawLine(cx + 4f * u, cy - r + 2f * u, cx + 8f * u, cy - r - 8f * u, tuft)
        }
        EarStyle.FROG -> {
            val bump = fillPaint(body)
            canvas.drawCircle(cx - 19f * u, cy - r + 1f * u, 10f * u, bump)
            canvas.drawCircle(cx + 19f * u, cy - r + 1f * u, 10f * u, bump)
        }
        EarStyle.ROBOT -> {
            val ant = strokePaint(accent, 3f * u)
            canvas.drawLine(cx, cy - r, cx, cy - r - 12f * u, ant)
            canvas.drawCircle(cx, cy - r - 14f * u, 4.5f * u, fillPaint(yellow))
            canvas.drawCircle(cx - r - 2f * u, cy, 4f * u, fillPaint(accent))
            canvas.drawCircle(cx + r + 2f * u, cy, 4f * u, fillPaint(accent))
        }
        EarStyle.DINO -> {
            val spike = fillPaint(pack.accent.toArgb())
            for (i in 0..2) {
                val sx = cx - 18f * u + i * 16f * u
                val p = Path()
                p.moveTo(sx - 7f * u, cy - r + 6f * u)
                p.lineTo(sx, cy - r - 10f * u)
                p.lineTo(sx + 7f * u, cy - r + 6f * u)
                p.close()
                canvas.drawPath(p, spike)
            }
        }
        EarStyle.PANDA -> {
            canvas.drawCircle(cx - 24f * u, cy - r + 6f * u, 10f * u, fillPaint(dark))
            canvas.drawCircle(cx + 24f * u, cy - r + 6f * u, 10f * u, fillPaint(dark))
        }
        EarStyle.BLOB -> {
            canvas.drawCircle(cx - 20f * u, cy - r + 4f * u, 5f * u, fillPaint(body))
            canvas.drawCircle(cx + 20f * u, cy - r + 4f * u, 5f * u, fillPaint(body))
        }
        EarStyle.PENGUIN, EarStyle.GHOST -> { /* no ears */ }
    }

    // --- body ---
    if (pack.ears == EarStyle.GHOST) {
        val p = Path()
        p.moveTo(cx - r, cy + 8f * u)
        p.addArc(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f)
        // wavy bottom edge
        val waveY = cy + 8f * u
        val step = r * 2f / 4f
        var x = cx + r
        for (i in 0..3) {
            p.quadTo(x - step / 2f, waveY + 9f * u, x - step, waveY)
            x -= step
        }
        p.close()
        canvas.drawPath(p, fillPaint(body))
    } else if (pack.ears == EarStyle.PENGUIN) {
        canvas.drawOval(RectF(cx - r * 0.96f, cy - r * 1.06f, cx + r * 0.96f, cy + r * 1.06f), fillPaint(body))
    } else {
        canvas.drawCircle(cx, cy, r, fillPaint(body))
    }

    // --- belly ---
    val bellyOval = RectF(cx - 20f * u, cy + 6f * u, cx + 20f * u, cy + 33f * u)
    canvas.drawOval(bellyOval, fillPaint(belly, 235))
    if (pack.ears == EarStyle.FOX) {
        // cheek tufts
        val tuft = fillPaint(body)
        for (sign in listOf(-1f, 1f)) {
            val p = Path()
            val tx = cx + sign * (r - 2f * u)
            p.moveTo(tx, cy + 6f * u)
            p.lineTo(tx + sign * 8f * u, cy + 14f * u)
            p.lineTo(tx, cy + 20f * u)
            p.close()
            canvas.drawPath(p, tuft)
        }
    }

    // --- party hat (PARTY only) ---
    if (expression == StickerExpression.PARTY) {
        val hat = Path()
        hat.moveTo(cx - 13f * u, cy - r + 2f * u)
        hat.lineTo(cx, cy - r - 22f * u)
        hat.lineTo(cx + 13f * u, cy - r + 2f * u)
        hat.close()
        canvas.drawPath(hat, fillPaint(0xFFFF8787.toInt()))
        canvas.drawCircle(cx, cy - r - 22f * u, 4f * u, fillPaint(yellow))
        canvas.drawCircle(cx - 6f * u, cy - r - 10f * u, 2.2f * u, fillPaint(white))
        canvas.drawCircle(cx + 5f * u, cy - r - 14f * u, 2.2f * u, fillPaint(blue))
    }

    // --- eyes ---
    val ex1 = cx - 14f * u
    val ex2 = cx + 14f * u
    val ey = if (pack.ears == EarStyle.FROG) cy - r + 1f * u else cy - 7f * u
    val eyeStroke = strokePaint(dark, 3.4f * u)
    val eyeFill = fillPaint(dark)
    val highlight = fillPaint(white)
    when (expression) {
        StickerExpression.HAPPY, StickerExpression.LAUGH -> {
            for (ex in listOf(ex1, ex2)) {
                canvas.drawArc(RectF(ex - 6.5f * u, ey - 4f * u, ex + 6.5f * u, ey + 6f * u), 180f, 180f, false, eyeStroke)
            }
        }
        StickerExpression.LOVE -> {
            for (ex in listOf(ex1, ex2)) {
                canvas.drawPath(heartPath(ex, ey + 1f * u, 6.5f * u), fillPaint(red))
            }
        }
        StickerExpression.ANGRY -> {
            // slanted eye lines + angled brows
            for (ex in listOf(ex1, ex2)) {
                canvas.drawLine(ex - 5f * u, ey + 3f * u, ex + 5f * u, ey - 3f * u, eyeStroke)
            }
            canvas.drawLine(ex1 - 7f * u, ey - 12f * u, ex1 + 6f * u, ey - 7f * u, strokePaint(dark, 3.6f * u))
            canvas.drawLine(ex2 - 6f * u, ey - 7f * u, ex2 + 7f * u, ey - 12f * u, strokePaint(dark, 3.6f * u))
        }
        StickerExpression.CRY, StickerExpression.SLEEP -> {
            for (ex in listOf(ex1, ex2)) {
                canvas.drawArc(RectF(ex - 6.5f * u, ey - 2f * u, ex + 6.5f * u, ey + 8f * u), 0f, 180f, false, eyeStroke)
            }
        }
        StickerExpression.WOW -> {
            for (ex in listOf(ex1, ex2)) {
                canvas.drawCircle(ex, ey, 7.5f * u, eyeFill)
                canvas.drawCircle(ex - 2f * u, ey - 2.5f * u, 2.2f * u, highlight)
            }
            canvas.drawLine(ex1 - 7f * u, ey - 13f * u, ex1 + 7f * u, ey - 13f * u, strokePaint(dark, 3f * u))
            canvas.drawLine(ex2 - 7f * u, ey - 13f * u, ex2 + 7f * u, ey - 13f * u, strokePaint(dark, 3f * u))
        }
        StickerExpression.COOL -> {
            // sunglasses
            val glass = fillPaint(dark)
            val frame = RectF(ex1 - 9f * u, ey - 6f * u, ex1 + 9f * u, ey + 5f * u)
            val frame2 = RectF(ex2 - 9f * u, ey - 6f * u, ex2 + 9f * u, ey + 5f * u)
            canvas.drawRoundRect(frame, 4f * u, 4f * u, glass)
            canvas.drawRoundRect(frame2, 4f * u, 4f * u, glass)
            canvas.drawLine(ex1 + 9f * u, ey - 1f * u, ex2 - 9f * u, ey - 1f * u, strokePaint(dark, 3f * u))
            canvas.drawLine(ex1 - 6f * u, ey - 2f * u, ex1 - 2f * u, ey + 1f * u, strokePaint(white, 2f * u, 200))
            canvas.drawLine(ex2 - 6f * u, ey - 2f * u, ex2 - 2f * u, ey + 1f * u, strokePaint(white, 2f * u, 200))
        }
        StickerExpression.SAD -> {
            for (ex in listOf(ex1, ex2)) {
                canvas.drawArc(RectF(ex - 6f * u, ey - 4f * u, ex + 6f * u, ey + 6f * u), 180f, 180f, false, eyeStroke)
            }
            // raised-inner brows
            canvas.drawLine(ex1 - 6f * u, ey - 13f * u, ex1 + 5f * u, ey - 9f * u, strokePaint(dark, 3f * u))
            canvas.drawLine(ex2 - 5f * u, ey - 9f * u, ex2 + 6f * u, ey - 13f * u, strokePaint(dark, 3f * u))
        }
        StickerExpression.PARTY -> {
            for (ex in listOf(ex1, ex2)) {
                canvas.drawPath(sparklePath(ex, ey, 7f * u), fillPaint(yellow))
                canvas.drawCircle(ex, ey, 2f * u, fillPaint(dark))
            }
        }
    }

    // --- blush (warm expressions) ---
    if (expression == StickerExpression.HAPPY || expression == StickerExpression.LOVE ||
        expression == StickerExpression.LAUGH || expression == StickerExpression.PARTY ||
        expression == StickerExpression.CRY
    ) {
        val blush = fillPaint(pink, 110)
        canvas.drawCircle(cx - 24f * u, cy + 9f * u, 5.5f * u, blush)
        canvas.drawCircle(cx + 24f * u, cy + 9f * u, 5.5f * u, blush)
    }

    // --- mouth ---
    val my = cy + 13f * u
    val mouthStroke = strokePaint(dark, 3.2f * u)
    val mouthFill = fillPaint(dark)
    when (expression) {
        StickerExpression.HAPPY -> {
            canvas.drawArc(RectF(cx - 9f * u, my - 4f * u, cx + 9f * u, my + 7f * u), 0f, 180f, false, mouthStroke)
        }
        StickerExpression.LOVE -> {
            canvas.drawPath(heartPath(cx, my + 1f * u, 6f * u), fillPaint(red))
        }
        StickerExpression.LAUGH -> {
            canvas.drawOval(RectF(cx - 9f * u, my - 3f * u, cx + 9f * u, my + 9f * u), mouthFill)
            canvas.drawArc(RectF(cx - 6f * u, my + 2f * u, cx + 6f * u, my + 9f * u), 0f, 180f, false, fillPaint(pink))
        }
        StickerExpression.ANGRY -> {
            canvas.drawArc(RectF(cx - 8f * u, my + 1f * u, cx + 8f * u, my + 10f * u), 180f, 180f, false, mouthStroke)
        }
        StickerExpression.CRY -> {
            val w = Path()
            w.moveTo(cx - 9f * u, my + 4f * u)
            w.quadTo(cx - 4.5f * u, my - 2f * u, cx, my + 4f * u)
            w.quadTo(cx + 4.5f * u, my - 2f * u, cx + 9f * u, my + 4f * u)
            canvas.drawPath(w, mouthStroke)
        }
        StickerExpression.WOW -> {
            canvas.drawCircle(cx, my + 2f * u, 5.5f * u, mouthFill)
        }
        StickerExpression.SLEEP -> {
            canvas.drawLine(cx - 4f * u, my + 3f * u, cx + 4f * u, my + 3f * u, mouthStroke)
        }
        StickerExpression.COOL -> {
            val s = Path()
            s.moveTo(cx - 9f * u, my + 3f * u)
            s.quadTo(cx, my + 8f * u, cx + 9f * u, my - 1f * u)
            canvas.drawPath(s, mouthStroke)
        }
        StickerExpression.SAD -> {
            canvas.drawArc(RectF(cx - 8f * u, my + 1f * u, cx + 8f * u, my + 10f * u), 180f, 180f, false, mouthStroke)
        }
        StickerExpression.PARTY -> {
            canvas.drawOval(RectF(cx - 10f * u, my - 3f * u, cx + 10f * u, my + 10f * u), mouthFill)
            canvas.drawArc(RectF(cx - 7f * u, my + 3f * u, cx + 7f * u, my + 10f * u), 0f, 180f, false, fillPaint(pink))
        }
    }

    // --- chick beak (replaces mouth shape for Pip) ---
    if (pack.ears == EarStyle.CHICK && expression != StickerExpression.LOVE) {
        val beak = Path()
        beak.moveTo(cx - 6f * u, my)
        beak.lineTo(cx + 6f * u, my)
        beak.lineTo(cx, my + 6f * u)
        beak.close()
        canvas.drawPath(beak, fillPaint(0xFFF08C00.toInt()))
    }

    // --- floating extras, driven by phase ---
    when (expression) {
        StickerExpression.LOVE -> {
            val h1 = (phase * 10f * u) % (12f * u)
            canvas.drawPath(heartPath(cx - 28f * u, cy - 28f * u - h1, 5f * u), fillPaint(red, 220))
            canvas.drawPath(heartPath(cx + 27f * u, cy - 24f * u - ((phase * 8f * u) % (10f * u)), 4f * u), fillPaint(red, 190))
        }
        StickerExpression.CRY -> {
            val fall = phase * 10f * u
            canvas.drawPath(tearPath(ex1 - 2f * u, ey + 12f * u + fall, 4.5f * u), fillPaint(blue, 210))
            canvas.drawPath(tearPath(ex2 + 2f * u, ey + 12f * u + ((phase * 7f * u) % (10f * u)), 4f * u), fillPaint(blue, 190))
        }
        StickerExpression.LAUGH -> {
            canvas.drawPath(tearPath(ex1 - 8f * u, ey + 6f * u, 3.5f * u), fillPaint(blue, 200))
            canvas.drawPath(tearPath(ex2 + 8f * u, ey + 6f * u, 3.5f * u), fillPaint(blue, 200))
        }
        StickerExpression.ANGRY -> {
            val vein = strokePaint(red, 3f * u)
            val vx = cx + 30f * u
            val vy = cy - 16f * u
            canvas.drawLine(vx - 6f * u, vy, vx + 6f * u, vy, vein)
            canvas.drawLine(vx, vy - 6f * u, vx, vy + 6f * u, vein)
            canvas.drawLine(vx - 4f * u, vy - 4f * u, vx + 4f * u, vy + 4f * u, vein)
            canvas.drawLine(vx - 4f * u, vy + 4f * u, vx + 4f * u, vy - 4f * u, vein)
        }
        StickerExpression.SAD -> {
            canvas.drawPath(tearPath(cx + 30f * u, cy - 12f * u + (phase * 6f * u), 4.5f * u), fillPaint(blue, 200))
        }
        StickerExpression.WOW -> {
            val tw = if (phase < 0.5f) (phase * 2f) else (2f - phase * 2f)
            canvas.drawPath(sparklePath(cx - 30f * u, cy - 22f * u, 5f * u), fillPaint(yellow, (120 + (tw * 135f)).toInt().coerceIn(0, 255)))
            canvas.drawPath(sparklePath(cx + 30f * u, cy - 18f * u, 4f * u), fillPaint(yellow, (255 - (tw * 135f)).toInt().coerceIn(0, 255)))
        }
        StickerExpression.SLEEP -> {
            val zp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF5F3DC4.toInt()
                textSize = 15f * u
                typeface = Typeface.DEFAULT_BOLD
            }
            val drift = phase * 8f * u
            zp.alpha = 200
            canvas.drawText("Z", cx + 22f * u, cy - 22f * u - drift, zp)
            zp.textSize = 11f * u
            zp.alpha = 140
            canvas.drawText("z", cx + 30f * u, cy - 14f * u - drift * 0.6f, zp)
            zp.textSize = 8f * u
            zp.alpha = 90
            canvas.drawText("z", cx + 36f * u, cy - 8f * u - drift * 0.3f, zp)
        }
        StickerExpression.COOL -> {
            canvas.drawPath(sparklePath(cx + 30f * u, cy - 20f * u, 4.5f * u), fillPaint(yellow, 220))
        }
        StickerExpression.PARTY -> {
            val confetti = listOf(red, blue, yellow, 0xFF51CF66.toInt(), 0xFFCC5DE8.toInt())
            val spots = listOf(
                -30f to -30f, -22f to -36f, 26f to -30f, 32f to -22f, -32f to -12f, 30f to -6f,
            )
            for ((i, s) in spots.withIndex()) {
                val yy = s.second * u + ((phase * 6f * u + i * 2f * u) % (8f * u))
                canvas.drawCircle(cx + s.first * u, cy + yy, 2.4f * u, fillPaint(confetti[i % confetti.size]))
            }
        }
        StickerExpression.HAPPY -> {
            canvas.drawPath(sparklePath(cx - 29f * u, cy - 20f * u, 4f * u), fillPaint(yellow, 200))
            canvas.drawPath(sparklePath(cx + 29f * u, cy - 24f * u, 3.2f * u), fillPaint(yellow, 170))
        }
    }
}


// ---------------------------------------------------------------------------
// Compose rendering of the same vector art
// ---------------------------------------------------------------------------

/** Pure sticker drawing: no animation state of its own. */
@Composable
fun StickerArt(
    pack: StickerPackDef,
    expression: StickerExpression,
    modifier: Modifier = Modifier,
    phase: Float = 0.3f,
) {
    Canvas(modifier) {
        drawIntoCanvas { c ->
            drawStickerArt(c.nativeCanvas, pack, expression, size.width.toInt().coerceAtLeast(1), phase)
        }
    }
}

/** Animated sticker: one infiniteTransition per sticker instance.
 *  Cheap — only graphicsLayer scale/rotation + the phase value change,
 *  never layout size. Pass animate=false for static thumbnails. */
@Composable
fun AnimatedStickerArt(
    pack: StickerPackDef,
    expression: StickerExpression,
    modifier: Modifier = Modifier,
    animate: Boolean = StickerSettings.animationEnabled,
) {
    if (animate) {
        val t = rememberInfiniteTransition(label = "stickerAnim")
        val phase by t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "stickerPhase",
        )
        val bounce by t.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "stickerBounce",
        )
        val wiggle by t.animateFloat(
            initialValue = -2.5f,
            targetValue = 2.5f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "stickerWiggle",
        )
        StickerArt(
            pack = pack,
            expression = expression,
            modifier = modifier.graphicsLayer {
                scaleX = bounce
                scaleY = bounce
                rotationZ = if (expression == StickerExpression.ANGRY || expression == StickerExpression.PARTY) wiggle else 0f
            },
            phase = phase,
        )
    } else {
        StickerArt(pack = pack, expression = expression, modifier = modifier, phase = 0.3f)
    }
}

// ---------------------------------------------------------------------------
// Settings (own prefs — kept separate from UiAnimationSettings on purpose)
// ---------------------------------------------------------------------------

object StickerSettings {
    private const val PREFS = "ayx_stickers"
    private const val KEY_ANIM = "animation_enabled"

    var animationEnabled: Boolean = true
        private set

    fun init(ctx: Context) {
        animationEnabled = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANIM, true)
    }

    fun setAnimationEnabled(ctx: Context, enabled: Boolean) {
        animationEnabled = enabled
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ANIM, enabled).apply()
    }
}

// ---------------------------------------------------------------------------
// Custom pack — up to 10 user gallery images, persisted
// ---------------------------------------------------------------------------

data class CustomSticker(val id: String, val uri: String, val addedAt: Long)

object CustomStickerStore {
    const val MAX = 10
    private const val PREFS = "ayx_stickers_custom"
    private const val KEY_LIST = "custom_list"

    fun list(ctx: Context): List<CustomSticker> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIST, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                CustomSticker(o.optString("id"), o.optString("uri"), o.optLong("addedAt"))
            }.filter { it.id.isNotBlank() && it.uri.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** @return true if added, false if the pack is already full. */
    fun add(ctx: Context, uriString: String): Boolean {
        val current = list(ctx).toMutableList()
        if (current.size >= MAX) return false
        current.add(CustomSticker("custom-${System.currentTimeMillis()}", uriString, System.currentTimeMillis()))
        save(ctx, current)
        return true
    }

    fun remove(ctx: Context, id: String) {
        val current = list(ctx).filterNot { it.id == id }
        save(ctx, current)
    }

    private fun save(ctx: Context, items: List<CustomSticker>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().put("id", it.id).put("uri", it.uri).put("addedAt", it.addedAt))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }
}

/** One gallery image from the custom pack, downsampled so scrolling stays cheap. */
@Composable
fun CustomStickerImage(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp = remember(uri) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(Uri.parse(uri))?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = "custom sticker",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.EmojiEmotions, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Sent-sticker tracking: lets OUR OWN sent stickers render ANIMATED in
// chat (the server echo is a plain image message — the local record maps
// its timestamp window back to the pack + expression).
// ---------------------------------------------------------------------------

object StickerSentStore {
    private const val PREFS = "ayx_stickers_sent"
    private const val WINDOW_BEFORE_MS = 8_000L
    private const val WINDOW_AFTER_MS = 120_000L
    private const val PRUNE_AFTER_MS = 600_000L

    fun record(ctx: Context, sentTs: Long, packId: String, expressionName: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(sentTs.toString(), "$packId:$expressionName")
        // prune entries older than 10 min
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MS
        for ((k, _) in prefs.all) {
            val ts = k.toLongOrNull() ?: continue
            if (ts < cutoff) editor.remove(k)
        }
        editor.apply()
    }

    /** Find the sticker sent nearest to [ts] inside the echo window. */
    fun lookup(ctx: Context, ts: Long): Pair<StickerPackDef, StickerExpression>? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var best: Pair<StickerPackDef, StickerExpression>? = null
        var bestDelta = Long.MAX_VALUE
        for ((k, v) in prefs.all) {
            val sentTs = k.toLongOrNull() ?: continue
            if (sentTs < ts - WINDOW_AFTER_MS || sentTs > ts + WINDOW_BEFORE_MS) continue
            val delta = kotlin.math.abs(sentTs - ts)
            if (delta >= bestDelta) continue
            val value = v as? String ?: continue
            val parts = value.split(":")
            if (parts.size != 2) continue
            val pack = StickerCatalog.pack(parts[0]) ?: continue
            val expr = runCatching { StickerExpression.valueOf(parts[1]) }.getOrNull() ?: continue
            best = pack to expr
            bestDelta = delta
        }
        return best
    }
}

/** Top-level helper used by ChatDetail: which animated sticker (if any)
 *  does a chat message at timestamp [ts] correspond to? */
fun stickerRefFor(ctx: Context, ts: Long): Pair<StickerPackDef, StickerExpression>? =
    StickerSentStore.lookup(ctx, ts)

// ---------------------------------------------------------------------------
// Off-screen PNG export — the SAME drawStickerArt, no Compose view needed.
// ---------------------------------------------------------------------------

/** Renders one sticker to a PNG byte array (transparent background). */
fun renderStickerPng(
    pack: StickerPackDef,
    expression: StickerExpression,
    sizePx: Int = 512,
): ByteArray? = runCatching {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawStickerArt(canvas, pack, expression, sizePx, 0.3f)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    out.toByteArray()
}.getOrNull()

// ---------------------------------------------------------------------------
// Host: everything ChatDetail needs, in one bundle
// ---------------------------------------------------------------------------

data class StickerHost(
    val customStickers: List<CustomSticker>,
    val onSendSticker: (StickerPackDef, StickerExpression) -> Unit,
    val onSendCustomSticker: (CustomSticker) -> Unit,
    val onAddCustomSticker: () -> Unit,
    val onRemoveCustomSticker: (String) -> Unit,
    val stickerRefFor: (Long) -> Pair<StickerPackDef, StickerExpression>?,
)


// ---------------------------------------------------------------------------
// Sticker picker — WhatsApp-style panel: pack tabs + grid.
// Plain Column panel (NOT ModalBottomSheet — avoids experimental APIs).
// ---------------------------------------------------------------------------

@Composable
fun StickerPicker(
    host: StickerHost,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var selectedPack by remember { mutableStateOf(StickerCatalog.packs.first()) }
    var showCustom by remember { mutableStateOf(false) }
    var animOn by remember { mutableStateOf(StickerSettings.animationEnabled) }

    // ONE shared transition for the whole grid — 10 canvases share one
    // phase value instead of running 10 separate infiniteTransitions.
    val transition = rememberInfiniteTransition(label = "stickerPicker")
    val pickerPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "pickerPhase",
    )
    val gridPhase = if (animOn) pickerPhase else 0.3f

    Column(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        // header: title + animation toggle + close
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showCustom) "My Stickers (${host.customStickers.size}/${CustomStickerStore.MAX})" else selectedPack.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("Animate", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(
                checked = animOn,
                onCheckedChange = {
                    animOn = it
                    StickerSettings.setAnimationEnabled(ctx, it)
                },
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "close stickers") }
        }

        // pack tabs: 10 original packs + custom pack
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(StickerCatalog.packs) { pack ->
                val selected = !showCustom && selectedPack.id == pack.id
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                        .clickable { selectedPack = pack; showCustom = false }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    StickerArt(pack, StickerExpression.HAPPY, Modifier.size(36.dp), phase = 0.3f)
                    Text(
                        pack.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                val selected = showCustom
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        )
                        .clickable { showCustom = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, "my stickers", tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "Mine ${host.customStickers.size}/${CustomStickerStore.MAX}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        if (showCustom) {
            // ---- custom pack grid ----
            if (host.customStickers.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No custom stickers yet", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Add up to 10 images from your gallery — they send as images too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = host.onAddCustomSticker) { Text("+ Add stickers") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // "+ Add" tile (only while under the cap)
                    if (host.customStickers.size < CustomStickerStore.MAX) {
                        item {
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                    .clickable { host.onAddCustomSticker() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Add, "add", tint = MaterialTheme.colorScheme.primary)
                                    Text(
                                        "${host.customStickers.size}/${CustomStickerStore.MAX}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    items(host.customStickers, key = { it.id }) { cs ->
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .clickable { host.onSendCustomSticker(cs) },
                            contentAlignment = Alignment.Center,
                        ) {
                            CustomStickerImage(cs.uri, Modifier.fillMaxSize().padding(6.dp))
                            IconButton(
                                onClick = { host.onRemoveCustomSticker(cs.id) },
                                modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f), CircleShape),
                            ) {
                                Icon(Icons.Filled.Close, "remove", tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        } else {
            // ---- built-in pack grid: 10 expressions ----
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(StickerCatalog.expressions, key = { it.name }) { expr ->
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .clickable { host.onSendSticker(selectedPack, expr) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (animOn) {
                            StickerArt(selectedPack, expr, Modifier.fillMaxSize().padding(8.dp), phase = gridPhase)
                        } else {
                            StickerArt(selectedPack, expr, Modifier.fillMaxSize().padding(8.dp), phase = 0.3f)
                        }
                    }
                }
            }
        }

        // pack tagline
        if (!showCustom) {
            Text(
                "${selectedPack.name} — ${selectedPack.tagline} · tap a sticker to send it as an image",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
    }
}
