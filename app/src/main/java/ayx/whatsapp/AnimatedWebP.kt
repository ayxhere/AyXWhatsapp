package ayx.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.min

private const val TAG = "AnimatedWebP"

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
 * 1. Render N frames with drawStickerArt() at different animation phases,
 *    using the SAME shared stickerMotion() curve as AnimatedStickerArt
 *    (squash-and-stretch + bob + rotation + blink) so AyX and WhatsApp
 *    show the same motion.
 * 2. Compress each frame with Bitmap.compress() -> a still WebP file.
 *    On API 30+ we use WEBP_LOSSLESS: lossy VP8 frames CANNOT carry an
 *    alpha channel inside ANMF, so lossy frames would render opaque
 *    (transparency lost). VP8L (lossless) preserves alpha. On older
 *    devices we fall back to lossy WEBP (alpha may be lost there —
 *    documented, still a valid sticker).
 * 3. Extract each still file's COMPLETE VP8/VP8L sub-chunk, INCLUDING its
 *    8-byte chunk header ("VP8 "/"VP8L" + LE size). The previous version
 *    stripped the header and embedded the raw bitstream — WhatsApp's
 *    strict decoder rejects that (blurred placeholder + download button
 *    + "Can't view sticker information"). The spec requires the full
 *    sub-chunk inside ANMF.
 * 4. Mux: RIFF + WEBP + VP8X (animation + alpha flags) + ANIM
 *    (transparent bg, loop 0 = infinite) + one ANMF per frame
 *    (full-canvas frame, duration ~100ms, flags 0x05 = dispose to
 *    background + no-blend, so frames never ghost over each other).
 * 5. STRICT validation ([validateAnimatedWebP]) re-parses the muxed bytes
 *    before anything is sent. If validation fails we NEVER send the
 *    bytes — we fall back to a single-frame static WebP (Android-encoded,
 *    known-valid) via the sticker protocol.
 *
 * FORMAT NOTES (WebP container spec — field layout, not guesswork):
 * - Every chunk: 4-byte ASCII tag + 4-byte little-endian size + data +
 *   1 pad byte if size is odd (pad byte NOT counted in size).
 * - RIFF size = file size - 8.
 * - VP8X payload (10 bytes): flags byte, 3 reserved zero bytes,
 *   canvas width-1 (24-bit LE) + height-1 (24-bit LE).
 *   Flags: bit 1 (0x02) = animation, bit 4 (0x10) = alpha -> 0x12.
 * - ANIM payload (6 bytes): background colour (4 bytes BGRA, all-zero =
 *   transparent) + loop count (16-bit LE, 0 = infinite).
 * - ANMF chunk data = 16-byte frame header + frame data (the COMPLETE
 *   VP8/VP8L sub-chunk). Frame header: frame X offset (3B LE), frame Y
 *   offset (3B LE), frame width-1 (3B LE), frame height-1 (3B LE),
 *   duration ms (3B LE), flags (1B). (NOTE: the header is 16 bytes =
 *   3+3+3+3+3+1 — NOT 24; the field list is what the spec defines.)
 *   Flags byte: bits 0-1 = disposal method (0 = none, 1 = dispose to
 *   background — values 2/3 are RESERVED/INVALID), bit 2 = blending
 *   (0 = alpha-blend, 1 = no-blend), bits 3-7 = 0 (reserved).
 *   The previous code wrote 0x03: disposal bits = 0b11 = 3 = INVALID.
 *   Correct "clear to background + overwrite" = disposal 1 (0x01) +
 *   no-blend (1 << 2 = 0x04) = 0x05.
 */

// ---------------------------------------------------------------------------
// Little-endian writers / readers
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

