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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight as AutoMirroredKeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ArrowBack as FilledArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BlurLinear
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image as FilledImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon Style — Settings → Appearance → Icon Style.
 *
 * Styles: Default (the app's Material icons) plus a set of user-supplied
 * Telegram-format icon packs. Only icon slots whose meaning genuinely
 * exists in BOTH a pack and this app are remapped (see [GlyphSlotMap] and
 * each pack's generated *GlyphData file); every other slot keeps its
 * Material icon in every style. Which slots a pack covers is decided per
 * pack — a pack without an honest glyph for a slot simply doesn't list
 * it, and [AyxIcon] falls back to Material for that slot in that style.
 * Persisted like the other appearance settings (own SharedPreferences).
 */

/** How a pack's glyphs are rendered. */
enum class GlyphRenderMode {
    /** Nothing pack only: white dots take the tint, red accents stay red. */
    NOTHING_MATRIX,

    /** Monochrome glyphs: the caller's tint is applied through the alpha. */
    TINT,

    /** Colored / shaded glyphs (Liquid Glass): drawn exactly as shipped. */
    AS_IS,
}

/** One selectable icon-pack style. [glyphs] maps slot name → base64 PNG. */
class IconPackStyle(
    val id: Int,
    val displayName: String,
    val author: String,
    val description: String,
    val renderMode: GlyphRenderMode,
    val glyphs: Map<String, String>,
)

object IconPackRegistry {
    /** Style id 0 is always Default (Material icons, no pack). */
    const val DEFAULT_ID = 0

    val styles: List<IconPackStyle> = listOf(
        IconPackStyle(
            1, "Nothing", "@nonenamenot",
            "Dot-matrix glyphs — dots take the theme color, red accents stay red",
            GlyphRenderMode.NOTHING_MATRIX,
            NothingGlyphData.GLYPHS + NothingExtraGlyphData.GLYPHS,
        ),
        IconPackStyle(
            2, "DottedPack", "@yzewe",
            "Everything redrawn in dots — outlines, shapes, even the fills",
            GlyphRenderMode.TINT, DottedGlyphData.GLYPHS,
        ),
        IconPackStyle(
            3, "Fluent 2 Design Filled", "@ezhe42",
            "Microsoft's Fluent 2 filled glyphs",
            GlyphRenderMode.TINT, FluentGlyphData.GLYPHS,
        ),
        IconPackStyle(
            4, "GalaxyIcons", "@ekrutetskin_channel",
            "Samsung One UI style line glyphs",
            GlyphRenderMode.TINT, GalaxyGlyphData.GLYPHS,
        ),
        IconPackStyle(
            5, "Material Symbols", "@nlvxm",
            "Google's Material Symbols, close cousins of the default icons",
            GlyphRenderMode.TINT, MatsymGlyphData.GLYPHS,
        ),
        IconPackStyle(
            6, "Liquid Glass Pack", "@n1ksly",
            "Silver liquid-glass glyphs, shown exactly as the pack draws them",
            GlyphRenderMode.AS_IS, LiquidGlyphData.GLYPHS,
        ),
        IconPackStyle(
            7, "UPixel", "@Mr_Fruktik",
            "Chunky pixel-art glyphs",
            GlyphRenderMode.TINT, UpixelGlyphData.GLYPHS,
        ),
        IconPackStyle(
            8, "Rounded Material Symbols", "@Slucket",
            "Material Symbols with soft rounded corners",
            GlyphRenderMode.TINT, Md3SymbGlyphData.GLYPHS,
        ),
        IconPackStyle(
            9, "Pixel icons", "@gslt1",
            "Fine pixel-art glyphs",
            GlyphRenderMode.TINT, PixelGlyphData.GLYPHS,
        ),
    )

    private val byId: Map<Int, IconPackStyle> = styles.associateBy { it.id }

    fun pack(id: Int): IconPackStyle? = byId[id]

    fun isValidId(id: Int): Boolean = id == DEFAULT_ID || byId.containsKey(id)

    fun nameOf(id: Int): String = if (id == DEFAULT_ID) "Default" else byId[id]?.displayName ?: "Default"
}

object IconStyleSettings {
    const val DEFAULT = IconPackRegistry.DEFAULT_ID
    const val NOTHING = 1

    val style = mutableStateOf(DEFAULT)

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_icon_style", Context.MODE_PRIVATE)
        prefs = p
        val v = p.getInt("style", DEFAULT)
        // The stored value from the first release (0 = Default, 1 = Nothing)
        // still means the same style — ids were kept stable when the other
        // packs were added, so an existing Nothing choice stays Nothing.
        style.value = if (IconPackRegistry.isValidId(v)) v else DEFAULT
    }

    fun setStyle(v: Int) {
        style.value = v
        prefs?.edit()?.putInt("style", v)?.apply()
    }
}

/**
 * AyX icon slot per Material vector, for the slots that genuinely match a
 * Telegram-pack glyph. Deliberately NOT mapped (no pack has an honest
 * glyph): Pause is mapped only where a pack ships a real pause glyph;
 * the Status ring (DonutLarge), MotionPhotosOn, SwapHoriz, Layers,
 * GraphicEq, CloudOff, Category, TextFields, Stop (the packs' "stop"
 * glyphs are prohibition signs, not a media stop square), Grain,
 * Gesture, FormatShapes, ViewCarousel, Vibration, Terminal, Memory and
 * Logout keep their Material icons in every style.
 *
 * Note the AutoMirrored variants: Icons.AutoMirrored.Filled.X is a
 * DIFFERENT ImageVector instance than Icons.Filled.X — a slot mapped only
 * under its Filled instance silently misses every AutoMirrored call site.
 * (That is exactly why the settings-row chevrons — AutoMirrored
 * KeyboardArrowRight — stayed Material in the first Nothing release.)
 */
private object GlyphSlotMap {
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
        Icons.Filled.DeleteSweep to "delete",
        Icons.Filled.Add to "add",
        Icons.Filled.PhotoCamera to "camera",
        Icons.Filled.FilledImage to "gallery",
        Icons.Filled.PermMedia to "gallery",
        Icons.Filled.Videocam to "videocam",
        Icons.Filled.VideoFile to "videocam",
        Icons.Filled.AttachFile to "attach",
        Icons.Filled.MoreVert to "more",
        Icons.Filled.NotificationsOff to "mute",
        Icons.Filled.Notifications to "notify",
        Icons.Filled.VisibilityOff to "eyeoff",
        Icons.Filled.Visibility to "eye",
        Icons.Filled.RemoveRedEye to "eye",
        Icons.Filled.Lock to "lock",
        Icons.Filled.LockOpen to "lockopen",
        Icons.Filled.Download to "download",
        Icons.Filled.PlayArrow to "play",
        Icons.Filled.Pause to "pause",
        Icons.Filled.EmojiEmotions to "smile",
        Icons.Filled.Translate to "translate",
        Icons.Filled.Info to "info",
        Icons.Filled.Home to "home",
        Icons.Filled.Chat to "chat",
        Icons.Filled.QuestionAnswer to "autoreply",
        Icons.Filled.KeyboardArrowRight to "arrowright",
        Icons.AutoMirrored.Filled.AutoMirroredKeyboardArrowRight to "arrowright",
        Icons.Filled.KeyboardArrowUp to "arrowup",
        Icons.Filled.KeyboardArrowDown to "arrowdown",
        Icons.Filled.ArrowDownward to "arrowdown",
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
        // coverage expansion: slots that fell back to Material everywhere
        Icons.Filled.CheckCircle to "checkcircle",
        Icons.Filled.RadioButtonUnchecked to "radio",
        Icons.Filled.Speed to "speed",
        Icons.Filled.Bolt to "bolt",
        Icons.Filled.FlashOn to "bolt",
        Icons.Filled.Checklist to "checklist",
        Icons.Filled.Animation to "anim",
        Icons.Filled.BlurOn to "blur",
        Icons.Filled.BlurLinear to "blurlinear",
        Icons.Filled.ZoomIn to "zoomin",
        Icons.Filled.Wallpaper to "wallpaper",
        Icons.Filled.Timeline to "timeline",
        Icons.Filled.Storage to "storage",
        Icons.Filled.SelectAll to "selectall",
        Icons.Filled.RotateRight to "rotate",
        Icons.Filled.MusicNote to "music",
    )

    fun glyphFor(vector: ImageVector): String? = MAP[vector]
}

