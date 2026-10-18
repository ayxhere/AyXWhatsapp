package ayx.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream

/**
 * "Edit image with AI" — HONEST on-device editing, no cloud, no fake AI.
 * Auto enhance + filters + brightness/contrast/saturation sliders + rotate/flip
 * are applied for real with [ColorMatrix] to the bitmap that gets posted.
 * Nothing here claims a cloud model; the label in the UI says "on-device".
 */
enum class AiEditFilter(val label: String) { ORIGINAL("Original"), VIVID("Vivid"), MONO("Mono"), WARM("Warm"), COOL("Cool") }

/** Pure function: apply filter + sliders + rotation + flip to [src]. */
fun applyImageEdits(src: Bitmap, filter: AiEditFilter, brightness: Float, contrast: Float, saturation: Float, rotationDeg: Float, flip: Boolean, vignette: Boolean = false): Bitmap {
    val m = ColorMatrix()
    // Filter preset first (as its own matrix), then the user's sliders on top.
    val preset = ColorMatrix()
    when (filter) {
        AiEditFilter.ORIGINAL -> { /* identity */ }
        AiEditFilter.VIVID -> { preset.setSaturation(1.4f); preset.postConcat(contrastMatrix(1.1f, 0f)) }
        AiEditFilter.MONO -> { preset.setSaturation(0f) }
        AiEditFilter.WARM -> { preset.postConcat(tintMatrix(1.08f, 1.0f, 0.92f)) }
        AiEditFilter.COOL -> { preset.postConcat(tintMatrix(0.92f, 1.0f, 1.08f)) }
    }
    m.postConcat(preset)
    // Saturation slider.
    val sat = ColorMatrix(); sat.setSaturation(saturation); m.postConcat(sat)
    // Contrast + brightness sliders (contrast pivots around 128).
    m.postConcat(contrastMatrix(contrast, brightness))
    val filtered = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(filtered)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    paint.colorFilter = ColorMatrixColorFilter(m)
    c.drawBitmap(src, 0f, 0f, paint)
    if (vignette) {
        // Darken the corners — radial gradient, transparent centre → dark edges.
        val cx = src.width / 2f; val cy = src.height / 2f
        val radius = maxOf(src.width, src.height) * 0.75f
        val grad = android.graphics.RadialGradient(cx, cy, radius,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(150, 0, 0, 0)),
            floatArrayOf(0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        val vp = Paint().apply { shader = grad }
        c.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), vp)
    }
    if (rotationDeg == 0f && !flip) return filtered
    val mx = Matrix()
    if (flip) mx.postScale(-1f, 1f, filtered.width / 2f, filtered.height / 2f)
    if (rotationDeg != 0f) mx.postRotate(rotationDeg, filtered.width / 2f, filtered.height / 2f)
    val out = Bitmap.createBitmap(filtered, 0, 0, filtered.width, filtered.height, mx, true)
    if (out != filtered) filtered.recycle()
    return out
}

/**
 * Parse a free-text AI prompt into REAL on-device edits (keyword-mapped).
 * Returns null when no known keyword matches — caller must show a hint and apply NOTHING (never fake an edit).
 * Supported words: brighter, warm/sunset/golden, black & white/mono, vivid, cool, dark, contrast, fade, saturate, vignette.
 */
data class PromptEdit(val filter: AiEditFilter, val brightness: Float, val contrast: Float, val saturation: Float, val vignette: Boolean)

fun parseAiPrompt(prompt: String): PromptEdit? {
    val p = prompt.lowercase()
    var matched = false
    var filter = AiEditFilter.ORIGINAL
    var brightness = 0f; var contrast = 1f; var saturation = 1f; var vignette = false
    fun has(vararg keys: String) = keys.any { p.contains(it) }
    if (has("black and white", "black & white", "b&w", "mono", "grayscale", "greyscale", "black-and-white")) { filter = AiEditFilter.MONO; matched = true }
    else if (has("vivid", "vibrant", "colourful", "colorful", "punchy")) { filter = AiEditFilter.VIVID; matched = true }
    else if (has("warm", "sunset", "golden", "orange")) { filter = AiEditFilter.WARM; matched = true }
    else if (has("cool", "cold", "winter")) { filter = AiEditFilter.COOL; matched = true }
    if (has("bright", "brighter", "brightness")) { brightness += 25f; matched = true }
    if (has("dark", "darker", "dim", "moody")) { brightness -= 25f; matched = true }
    if (has("contrast", "sharp")) { contrast = 1.3f; matched = true }
    if (has("fade", "faded", "vintage")) { saturation = 0.6f; contrast = 0.9f; matched = true }
    if (has("saturat")) { saturation = 1.5f; matched = true }
    if (has("vignette")) { vignette = true; matched = true }
    if (!matched) return null
    return PromptEdit(filter, brightness, contrast, saturation, vignette)
}

