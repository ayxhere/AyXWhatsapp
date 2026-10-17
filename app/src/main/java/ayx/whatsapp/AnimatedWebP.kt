package ayx.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated WebP encoder + muxer (pure Android, no native libwebp).
 *
 * WHY THIS EXISTS: premium stickers were sent as PNG *images* — WhatsApp
 * showed them with a bubble background + download button, no animation,
 * no sticker actions. Real WhatsApp stickers are animated WebP sent via
 * the sticker protocol ({ sticker: buffer }). This file turns the SAME
 * vector art from drawStickerArt() into one animated WebP (RIFF container)
 * so the sticker arrives as a REAL sticker: transparent, animated,
 * long-press shows sticker actions.
 *
 * HOW IT WORKS:
 * 1. Render N frames with drawStickerArt() at different animation phases
 *    (phase drives floating extras; bounce scale + wiggle rotation are
 *    applied with canvas transforms to match AnimatedStickerArt).
 * 2. Compress each frame with Bitmap.compress(WEBP) -> a still WebP file.
 * 3. Strip each still file down to its VP8/VP8L bitstream (the chunk
 *    payload, WITHOUT the 8-byte chunk header).
 * 4. Mux: RIFF + WEBP + VP8X (animation + alpha flags) + ANIM
 *    (transparent bg, loop 0 = infinite) + one ANMF per frame
 *    (full-canvas frame, duration ~100ms, disposal=clear + blending=
 *    overwrite so frames never ghost over each other).
 *
 * FORMAT NOTES (WebP container spec):
 * - Every chunk: 4-byte ASCII tag + 4-byte little-endian size + data +
 *   1 pad byte if size is odd (pad byte NOT counted in size).
 * - VP8X flags byte: bit 1 = alpha, bit 4 = animation -> 0x12.
 * - ANMF flags byte: bit 0 = disposal (1 = dispose to background),
 *   bit 1 = blending (1 = overwrite, no alpha blend) -> 0x03.
 * - ANMF frame data = VP8/VP8L bitstream WITHOUT its chunk header.
 *   The decoder sniffs the bitstream signature (VP8 starts with frame
 *   tag + 0x9D012A magic, VP8L starts with 0x2F), so either is fine.
 * - ANIM background color is all-zero (transparent) so byte order is moot.
 */

// ---------------------------------------------------------------------------
// Little-endian writers
// ---------------------------------------------------------------------------

private fun le16(v: Int, out: ByteArrayOutputStream) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
}

private fun le24(v: Int, out: ByteArrayOutputStream) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
    out.write((v shr 16) and 0xFF)
}

private fun le32(v: Int, out: ByteArrayOutputStream) {
    out.write(v and 0xFF)
    out.write((v shr 8) and 0xFF)
    out.write((v shr 16) and 0xFF)
    out.write((v shr 24) and 0xFF)
}