/** Decodes the embedded base64 glyphs once and caches the bitmaps. */
object PackGlyphStore {
    private val cache = HashMap<String, Bitmap?>()

    fun bitmap(pack: IconPackStyle, slot: String): Bitmap? {
        val key = "${pack.id}:$slot"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
            val decoded = runCatching {
                val b64 = pack.glyphs[slot] ?: return@runCatching null
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            cache[key] = decoded
            return decoded
        }
    }
}

/** Kept for the Nothing pack's original call sites. */
object NothingGlyphStore {
    fun bitmap(name: String): Bitmap? =
        IconPackRegistry.pack(IconStyleSettings.NOTHING)?.let { PackGlyphStore.bitmap(it, name) }
}

/**
 * The Nothing pack's glyphs are dot-matrix in two inks: WHITE dots (the
 * shape) and pure-RED dots (Nothing's accent). A plain SRC_IN tint would
 * flatten the red away; rendering the PNG as-is would make the white dots
 * invisible on light backgrounds. This color matrix instead maps the
 * white/grey dots to the caller's tint color and leaves the red accent
 * dots red:
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

private fun colorFilterFor(mode: GlyphRenderMode, tint: Color): ColorFilter? = when (mode) {
    GlyphRenderMode.NOTHING_MATRIX -> ColorFilter.colorMatrix(nothingTintMatrix(tint))
    GlyphRenderMode.TINT -> ColorFilter.tint(tint, BlendMode.SrcIn)
    GlyphRenderMode.AS_IS -> null
}

/** Renders one glyph of the given pack by slot name (used by the style preview). */
@Composable
fun PackGlyph(
    pack: IconPackStyle,
    slot: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val bmp = remember(pack.id, slot) { PackGlyphStore.bitmap(pack, slot) } ?: return
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = colorFilterFor(pack.renderMode, tint),
        filterQuality = FilterQuality.None,
    )
}

/** Renders one Nothing-pack glyph by slot name (kept for existing call sites). */
@Composable
fun NothingGlyph(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val pack = IconPackRegistry.pack(IconStyleSettings.NOTHING) ?: return
    PackGlyph(pack, name, contentDescription, modifier, tint)
}

/**
 * Drop-in replacement for material3 [Icon] with the same signature: when
 * an icon-pack style is active AND the vector has a mapped slot AND the
 * active pack ships a glyph for that slot, renders the pack glyph (per
 * the pack's render mode); otherwise renders the Material icon unchanged.
 */
@Composable
fun AyxIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val styleId = IconStyleSettings.style.value
    val pack = IconPackRegistry.pack(styleId)
    val slot = if (pack != null) GlyphSlotMap.glyphFor(imageVector) else null
    val bmp = remember(styleId, slot) {
        if (pack != null && slot != null) PackGlyphStore.bitmap(pack, slot) else null
    }
    if (pack == null || slot == null || bmp == null) {
        Icon(imageVector, contentDescription, modifier, tint)
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            colorFilter = colorFilterFor(pack.renderMode, tint),
            filterQuality = FilterQuality.None,
        )
    }
}