private fun contrastMatrix(contrast: Float, brightness: Float): ColorMatrix {
    // out = contrast * in + (128 * (1 - contrast) + brightness)
    val t = 128f * (1f - contrast) + brightness
    return ColorMatrix(floatArrayOf(
        contrast, 0f, 0f, 0f, t,
        0f, contrast, 0f, 0f, t,
        0f, 0f, contrast, 0f, t,
        0f, 0f, 0f, 1f, 0f
    ))
}

private fun tintMatrix(r: Float, g: Float, b: Float): ColorMatrix {
    return ColorMatrix(floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))
}

private fun decodeCapped(ctx: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val biggest = maxOf(bounds.outWidth, bounds.outHeight)
        while (biggest / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) { null }
}

/**
 * Edit sheet: live preview + filter chips + Auto enhance + sliders + rotate/flip.
 * [onDone] receives the edited JPEG bytes (quality 92) — the editor posts
 * THESE bytes, so what you see is what gets posted.
 */
@Composable
fun AiImageEditSheet(ctx: Context, sourceUri: Uri, onDone: (ByteArray) -> Unit, onCancel: () -> Unit) {
    val src = remember(sourceUri) { decodeCapped(ctx, sourceUri) }
    var filter by remember { mutableStateOf(AiEditFilter.ORIGINAL) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var flip by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var promptHint by remember { mutableStateOf<String?>(null) }
    var promptApplied by remember { mutableStateOf<String?>(null) }
    var vignette by remember { mutableStateOf(false) }
    fun applyPrompt(text: String) {
        val parsed = parseAiPrompt(text)
        if (parsed == null) { promptHint = "No matching keyword — try: brighter, warm sunset, black & white, vivid, cool, dark, contrast, fade, vignette"; promptApplied = null }
        else { filter = parsed.filter; brightness = parsed.brightness; contrast = parsed.contrast; saturation = parsed.saturation; vignette = parsed.vignette; promptHint = null; promptApplied = "Applied: " + text }
    }
    val preview = remember(src, filter, brightness, contrast, saturation, rotation, flip, vignette) {
        if (src == null) null else applyImageEdits(src, filter, brightness, contrast, saturation, rotation, flip, vignette)
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Edit image with AI", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("AI edit (on-device, prompt-mapped) — no cloud", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        val bmp = preview ?: return@TextButton
                        busy = true
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        busy = false
                        onDone(out.toByteArray())
                    }, enabled = preview != null && !busy) { Text("Apply", color = Color(0xFF4EA1FF)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                    val bmp = preview
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize())
                    else Text("Preview unavailable", color = Color.White)
                }
                Spacer(Modifier.height(10.dp))
                // AI prompt: free text → keyword-mapped REAL edits (never faked).
                OutlinedTextField(promptText, { promptText = it }, placeholder = { Text("AI prompt — e.g. warm sunset", color = Color.White.copy(alpha = 0.5f)) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.4f), unfocusedBorderColor = Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Brighter", "Warm sunset", "Black & white", "Vivid", "Cool").forEach { chip ->
                        FilterChip(selected = false, onClick = { promptText = chip; applyPrompt(chip) }, label = { Text(chip) })
                    }
                    FilterChip(selected = false, onClick = { applyPrompt(promptText) }, label = { Text("Apply prompt") })
                }
                if (promptHint != null) Text(promptHint!!, color = Color(0xFFFFB84D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                if (promptApplied != null) Text(promptApplied!!, color = Color(0xFF7ED957), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiEditFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                    FilterChip(selected = false, onClick = {
                        // One-tap Auto enhance: gentle lift on all three sliders.
                        brightness = 8f; contrast = 1.15f; saturation = 1.2f; filter = AiEditFilter.ORIGINAL
                    }, label = { Text("✨ Auto enhance") })
                }
                Spacer(Modifier.height(6.dp))
                EditSlider("Brightness", brightness, -50f..50f, { brightness = it })
                EditSlider("Contrast", contrast, 0.5f..1.5f, { contrast = it })
                EditSlider("Saturation", saturation, 0f..2f, { saturation = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { rotation = (rotation + 90f) % 360f }) { Text("Rotate 90°", color = Color.White) }
                    TextButton(onClick = { flip = !flip }) { Text(if (flip) "Unflip" else "Flip", color = Color.White) }
                    TextButton(onClick = { filter = AiEditFilter.ORIGINAL; brightness = 0f; contrast = 1f; saturation = 1f; rotation = 0f; flip = false; vignette = false; promptText = ""; promptHint = null; promptApplied = null }) { Text("Reset", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun EditSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(String.format("%.2f", value), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
    }
}
