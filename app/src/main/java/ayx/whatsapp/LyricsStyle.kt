package ayx.whatsapp

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * Lyrics display style for the status editor — picked from the chooser
 * that opens on double-tap of the editor preview (or via the song panel's
 * "Lyrics style" button), and used BOTH by the editor's live preview and
 * by the send-time render (MediaTools.photosToVideo / drawComposite), so
 * what you see in the editor is what a photo status bakes in.
 *
 * The ids ARE the MediaTools.drawComposite style codes:
 *   0 Plain, 1 Reveal (the default), 2 Pop, 3 Slide Up, 5 Fade,
 *   6 Blur and 7 Rotate — the last two carry over the appear channels of
 *   Settings → Appearance → Text Animation (blur / rotate) to lyrics.
 *   8 Text Motion (added later, additive): a per-CHARACTER glide-in on
 *   TextAnim's own easing family — the photo bake draws its settled
 *   end-state (full line, sharp), since a frame render has no live
 *   per-character timeline.
 *   9 Letters (v169, additive): the current line's LETTERS appear one
 *   by one WITH the singing — visible character count = progress
 *   through the line's own window (line start → next line start; the
 *   last line runs to the trim end) × line length, so "Hello" builds
 *   H → HE → HEL → HELL → HELLO with no spaces inserted. Display-side
 *   math only (the synced (timeMs, line) list is untouched); the photo
 *   bake again draws the settled end-state (full line).
 *
 * Scope, honestly: this only decides how an already-synced line is DRAWN.
 * The lyrics fetch / parse / sync / cache pipeline (the LYRICS LOCK) is
 * not touched by this file. Video statuses never carried lyrics in the
 * posted file and still don't — for them the style is preview-only,
 * exactly as lyrics themselves are today.
 *
 * Persisted in SharedPreferences ("ayx_lyrics_style") so the editor
 * reopens on the last-used style, like the other settings stores.
 */
object LyricsStyleStore {
    const val PLAIN = 0
    const val REVEAL = 1
    const val POP = 2
    const val SLIDE = 3
    const val FADE = 5
    const val BLUR = 6
    const val ROTATE = 7
    const val TEXT_MOTION = 8
    const val LETTERS = 9

    /**
     * THE shared lyric size definition (editor preview ⇄ photo bake).
     * The editor draws every style at [LYRIC_BASE_SP] sp (line height
     * [LYRIC_LINE_SP] sp) times the pinch lyricScale (0.5–2.5×) on a
     * full-screen-width preview, so a lyric's on-screen size is the
     * FRACTION [baseFractionOfWidth] of the frame width. The send-time
     * bake (MediaTools.drawComposite) takes the same fraction of its
     * canvas width — the posted status then shows the SAME relative
     * size the editor preview showed, locked at any lyricScale.
     * (The bake previously keyed size off canvas HEIGHT — 0.052·h for
     * whole-line styles, 0.056·h for Reveal — which inflated lyrics
     * ~1.4–1.9× over the preview, worst on tall aspect canvases.)
     */
    const val LYRIC_BASE_SP = 27f
    const val LYRIC_LINE_SP = 31f

    /** Editor base text height as a fraction of the (full-screen) preview width, on THIS device. */
    fun baseFractionOfWidth(ctx: Context): Float {
        val dm = ctx.resources.displayMetrics
        if (dm.widthPixels <= 0) return 0f
        val basePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, LYRIC_BASE_SP, dm)
        return basePx / dm.widthPixels.toFloat()
    }

    data class Option(val id: Int, val label: String, val desc: String)

    val OPTIONS = listOf(
        Option(REVEAL, "Default · Reveal", "Words appear one by one — the newest word stays grey"),
        Option(BLUR, "Blur", "Each line melts in from a soft blur — Text Animation style"),
        Option(SLIDE, "Slide Up", "Each line slides up into place — Text Animation style"),
        Option(POP, "Pop", "Each line pops in with a grow — Text Animation style"),
        Option(ROTATE, "Rotate", "Each line tilts in — Text Animation style"),
        Option(FADE, "Fade", "Each line fades in"),
        Option(PLAIN, "Plain", "Full line at once — no animation"),
        Option(TEXT_MOTION, "Text Motion", "Letters glide in one by one with a bounce — Text Animation style"),
        Option(LETTERS, "Letters", "Letters appear one by one as they're sung, gliding in — H, HE, HEL, HELLO — Text Animation style"),
    )

    val style = mutableStateOf(REVEAL)

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_lyrics_style", Context.MODE_PRIVATE)
        prefs = p
        style.value = p.getInt("style", REVEAL)
        colorArgb.value = p.getInt("color", 0)
    }

    fun setStyle(v: Int) {
        style.value = v
        prefs?.edit()?.putInt("style", v)?.apply()
    }

    /**
     * Lyrics colour (v173) — picked from the colour row inside the
     * double-tap "Lyrics style" dialog, stored next to the style in the
     * same prefs. 0 = Default: the lyric accent #FF4848 (the v169
     * colour); any other value is a picked ARGB that tints the lyrics
     * text in BOTH the editor preview (StyledLyricLine / the Reveal
     * renderer) and the photo bake (MediaTools.drawComposite reads
     * [activeColorArgb] exactly like it reads [baseFractionOfWidth]),
     * so preview and posted status agree. The colour mechanism the user
     * remembered (a colour choice on the double-tap dialog) did not
     * exist in the editor — the accent was a hardcoded constant on both
     * sides — so this state is the rebuild, deliberately minimal:
     * Default + a fixed palette, no per-post override.
     */
    val colorArgb = mutableStateOf(0)

    data class ColorOption(val label: String, val argb: Int)

    /** The dialog's colour row: Default first, then the palette. */
    val COLOR_OPTIONS = listOf(
        ColorOption("Default", 0),
        ColorOption("White", 0xFFFFFFFF.toInt()),
        ColorOption("Green", 0xFF4ADE80.toInt()),
        ColorOption("Amber", 0xFFFFB84D.toInt()),
        ColorOption("Sky", 0xFF4DC3FF.toInt()),
        ColorOption("Violet", 0xFFB388FF.toInt()),
        ColorOption("Pink", 0xFFFF7AC8.toInt()),
        ColorOption("Cyan", 0xFF3EE6D8.toInt()),
        ColorOption("Black", 0xFF000000.toInt()),
        ColorOption("Blue", 0xFF0A84FF.toInt()),
    )

    /** The ARGB lyrics render in right now — the accent when unpicked. */
    fun activeColorArgb(): Int = if (colorArgb.value != 0) colorArgb.value else 0xFFFF4848.toInt()

    fun setColor(v: Int) {
        colorArgb.value = v
        prefs?.edit()?.putInt("color", v)?.apply()
    }

    fun colorLabel(argb: Int): String = COLOR_OPTIONS.firstOrNull { it.argb == argb }?.label ?: "Custom"

    fun label(id: Int): String = OPTIONS.firstOrNull { it.id == id }?.label ?: "Default · Reveal"
}
