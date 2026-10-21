package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack as AutoMirroredArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack as FilledArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image as FilledImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon Style — Settings → Appearance → Icon Style.
 *
 * Two styles: Default (the app's Material icons) and Nothing (dot-matrix
 * glyphs from the user-supplied "NOTHING" Telegram-format icon pack).
 * Only icon slots whose meaning genuinely exists in BOTH the pack and this
 * app are remapped (see [NothingGlyphMap]); every other slot keeps its
 * Material icon in both styles. Persisted like the other appearance
 * settings (own SharedPreferences file, Compose-observable state).
 */
object IconStyleSettings {
    const val DEFAULT = 0
    const val NOTHING = 1

    val style = mutableStateOf(DEFAULT)
    val STYLE_LABELS = listOf("Default", "Nothing")

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_icon_style", Context.MODE_PRIVATE)
        prefs = p
        val v = p.getInt("style", DEFAULT)
        style.value = if (v in DEFAULT..NOTHING) v else DEFAULT
    }

    fun setStyle(v: Int) {
        style.value = v
        prefs?.edit()?.putInt("style", v)?.apply()
    }
}

/**
 * Pack-slot name per Material vector, for the slots that genuinely match.
 * Deliberately NOT mapped (no honest glyph in the pack): Pause (the pack
 * has no pause glyph at all), CheckCircle, Visibility (open eye), the
 * Status ring (the pack's circle glyph is a broken solid square), Speed,
 * Checklist, MusicNote, Logout, GraphicEq and the decorative editor icons.
 * The pack's sticker and pin glyphs have no slot here either: the sticker
 * feature was removed from this app, and there is no pin action.
 */
private object NothingGlyphMap {
    private val MAP: Map<ImageVector, String> = mapOf(
        Icons.AutoMirrored.Filled.AutoMirroredArrowBack to "back",
        Icons.Filled.FilledArrowBack to "back",
        Icons.AutoMirrored.Filled.Send to "send",
        Icons.AutoMirrored.Filled.Reply to "reply",
        Icons.AutoMirrored.Filled.ArrowForward to "forward",
        Icons.Filled.Share to "share",
        Icons.Filled.Done to "check",
        Icons.Filled.Check to "check",
        Icons.Filled.DoneAll to "seen",
        Icons.Filled.Close to "close",
        Icons.Filled.Clear to "close",
        Icons.Filled.Search to "search",
        Icons.Filled.Settings to "settings",
        Icons.Filled.Edit to "edit",
        Icons.Filled.ContentCopy to "copy",
        Icons.Filled.Delete to "delete",
        Icons.Filled.DeleteOutline to "delete",
        Icons.Filled.Add to "add",
        Icons.Filled.PhotoCamera to "camera",
        Icons.Filled.FilledImage to "gallery",
        Icons.Filled.Videocam to "videocam",
        Icons.Filled.VideoFile to "videocam",
        Icons.Filled.AttachFile to "attach",
        Icons.Filled.MoreVert to "more",
        Icons.Filled.NotificationsOff to "mute",
        Icons.Filled.Notifications to "notify",
        Icons.Filled.VisibilityOff to "eyeoff",
        Icons.Filled.Lock to "lock",
        Icons.Filled.Download to "download",
        Icons.Filled.PlayArrow to "play",
        Icons.Filled.EmojiEmotions to "smile",
        Icons.Filled.Translate to "translate",
        Icons.Filled.Info to "info",
        Icons.Filled.Home to "home",
        Icons.Filled.Chat to "chat",
        Icons.Filled.KeyboardArrowRight to "arrowright",
        Icons.Filled.KeyboardArrowUp to "arrowup",
        Icons.Filled.KeyboardArrowDown to "arrowdown",
        Icons.Filled.Schedule to "clock",
        Icons.Filled.Refresh to "refresh",
        Icons.Filled.Shield to "shield",
        Icons.Filled.QrCode2 to "qr",
        Icons.Filled.Crop to "crop",
        Icons.Filled.Language to "language",
        Icons.Filled.Person to "person",
        Icons.Filled.Favorite to "heart",
        Icons.Filled.AutoAwesome to "sparkle",
        Icons.Filled.Palette to "palette",
        Icons.Filled.Tune to "tune",
        Icons.Filled.InsertDriveFile to "file",
        Icons.Filled.Block to "block",
        Icons.Filled.Mic to "mic",
    )

    fun glyphFor(vector: ImageVector): String? = MAP[vector]
}

/** Decodes the embedded base64 glyphs once and caches the bitmaps. */
object NothingGlyphStore {
    private val cache = HashMap<String, Bitmap?>()

    fun bitmap(name: String): Bitmap? {
        synchronized(cache) {
            if (cache.containsKey(name)) return cache[name]
            val decoded = runCatching {
                val b64 = NothingGlyphData.GLYPHS[name] ?: return@runCatching null
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            cache[name] = decoded
            return decoded
        }
    }
}

/**
 * The pack's glyphs are dot-matrix in two inks: WHITE dots (the shape) and
 * pure-RED dots (Nothing's accent). A plain SRC_IN tint would flatten the
 * red away; rendering the PNG as-is would make the white dots invisible on
 * light backgrounds. This color matrix instead maps the white/grey dots to
 * the caller's tint color and leaves the red accent dots red:
 *   out = (tint * G) + red * (R - G)   per channel, alpha scaled by tint.
 * White (1,1,1) -> tint; red (1,0,0) -> red; grey (g,g,g) -> tint * g.
 */
private fun nothingTintMatrix(tint: Color): ColorMatrix = ColorMatrix(
    floatArrayOf(
        1f, tint.red - 1f, 0f, 0f, 0f,
        0f, tint.green, 0f, 0f, 0f,
        0f, tint.blue, 0f, 0f, 0f,
        0f, 0f, 0f, tint.alpha, 0f,
    )
)

/** Renders one pack glyph by name (used by AyxIcon and the style preview). */
@Composable
fun NothingGlyph(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val bmp = remember(name) { NothingGlyphStore.bitmap(name) } ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.colorMatrix(nothingTintMatrix(tint)),
        filterQuality = FilterQuality.None,
    )
}

/**
 * Drop-in replacement for material3 [Icon] with the same signature: when
 * the Nothing style is active AND the vector has a mapped glyph, renders
 * the dot-matrix glyph (tinted to the caller's tint, red accents kept);
 * otherwise renders the Material icon unchanged.
 */
@Composable
fun AyxIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val glyph = if (IconStyleSettings.style.value == IconStyleSettings.NOTHING) {
        NothingGlyphMap.glyphFor(imageVector)
    } else {
        null
    }
    if (glyph == null) {
        Icon(imageVector, contentDescription, modifier, tint)
        return
    }
    val bmp = remember(glyph) { NothingGlyphStore.bitmap(glyph) }
    if (bmp == null) {
        Icon(imageVector, contentDescription, modifier, tint)
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            colorFilter = ColorFilter.colorMatrix(nothingTintMatrix(tint)),
            filterQuality = FilterQuality.None,
        )
    }
}
