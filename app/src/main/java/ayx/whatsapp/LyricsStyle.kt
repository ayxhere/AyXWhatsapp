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
        Option(LETTERS, "Letters", "Letters appear one by one as they're sung — H, HE, HEL, HELLO"),
    )

    val style = mutableStateOf(REVEAL)

    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("ayx_lyrics_style", Context.MODE_PRIVATE)
        prefs = p
        style.value = p.getInt("style", REVEAL)
    }

    fun setStyle(v: Int) {
        style.value = v
        prefs?.edit()?.putInt("style", v)?.apply()
    }

    fun label(id: Int): String = OPTIONS.firstOrNull { it.id == id }?.label ?: "Default · Reveal"
}