private fun le32At(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

/** One RIFF chunk: tag + LE size + data + pad byte when size is odd. */
private fun writeChunk(out: ByteArrayOutputStream, tag: String, data: ByteArray) {
    out.write(tag.toByteArray(Charsets.US_ASCII))
    le32(data.size, out)
    out.write(data)
    if (data.size % 2 == 1) out.write(0)
}

// ---------------------------------------------------------------------------
// Still-WebP -> VP8/VP8L bitstream
// ---------------------------------------------------------------------------

/**
 * From one complete still-WebP file, extract the VP8/VP8L bitstream
 * (the chunk PAYLOAD, without the 8-byte chunk header). Null if the
 * file is not a WebP or has no VP8/VP8L chunk.
 */
private fun extractFrameBitstream(webp: ByteArray): ByteArray? {
    if (webp.size < 12) return null
    if (String(webp, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    if (String(webp, 8, 4, Charsets.US_ASCII) != "WEBP") return null
    var off = 12
    while (off + 8 <= webp.size) {
        val tag = String(webp, off, 4, Charsets.US_ASCII)
        val size = le32At(webp, off + 4)
        if (size < 0 || off + 8 + size > webp.size) return null
        if (tag == "VP8 " || tag == "VP8L ") return webp.copyOfRange(off + 8, off + 8 + size)
        off += 8 + size + (size and 1)
    }
    return null
}

// ---------------------------------------------------------------------------
// Animated WebP muxer
// ---------------------------------------------------------------------------

/**
 * Muxes VP8/VP8L frame bitstreams into ONE animated WebP.
 * @param frames frame bitstreams (from [extractFrameBitstream]), all the
 *   same [width] x [height] canvas.
 * @param frameDurationMs per-frame duration in ms (24-bit field).
 * @param loopCount 0 = loop forever (WhatsApp stickers loop).
 * @return complete animated WebP bytes, or null on bad input.
 */
fun muxAnimatedWebP(
    frames: List<ByteArray>,
    width: Int,
    height: Int,
    frameDurationMs: Int,
    loopCount: Int = 0,
): ByteArray? {
    if (frames.isEmpty() || width <= 0 || height <= 0 || frameDurationMs <= 0) return null
    val body = ByteArrayOutputStream()
    body.write("WEBP".toByteArray(Charsets.US_ASCII))

    // VP8X: flags = animation (0x10) | alpha (0x02), 3 reserved bytes,
    // canvas width-1 + height-1 (24-bit each).
    val vp8x = ByteArrayOutputStream()
    vp8x.write(0x12)
    vp8x.write(0)
    vp8x.write(0)
    vp8x.write(0)
    le24(width - 1, vp8x)
    le24(height - 1, vp8x)
    writeChunk(body, "VP8X", vp8x.toByteArray())

    // ANIM: transparent background (4 zero bytes) + loop count (16-bit).
    val anim = ByteArrayOutputStream()
    anim.write(0)
    anim.write(0)
    anim.write(0)
    anim.write(0)
    le16(loopCount, anim)
    writeChunk(body, "ANIM", anim.toByteArray())

    // One ANMF per frame: full-canvas frame, disposal=clear (bit 0),
    // blending=overwrite (bit 1) -> 0x03, so frames never ghost.
    for (f in frames) {
        val anmf = ByteArrayOutputStream()
        le24(0, anmf) // frame x offset
        le24(0, anmf) // frame y offset
        le24(width - 1, anmf) // frame width - 1
        le24(height - 1, anmf) // frame height - 1
        le24(frameDurationMs, anmf)
        anmf.write(0x03)
        anmf.write(f)
        writeChunk(body, "ANMF", anmf.toByteArray())
    }

    val out = ByteArrayOutputStream()
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    // RIFF size = file size - 8 = body size (body starts with "WEBP").
    le32(body.size(), out)
    out.write(body.toByteArray())
    return out.toByteArray()
}

/**
 * Structural check: returns the number of ANMF frames if [bytes] is a
 * well-formed animated WebP (VP8X + ANIM + >=1 ANMF, all chunks within
 * bounds), else -1. Used to verify the muxer output before sending.
 * NOTE: BitmapFactory.decodeByteArray is NOT a valid check for animated
 * WebP (it decodes only the first frame / may fail) — never use it here.
 */
fun countAnimatedWebPFrames(bytes: ByteArray): Int {
    if (bytes.size < 12) return -1
    if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return -1
    if (String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return -1
    var off = 12
    var anmf = 0
    var hasVp8x = false
    var hasAnim = false
    while (off + 8 <= bytes.size) {
        val tag = String(bytes, off, 4, Charsets.US_ASCII)
        val size = le32At(bytes, off + 4)
        if (size < 0 || off + 8 + size > bytes.size) return -1
        when (tag) {
            "VP8X" -> hasVp8x = true
            "ANIM" -> hasAnim = true
            "ANMF" -> anmf++
        }
        off += 8 + size + (size and 1)
    }
    return if (hasVp8x && hasAnim && anmf > 0) anmf else -1
}

// ---------------------------------------------------------------------------
// Frame rendering (same art as AnimatedStickerArt, off-screen)
// ---------------------------------------------------------------------------

/** Renders one sticker's frames at [phase] 0..1 steps. Skips failed frames. */
private fun renderStickerFrames(
    pack: StickerPackDef,
    expression: StickerExpression,
    frameCount: Int,
    sizePx: Int,
    quality: Int,
): List<ByteArray> {
    val frames = mutableListOf<ByteArray>()
    for (i in 0 until frameCount) {
        val phase = i.toFloat() / frameCount.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Mirror the Compose animation: bounce scale (2 cycles per loop)
        // + wiggle rotation for ANGRY / PARTY (matches AnimatedStickerArt).
        val bounce = 1f + 0.04f * (0.5f - 0.5f * kotlin.math.cos(2 * PI * phase * 2).toFloat())
        val wiggle = if (expression == StickerExpression.ANGRY || expression == StickerExpression.PARTY) {
            2.5f * sin(2 * PI * phase * 2).toFloat()
        } else 0f
        canvas.save()
        canvas.translate(sizePx / 2f, sizePx / 2f)
        canvas.scale(bounce, bounce)
        canvas.rotate(wiggle)
        canvas.translate(-sizePx / 2f, -sizePx / 2f)
        drawStickerArt(canvas, pack, expression, sizePx, phase)
        canvas.restore()
        val out = ByteArrayOutputStream()
        @Suppress("DEPRECATION") // Bitmap.CompressFormat.WEBP = lossy VP8; still works.
        val ok = bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)
        if (ok) extractFrameBitstream(out.toByteArray())?.let { frames.add(it) }
        bmp.recycle()
    }
    return frames
}

/** One still WebP frame (used as the fallback when animated muxing fails). */
fun renderStaticStickerWebP(
    pack: StickerPackDef,
    expression: StickerExpression,
    sizePx: Int = 512,
): ByteArray? = runCatching {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawStickerArt(canvas, pack, expression, sizePx, 0.3f)
    val out = ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    bmp.compress(Bitmap.CompressFormat.WEBP, 85, out)
    bmp.recycle()
    out.toByteArray()
}.getOrNull()

/**
 * Renders one sticker as an ANIMATED WebP (loops forever).
 * Tries rich presets first, shrinking on size: WhatsApp stickers should
 * stay under ~512KB, so if the 8-frame cut is too big we drop to fewer
 * frames / lower quality instead of sending an oversized file.
 * Falls back to a single-frame static WebP — still sticker protocol,
 * never an image. Null only if every render path fails.
 */
fun renderAnimatedStickerWebP(
    pack: StickerPackDef,
    expression: StickerExpression,
    sizePx: Int = 512,
): ByteArray? {
    val presets = listOf(
        8 to 80,
        6 to 70,
        4 to 60,
    )
    for ((frameCount, quality) in presets) {
        val bits = runCatching { renderStickerFrames(pack, expression, frameCount, sizePx, quality) }.getOrNull()
            ?: continue
        if (bits.size < 2) continue
        val muxed = runCatching { muxAnimatedWebP(bits, sizePx, sizePx, 100) }.getOrNull()
            ?: continue
        // Must be a valid animated WebP with exactly the frames we muxed,
        // and small enough for WhatsApp's sticker size expectations.
        if (muxed.size <= 500_000 && countAnimatedWebPFrames(muxed) == bits.size) return muxed
    }
    return renderStaticStickerWebP(pack, expression, sizePx)
}

/**
 * Decodes a user gallery image (any format) into a static WebP sticker:
 * downsampled to fit 512x512, letterboxed (Fit — never cropped) on a
 * transparent canvas. Animated source images decode to their first
 * frame only (BitmapFactory limit — documented). Null on failure.
 */
fun uriToStickerWebP(ctx: Context, uriString: String): ByteArray? = runCatching {
    val uri = Uri.parse(uriString)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        ?: return@runCatching null
    val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val scale = min(512f / bmp.width, 512f / bmp.height)
    val dw = bmp.width * scale
    val dh = bmp.height * scale
    c.drawBitmap(
        bmp,
        Rect(0, 0, bmp.width, bmp.height),
        RectF((512f - dw) / 2f, (512f - dh) / 2f, (512f + dw) / 2f, (512f + dh) / 2f),
        null,
    )
    bmp.recycle()
    val baos = ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    out.compress(Bitmap.CompressFormat.WEBP, 85, baos)
    out.recycle()
    baos.toByteArray()
}.getOrNull()
