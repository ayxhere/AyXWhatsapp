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