private fun le16At(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun le24At(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16)

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

private fun tagAt(b: ByteArray, off: Int): String =
    String(b, off, 4, Charsets.US_ASCII)

// ---------------------------------------------------------------------------
// Still-WebP -> COMPLETE VP8/VP8L sub-chunk (header + payload)
// ---------------------------------------------------------------------------

/**
 * From one complete still-WebP file, extract the COMPLETE VP8/VP8L
 * sub-chunk (8-byte chunk header + payload). Null if the file is not a
 * WebP or has no VP8/VP8L chunk.
 *
 * The header MUST be kept: ANMF frame data is a complete VP8/VP8L chunk,
 * not a bare bitstream. Stripping it produced files WhatsApp rejects.
 */
private fun extractFrameChunk(webp: ByteArray): ByteArray? {
    if (webp.size < 12) return null
    if (tagAt(webp, 0) != "RIFF") return null
    if (tagAt(webp, 8) != "WEBP") return null
    var off = 12
    while (off + 8 <= webp.size) {
        val tag = tagAt(webp, off)
        val size = le32At(webp, off + 4)
        if (size < 0 || off + 8 + size > webp.size) return null
        if (tag == "VP8 " || tag == "VP8L") {
            // Keep the WHOLE sub-chunk: 8-byte header + payload.
            return webp.copyOfRange(off, off + 8 + size)
        }
        off += 8 + size + (size and 1)
    }
    return null
}

// ---------------------------------------------------------------------------
// Animated WebP muxer
// ---------------------------------------------------------------------------

/**
 * Muxes COMPLETE VP8/VP8L frame sub-chunks into ONE animated WebP.
 * @param frames frame sub-chunks (from [extractFrameChunk]), all the
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
    // Each frame must be a complete VP8/VP8L sub-chunk (header + payload).
    for (f in frames) {
        if (f.size < 8 + 1) return null
        val tag = tagAt(f, 0)
        if (tag != "VP8 " && tag != "VP8L") return null
        if (le32At(f, 4) + 8 != f.size && le32At(f, 4) + 8 + 1 != f.size) return null
    }
    val body = ByteArrayOutputStream()
    body.write("WEBP".toByteArray(Charsets.US_ASCII))

    // VP8X: flags = animation (bit 1 = 0x02) | alpha (bit 4 = 0x10) = 0x12,
    // 3 reserved bytes, canvas width-1 + height-1 (24-bit each).
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

    // One ANMF per frame: 16-byte header + complete VP8/VP8L sub-chunk.
    // Flags 0x05 = disposal-to-background (bits 0-1 = 01) + no-blend
    // (bit 2 = 1). The old 0x03 had disposal = 3 = RESERVED/INVALID.
    for (f in frames) {
        val anmf = ByteArrayOutputStream()
        le24(0, anmf) // frame x offset
        le24(0, anmf) // frame y offset
        le24(width - 1, anmf) // frame width - 1
        le24(height - 1, anmf) // frame height - 1
        le24(frameDurationMs, anmf)
        anmf.write(0x05)
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
 * STRICT validator: re-parses muxed animated WebP bytes against the
 * container spec. Returns true only if EVERY check passes:
 * - RIFF/WEBP magic, RIFF size + 8 == total length (no trailing garbage,
 *   no truncation), all chunks within bounds.
 * - First chunk is VP8X (10 bytes), flags include animation + alpha
 *   (0x12), canvas size matches [canvasWidth]/[canvasHeight].
 * - ANIM (6 bytes) present BEFORE the first ANMF.
 * - Exactly [expectedFrames] ANMF chunks, each: 16-byte header sane
 *   (x=0, y=0, w/h == canvas, duration > 0, flags valid: disposal <= 1,
 *   reserved bits 3-7 zero), frame data = complete VP8/VP8L sub-chunk
 *   with consistent size, and the VP8/VP8L inner bitstream's own
 *   width/height fields == canvas.
 *
 * Anything failing -> the bytes MUST NOT be sent (caller falls back to
 * a static WebP). BitmapFactory.decodeByteArray is NOT a valid check
 * for animated WebP (it decodes only the first frame / may fail) —
 * never use it here.
 */
fun validateAnimatedWebP(
    bytes: ByteArray,
    expectedFrames: Int,
    canvasWidth: Int,
    canvasHeight: Int,
): Boolean {
    if (bytes.size < 12 || expectedFrames <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return false
    if (tagAt(bytes, 0) != "RIFF") return false
    if (tagAt(bytes, 8) != "WEBP") return false
    if (le32At(bytes, 4) + 8 != bytes.size) return false

    var off = 12
    var chunkIndex = 0
    var seenVp8x = false
    var seenAnim = false
    var anmfCount = 0
    while (off + 8 <= bytes.size) {
        val tag = tagAt(bytes, off)
        val size = le32At(bytes, off + 4)
        if (size < 0 || off + 8 + size > bytes.size) return false
        val dataOff = off + 8
        when (tag) {
            "VP8X" -> {
                if (chunkIndex != 0) return false // VP8X must be first
                if (size != 10) return false
                val flags = bytes[dataOff].toInt() and 0xFF
                if (flags and 0x12 != 0x12) return false // animation + alpha
                if (bytes[dataOff + 1].toInt() != 0 || bytes[dataOff + 2].toInt() != 0 ||
                    bytes[dataOff + 3].toInt() != 0
                ) return false // reserved bytes must be zero
                if (le24At(bytes, dataOff + 4) + 1 != canvasWidth) return false
                if (le24At(bytes, dataOff + 7) + 1 != canvasHeight) return false
                seenVp8x = true
            }
            "ANIM" -> {
                if (!seenVp8x) return false
                if (size != 6) return false
                seenAnim = true
            }
            "ANMF" -> {
                if (!seenVp8x || !seenAnim) return false // ANIM must precede frames
                if (size < 16 + 8 + 1) return false // header + sub-chunk header + >=1 payload byte
                val frameX = le24At(bytes, dataOff)
                val frameY = le24At(bytes, dataOff + 3)
                val frameW = le24At(bytes, dataOff + 6) + 1
                val frameH = le24At(bytes, dataOff + 9) + 1
                val duration = le24At(bytes, dataOff + 12)
                val flags = bytes[dataOff + 15].toInt() and 0xFF
                if (frameX != 0 || frameY != 0) return false // we emit full-canvas frames
                if (frameW != canvasWidth || frameH != canvasHeight) return false
                if (duration <= 0) return false
                if (flags and 0x03 > 1) return false // disposal 2/3 = reserved
                if (flags and 0xF8 != 0) return false // reserved bits must be zero
                // Frame data = complete VP8/VP8L sub-chunk.
                val subOff = dataOff + 16
                val subTag = tagAt(bytes, subOff)
                if (subTag != "VP8 " && subTag != "VP8L") return false
                val subSize = le32At(bytes, subOff + 4)
                if (subSize < 1) return false
                // ANMF size = 16 header + 8 sub-header + payload (+1 pad if payload odd).
                if (size != 16 + 8 + subSize && size != 16 + 8 + subSize + 1) return false
                if (!validateFrameBitstream(bytes, subOff, subSize, canvasWidth, canvasHeight)) return false
                anmfCount++
            }
            // Other chunks (e.g. ICCP) are tolerated but must stay in bounds.
        }
        off += 8 + size + (size and 1)
        chunkIndex++
    }
    if (off != bytes.size) return false // trailing garbage
    return seenVp8x && seenAnim && anmfCount == expectedFrames
}

/**
 * Validates the inner VP8/VP8L bitstream's own dimension fields against
 * the canvas. The codec is read from the sub-chunk TAG (never sniffed
 * from the payload — a VP8 frame's first payload byte can coincidentally
 * equal 0x2F). VP8: 3-byte frame tag (bit 0 = 0 keyframe) + 3-byte magic
 * 0x9D012A + width/height (14-bit + 2 scale bits each). VP8L: 0x2F
 * signature + 32-bit LE field (14-bit width-1, 14-bit height-1, alpha
 * bit, 3-bit version).
 */
private fun validateFrameBitstream(
    bytes: ByteArray,
    subOff: Int,
    subSize: Int,
    canvasWidth: Int,
    canvasHeight: Int,
): Boolean {
    if (subSize < 10 || subOff + 8 + subSize > bytes.size) return false
    val tag = tagAt(bytes, subOff)
    val payloadOff = subOff + 8
    val payloadSize = subSize
    if (tag == "VP8L") {
        // VP8L
        if (payloadSize < 5) return false
        val v = le32At(bytes, payloadOff + 1)
        val w = (v and 0x3FFF) + 1
        val h = ((v shr 14) and 0x3FFF) + 1
        return w == canvasWidth && h == canvasHeight
    }
    if (tag != "VP8 ") return false
    // VP8: frame tag bit 0 must be 0 (keyframe), then magic 0x9D012A.
    if (bytes[payloadOff].toInt() and 0x01 != 0) return false
    if (payloadSize < 10) return false
    if ((bytes[payloadOff + 3].toInt() and 0xFF) != 0x9D ||
        (bytes[payloadOff + 4].toInt() and 0xFF) != 0x01 ||
        (bytes[payloadOff + 5].toInt() and 0xFF) != 0x2A
    ) return false
    val w = (bytes[payloadOff + 6].toInt() and 0xFF) or
        ((bytes[payloadOff + 7].toInt() and 0x3F) shl 8)
    val h = (bytes[payloadOff + 8].toInt() and 0xFF) or
        ((bytes[payloadOff + 9].toInt() and 0x3F) shl 8)
    return w == canvasWidth && h == canvasHeight
}

/**
 * Structural check: returns the number of ANMF frames if [bytes] is a
 * well-formed animated WebP (VP8X + ANIM + >=1 ANMF, all chunks within
 * bounds), else -1. Kept for quick checks; [validateAnimatedWebP] is
 * the strict gate used before sending.
 */
fun countAnimatedWebPFrames(bytes: ByteArray): Int {
    if (bytes.size < 12) return -1
    if (tagAt(bytes, 0) != "RIFF") return -1
    if (tagAt(bytes, 8) != "WEBP") return -1
    var off = 12
    var anmf = 0
    var hasVp8x = false
    var hasAnim = false
    while (off + 8 <= bytes.size) {
        val tag = tagAt(bytes, off)
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
// Frame rendering (same art + SAME motion as AnimatedStickerArt, off-screen)
// ---------------------------------------------------------------------------

/**
 * Compresses a bitmap to WebP, preserving alpha. On API 30+ uses
 * WEBP_LOSSLESS (VP8L, alpha kept). On older devices falls back to lossy
 * WEBP (VP8, alpha LOST — documented; still a valid sticker).
 */
private fun compressWebP(bmp: Bitmap, quality: Int, out: ByteArrayOutputStream): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, out)
    } else {
        @Suppress("DEPRECATION") // WEBP = lossy VP8; only used pre-API-30.
        bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)
    }
}

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
        // SAME motion curve as AnimatedStickerArt (shared stickerMotion).
        val motion = stickerMotion(phase, expression)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.translate(sizePx / 2f, sizePx / 2f + motion.offsetYFrac * sizePx)
        canvas.scale(motion.scaleX, motion.scaleY)
        canvas.rotate(motion.rotationDeg)
        canvas.translate(-sizePx / 2f, -sizePx / 2f)
        drawStickerArt(canvas, pack, expression, sizePx, phase, motion.blink)
        canvas.restore()
        val out = ByteArrayOutputStream()
        val ok = compressWebP(bmp, quality, out)
        if (ok) extractFrameChunk(out.toByteArray())?.let { frames.add(it) }
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
    drawStickerArt(canvas, pack, expression, sizePx, 0.3f, 1f)
    val out = ByteArrayOutputStream()
    compressWebP(bmp, 90, out)
    bmp.recycle()
    out.toByteArray()
}.getOrNull()

