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
import android.util.Base64
import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client-side AI settings (SharedPreferences "ayx_ai_settings").
 * The user pastes their OWN OpenRouter key here — it is NEVER hardcoded
 * anywhere in the app and never leaves the device except in the
 * Authorization header of the user's own OpenRouter requests.
 */
object AiImgSettings {
    private const val PREFS = "ayx_ai_settings"
    const val DEFAULT_MODEL = "google/gemini-2.5-flash-image"
    fun getKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("openrouter_key", "")?.trim() ?: ""
    fun getModel(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("image_model", DEFAULT_MODEL)?.trim()
            .takeUnless { it.isNullOrBlank() } ?: DEFAULT_MODEL
    fun save(ctx: Context, key: String, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("openrouter_key", key.trim())
            .putString("image_model", model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }
}

data class ImgEditResult(val bitmap: Bitmap?, val error: String?)

/**
 * REAL image-to-image edit via OpenRouter chat completions.
 * Sends the downscaled JPEG + the user's prompt; parses the returned image
 * (choices[0].message.images[0].image_url.url, with fallbacks for inline
 * content blocks). Runs on Dispatchers.IO — never blocks the UI.
 * This costs the user's own OpenRouter credits.
 */
suspend fun openRouterImageEdit(key: String, model: String, jpegBytes: ByteArray, prompt: String): ImgEditResult =
    withContext(Dispatchers.IO) {
        try {
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val imgPart = JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            val body = JSONObject()
                .put("model", model)
                .put("modalities", JSONArray().put("image").put("text"))
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(imgPart))))
            val c = (URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 15000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("HTTP-Referer", "ayx.whatsapp")
                setRequestProperty("X-Title", "AyXWhatsApp")
            }
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) return@withContext ImgEditResult(null, "Invalid API key (HTTP $code) — check your OpenRouter key in AI Settings.")
            if (code == 402) return@withContext ImgEditResult(null, "Insufficient OpenRouter credits (HTTP 402) — top up at openrouter.ai.")
            if (code == 429) return@withContext ImgEditResult(null, "Rate limited (HTTP 429) — wait a minute and retry.")
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(txt).optString("error").ifBlank { JSONObject(txt).optJSONObject("error")?.optString("message") } }.getOrNull()
                return@withContext ImgEditResult(null, "OpenRouter error (HTTP $code)${if (!msg.isNullOrBlank()) ": ${msg.take(160)}" else ""}")
            }
            val o = JSONObject(txt)
            val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return@withContext ImgEditResult(null, "Bad response: no choices in reply.")
            // primary: choices[0].message.images[0].image_url.url
            var dataUrl: String? = msg.optJSONArray("images")?.optJSONObject(0)?.optJSONObject("image_url")?.optString("url")
            // fallback: inline image_url blocks inside content[]
            if (dataUrl.isNullOrBlank()) {
                val content = msg.optJSONArray("content")
                if (content != null) for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val u = part.optJSONObject("image_url")?.optString("url")
                    if (!u.isNullOrBlank()) { dataUrl = u; break }
                }
            }
            // fallback: content as a plain string data-URL
            if (dataUrl.isNullOrBlank()) {
                val s = msg.optString("content")
                if (s.startsWith("data:image")) dataUrl = s
            }
            if (dataUrl.isNullOrBlank()) return@withContext ImgEditResult(null, "The model returned no image — try a clearer prompt (e.g. \"change the background to a beach at sunset\").")
            val payload = dataUrl.substringAfter("base64,", "")
            if (payload.isBlank()) return@withContext ImgEditResult(null, "The model returned an image URL instead of image data — cannot apply.")
            val bytes = try { Base64.decode(payload, Base64.DEFAULT) } catch (_: Exception) {
                return@withContext ImgEditResult(null, "Could not decode the returned image.")
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext ImgEditResult(null, "Could not decode the returned image.")
            ImgEditResult(bmp, null)
        } catch (e: java.net.SocketTimeoutException) {
            ImgEditResult(null, "Request timed out — check your connection and retry.")
        } catch (e: Exception) {
            ImgEditResult(null, "Network error: ${(e.message ?: "failed").take(120)}")
        }
    }

/** Downscale to max [maxSide] px and JPEG-compress (q85) for the API request. */
fun downscaleJpeg(ctx: Context, uri: Uri, maxSide: Int = 1024, quality: Int = 85): ByteArray? {
    return try {
        val bmp = decodeCapped(ctx, uri, maxSide) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        runCatching { bmp.recycle() }
        out.toByteArray()
    } catch (_: Exception) { null }
}