/**
 * Renders one sticker as an ANIMATED WebP (loops forever).
 * Tries rich presets first, shrinking on size: WhatsApp stickers should
 * stay under ~512KB, so if the 8-frame cut is too big we drop to fewer
 * frames, then to a smaller canvas, instead of sending an oversized file.
 *
 * The muxed bytes are STRICT-validated ([validateAnimatedWebP]) before
 * being returned. If validation fails — or every preset fails — we fall
 * back to a single-frame static WebP: still sticker protocol, never an
 * image, never unvalidated bytes. Null only if every render path fails.
 */
fun renderAnimatedStickerWebP(
    pack: StickerPackDef,
    expression: StickerExpression,
    sizePx: Int = 512,
): ByteArray? {
    val presets = listOf(
        8 to sizePx,
        6 to sizePx,
        4 to sizePx,
        4 to 384,
    )
    for ((frameCount, canvasSize) in presets) {
        val bits = runCatching { renderStickerFrames(pack, expression, frameCount, canvasSize, 90) }.getOrNull()
            ?: continue
        if (bits.size < 2) {
            Log.w(TAG, "sticker webp: preset $frameCount frames @ $canvasSize px yielded ${bits.size} frames, skipping")
            continue
        }
        val muxed = runCatching { muxAnimatedWebP(bits, canvasSize, canvasSize, 100) }.getOrNull()
            ?: continue
        if (muxed.size > 500_000) {
            Log.w(TAG, "sticker webp: preset $frameCount frames @ $canvasSize px too big (${muxed.size} bytes), shrinking")
            continue
        }
        if (!validateAnimatedWebP(muxed, bits.size, canvasSize, canvasSize)) {
            Log.w(TAG, "sticker webp: preset $frameCount frames @ $canvasSize px FAILED validation, trying next preset")
            continue
        }
        Log.i(TAG, "sticker webp: animated path, $frameCount frames @ $canvasSize px, ${muxed.size} bytes")
        return muxed
    }
    Log.w(TAG, "sticker webp: animated presets failed, static fallback")
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
    compressWebP(out, 90, baos)
    out.recycle()
    baos.toByteArray()
}.getOrNull()