/** Shared OpenRouter key + model fields (Settings page and the in-sheet dialog use the same prefs). */
@Composable
fun AiSettingsFields(ctx: Context, dark: Boolean = true, onSaved: () -> Unit) {
    val t: Color = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val tDim: Color = if (dark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    val tFaint: Color = if (dark) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val border = if (dark) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val borderF = if (dark) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary
    var key by remember { mutableStateOf(AiImgSettings.getKey(ctx)) }
    var model by remember { mutableStateOf(AiImgSettings.getModel(ctx)) }
    var showKey by remember { mutableStateOf(false) }
    var savedTick by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = t, unfocusedTextColor = t, cursorColor = t,
        focusedBorderColor = borderF, unfocusedBorderColor = border,
        focusedLabelColor = tDim, unfocusedLabelColor = tDim)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(Lang.s("aiedit_privacy"),
            style = MaterialTheme.typography.bodySmall, color = tDim)
        OutlinedTextField(key, { key = it }, label = { Text(Lang.s("aiedit_key_label"), color = tDim) }, singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) Lang.s("aiedit_hide") else Lang.s("aiedit_show"), color = Color(0xFF4EA1FF)) }
            },
            colors = fieldColors, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text(Lang.s("aiedit_model_label"), color = tDim) }, singleLine = true,
            placeholder = { Text(AiImgSettings.DEFAULT_MODEL, color = tFaint) },
            colors = fieldColors, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                AiImgSettings.save(ctx, key, model)
                savedTick = true
                Toast.makeText(ctx, Lang.s("toast_ai_saved"), Toast.LENGTH_SHORT).show()
                onSaved()
            }) { Text(Lang.s("save")) }
            if (savedTick) Text(Lang.s("aiedit_saved_tick"), color = Color(0xFF7ED957), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun AiSettingsDialog(ctx: Context, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = Color(0xFF161616)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Lang.s("aiedit_settings"), color = Color.White, style = MaterialTheme.typography.titleMedium)
                AiSettingsFields(ctx, onSaved = {})
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text(Lang.s("close"), color = Color(0xFF4EA1FF)) }
                }
            }
        }
    }
}

enum class AiEditFilter(val label: String) { ORIGINAL("Original"), VIVID("Vivid"), MONO("Mono"), WARM("Warm"), COOL("Cool") }

/** Pure function: apply filter + sliders + rotation + flip to [src]. */
fun applyImageEdits(src: Bitmap, filter: AiEditFilter, brightness: Float, contrast: Float, saturation: Float, rotationDeg: Float, flip: Boolean, vignette: Boolean = false): Bitmap {
    val m = ColorMatrix()
    val preset = ColorMatrix()
    when (filter) {
        AiEditFilter.ORIGINAL -> { /* identity */ }
        AiEditFilter.VIVID -> { preset.setSaturation(1.4f); preset.postConcat(contrastMatrix(1.1f, 0f)) }
        AiEditFilter.MONO -> { preset.setSaturation(0f) }
        AiEditFilter.WARM -> { preset.postConcat(tintMatrix(1.08f, 1.0f, 0.92f)) }
        AiEditFilter.COOL -> { preset.postConcat(tintMatrix(0.92f, 1.0f, 1.08f)) }
    }
    m.postConcat(preset)
    val sat = ColorMatrix(); sat.setSaturation(saturation); m.postConcat(sat)
    m.postConcat(contrastMatrix(contrast, brightness))
    val filtered = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(filtered)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    paint.colorFilter = ColorMatrixColorFilter(m)
    c.drawBitmap(src, 0f, 0f, paint)
    if (vignette) {
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

private fun contrastMatrix(contrast: Float, brightness: Float): ColorMatrix {
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
 * Edit sheet, two honest sections:
 * 1. "AI Edit (OpenRouter)" — REAL image-to-image edit with the user's own
 *    OpenRouter key (never hardcoded). Result preview + Apply / Save / Retry / Cancel.
 * 2. "Filters (on-device)" — the previous local tools (filters, sliders,
 *    rotate/flip). Labelled as on-device, never as AI.
 * [onDone] receives JPEG bytes (q92) — the editor posts THESE bytes.
 */
@Composable
fun AiImageEditSheet(ctx: Context, sourceUri: Uri, onDone: (ByteArray) -> Unit, onCancel: () -> Unit, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hasKey = remember { AiImgSettings.getKey(ctx).isNotBlank() }
    // ---- real AI state ----
    var promptText by remember { mutableStateOf("") }
    var promptError by remember { mutableStateOf<String?>(null) }
    var generating by remember { mutableStateOf(false) }
    var genError by remember { mutableStateOf<String?>(null) }
    var genResult by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    // ---- on-device filter state ----
    val src = remember(sourceUri) { decodeCapped(ctx, sourceUri) }
    var filter by remember { mutableStateOf(AiEditFilter.ORIGINAL) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var flip by remember { mutableStateOf(false) }
    var vignette by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val preview = remember(src, filter, brightness, contrast, saturation, rotation, flip, vignette) {
        if (src == null) null else applyImageEdits(src, filter, brightness, contrast, saturation, rotation, flip, vignette)
    }
    fun runGenerate() {
        val p = promptText.trim()
        if (p.isEmpty()) { promptError = "Type a prompt first — e.g. \"change the background to a beach at sunset\"."; return }
        promptError = null; genError = null
        generating = true
        scope.launch {
            val key = AiImgSettings.getKey(ctx)
            val model = AiImgSettings.getModel(ctx)
            if (key.isBlank()) { generating = false; genError = Lang.s("aiedit_no_key_err"); return@launch }
            val jpeg = downscaleJpeg(ctx, sourceUri)
            if (jpeg == null) { generating = false; genError = "Could not read the image."; return@launch }
            val r = openRouterImageEdit(key, model, jpeg, p)
            generating = false
            if (r.bitmap != null) genResult = r.bitmap else genError = r.error ?: "Edit failed."
        }
    }
    fun resultBytes(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 92, out); return out.toByteArray()
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Subtle dark teal → charcoal gradient so the sheet doesn't look flat pure-black.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0E2A28), Color(0xFF101418), Color(0xFF08090B))))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text(Lang.s("cancel"), color = Color.White) }
                    Text(Lang.s("aiedit_edit_image"), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(64.dp))
                }
                // ---------- SECTION 1: real AI edit ----------
                Text(Lang.s("aiedit_section_ai"), color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(Lang.s("aiedit_section_ai_desc"),
                    color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                if (!hasKey) {
                    Text(Lang.s("aiedit_no_key"),
                        color = Color(0xFFFFB84D), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = onOpenSettings) { Text(Lang.s("aiedit_open_settings")) }
                } else {
                    OutlinedTextField(promptText, { promptText = it; promptError = null },
                        placeholder = { Text("Change the background to a beautiful Indian village at sunset.", color = Color.White.copy(alpha = 0.45f)) },
                        label = { Text(Lang.s("aiedit_describe"), color = Color.White.copy(alpha = 0.6f)) }, minLines = 2, maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                            focusedBorderColor = Color.White.copy(alpha = 0.4f), unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedLabelColor = Color.White.copy(alpha = 0.7f), unfocusedLabelColor = Color.White.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth())
                    if (promptError != null) Text(promptError!!, color = Color(0xFFFFB84D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Warm sunset", "Beach background", "Black & white", "Vivid colors").forEach { chip ->
                            FilterChip(selected = false, onClick = { promptText = chip }, label = { Text(chip) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { runGenerate() }, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
                        if (generating) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White); Spacer(Modifier.size(8.dp)); Text(Lang.s("aiedit_generating")) }
                        else Text(Lang.s("aiedit_generate"))
                    }
                    if (genError != null) {
                        Text(genError!!, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { runGenerate() }, enabled = !generating) { Text(Lang.s("retry"), color = Color(0xFF4EA1FF)) }
                    }
                    val res = genResult
                    if (res != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(Lang.s("aiedit_result_hint"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Image(res.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 320.dp))
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDone(resultBytes(res)) }, modifier = Modifier.weight(1f)) { Text(Lang.s("aiedit_apply")) }
                            TextButton(onClick = {
                                saving = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { MediaSaver.save(ctx, resultBytes(res), "ayx-ai-edit-${System.currentTimeMillis()}.jpg", "image") }
                                    saving = false
                                    Toast.makeText(ctx, if (ok) Lang.s("aiedit_saved_to") else Lang.s("aiedit_save_failed"), Toast.LENGTH_SHORT).show()
                                }
                            }, enabled = !saving) { Text(if (saving) Lang.s("aiedit_saving") else Lang.s("save"), color = Color(0xFF4EA1FF)) }
                            TextButton(onClick = { genResult = null; genError = null }) { Text(Lang.s("discard"), color = Color.White.copy(alpha = 0.7f)) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // ---------- SECTION 2: on-device filters ----------
                Text(Lang.s("aiedit_filters"), color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(Lang.s("aiedit_filters_desc"), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                    val bmp = preview
                    if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize()) else Text(Lang.s("preview_unavailable"), color = Color.White)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiEditFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                    FilterChip(selected = false, onClick = { brightness = 8f; contrast = 1.15f; saturation = 1.2f; filter = AiEditFilter.ORIGINAL }, label = { Text(Lang.s("aiedit_auto_enhance")) })
                    FilterChip(selected = vignette, onClick = { vignette = !vignette }, label = { Text(Lang.s("aiedit_vignette")) })
                }
                Spacer(Modifier.height(6.dp))
                EditSlider(Lang.s("aiedit_brightness"), brightness, -50f..50f, { brightness = it })
                EditSlider(Lang.s("aiedit_contrast"), contrast, 0.5f..1.5f, { contrast = it })
                EditSlider(Lang.s("aiedit_saturation"), saturation, 0f..2f, { saturation = it })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { rotation = (rotation + 90f) % 360f }) { Text(Lang.s("aiedit_rotate"), color = Color.White) }
                    TextButton(onClick = { flip = !flip }) { Text(if (flip) Lang.s("aiedit_unflip") else Lang.s("aiedit_flip"), color = Color.White) }
                    TextButton(onClick = { filter = AiEditFilter.ORIGINAL; brightness = 0f; contrast = 1f; saturation = 1f; rotation = 0f; flip = false; vignette = false }) { Text(Lang.s("aiedit_reset"), color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        val bmp = preview ?: return@Button
                        busy = true
                        onDone(resultBytes(bmp)); busy = false
                    }, enabled = preview != null && !busy) { Text(Lang.s("aiedit_apply_filters")) }
                }
                Spacer(Modifier.height(16.dp))
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
