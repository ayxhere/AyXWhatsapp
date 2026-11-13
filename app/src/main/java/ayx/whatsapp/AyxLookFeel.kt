package ayx.whatsapp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Orion-inspired "Look & feel" — ORIGINAL reimplementation for AyX WhatsApp.
 *
 * Design values below (palette hexes, timings, measurements) are facts
 * reimplemented from the public Orion Store design. No Orion source code,
 * assets, or brand marks are used: the AX logo and the AyX identity stay
 * ours, and every line of code here is written for this app.
 *
 * What it adds over the old theme system:
 *  - 4 themes: Light / Dusk (tinted dark) / Dark / OLED (pure black),
 *    plus "Follow system" (ThemeStore.mode == "system").
 *  - 6 palettes (Orion, Ocean, Forest, Sunset, Rose, Mono) — a palette
 *    drives primary / containers / tertiary / background, not just one
 *    accent color. Picking a palette sets ThemeStore accent to "palette";
 *    picking a named accent on the Accent page still overrides primary.
 *  - Material You (Android 12+ dynamic color) — overrides the palette.
 *  - Performance modes Auto / Lite / Balanced / Boost (auto resolves to
 *    Lite on < 4 GB RAM, else Balanced). Lite (or Disable animations)
 *    is "calm": decorative motion is skipped (splash glow, entrances).
 *  - Space Grotesk as the app font (downloaded once, like Orion does).
 */

// ── Palette data ─────────────────────────────────────────────────────────
// Key roles per theme. secondaryContainer is derived in the builder
// (primaryContainer blended toward the surface), so it is not stored.

data class PaletteRoles(
    val primary: Color,
    val primaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val background: Color,
    val surfaceContainer: Color,
)

data class AyxPalette(
    val id: String,
    val name: String,
    val light: PaletteRoles,
    val dark: PaletteRoles,
    val duskBackground: Color,
)

private fun c(hex: Long) = Color(hex)

val AYX_PALETTES: List<AyxPalette> = listOf(
    AyxPalette(
        "orion", "Orion",
        PaletteRoles(c(0xFF4C4ECD), c(0xFF7478F8), c(0xFF456900), c(0xFFCCFE82), c(0xFFFCF8FE), c(0xFFF0ECF6)),
        PaletteRoles(c(0xFFC0C1FF), c(0xFF3F40C0), c(0xFFF1FFD4), c(0xFFCAFB80), c(0xFF0E0E12), c(0xFF191920)),
        c(0xFF1E1E2E),
    ),
    AyxPalette(
        "ocean", "Ocean",
        PaletteRoles(c(0xFF006592), c(0xFF31B8FF), c(0xFF9C414F), c(0xFFFD8D9A), c(0xFFF8F9FD), c(0xFFEAEEF4)),
        PaletteRoles(c(0xFF89CEFF), c(0xFF00A8EE), c(0xFFFFA2AB), c(0xFFFD8D9A), c(0xFF0B0F11), c(0xFF151A1E)),
        c(0xFF13212B),
    ),
    AyxPalette(
        "forest", "Forest",
        PaletteRoles(c(0xFF196D33), c(0xFFA2F6AB), c(0xFF865400), c(0xFFFFAD3B), c(0xFFF8FAF3), c(0xFFEAF0E6)),
        PaletteRoles(c(0xFF87D991), c(0xFF016027), c(0xFFFFC073), c(0xFFFFAD3B), c(0xFF0C0F0B), c(0xFF151B15)),
        c(0xFF152316),
    ),
    AyxPalette(
        "sunset", "Sunset",
        PaletteRoles(c(0xFF9E4400), c(0xFFF77F37), c(0xFF6950A4), c(0xFFBA9FFA), c(0xFFFFF8F6), c(0xFFFEEAE1)),
        PaletteRoles(c(0xFFFFB690), c(0xFFB54E00), c(0xFFC6AFFF), c(0xFFBA9FFA), c(0xFF130D0A), c(0xFF211712)),
        c(0xFF2E1B10),
    ),
    AyxPalette(
        "rose", "Rose",
        PaletteRoles(c(0xFFA3356A), c(0xFFFB7BB3), c(0xFF006B60), c(0xFF8DFFEC), c(0xFFFFF8F8), c(0xFFFCE9EE)),
        PaletteRoles(c(0xFFFFB0CD), c(0xFF93285D), c(0xFFE5FFF9), c(0xFF8DFFEC), c(0xFF130D0F), c(0xFF20171A)),
        c(0xFF2D1A21),
    ),
    AyxPalette(
        "mono", "Mono",
        PaletteRoles(c(0xFF5D5F64), c(0xFFE1E2E8), c(0xFF5D5F64), c(0xFFF2F3FA), c(0xFFF9F9FE), c(0xFFEBEEF5)),
        PaletteRoles(c(0xFFC5C6CC), c(0xFF505258), c(0xFFF8F9FF), c(0xFFEAEBF1), c(0xFF0C0E12), c(0xFF161A1F)),
        c(0xFF1D2024),
    ),
)

// ── Store ────────────────────────────────────────────────────────────────

object LookFeelStore {
    val palette = mutableStateOf("orion")
    val materialYou = mutableStateOf(false)
    // auto | lite | balanced | boost
    val perfMode = mutableStateOf("auto")
    val haptic = mutableStateOf(true)
    val smoothMotion = mutableStateOf(false)
    val disableAnimations = mutableStateOf(false)
    val compactMode = mutableStateOf(false)

    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("lookfeel", Context.MODE_PRIVATE)
        val p = prefs!!
        palette.value = p.getString("palette", "orion") ?: "orion"
        materialYou.value = p.getBoolean("material_you", false)
        perfMode.value = p.getString("perf_mode", "auto") ?: "auto"
        haptic.value = p.getBoolean("haptic", true)
        smoothMotion.value = p.getBoolean("smooth_motion", false)
        disableAnimations.value = p.getBoolean("disable_animations", false)
        compactMode.value = p.getBoolean("compact_mode", false)
    }

    private fun save() {
        prefs?.edit()
            ?.putString("palette", palette.value)
            ?.putBoolean("material_you", materialYou.value)
            ?.putString("perf_mode", perfMode.value)
            ?.putBoolean("haptic", haptic.value)
            ?.putBoolean("smooth_motion", smoothMotion.value)
            ?.putBoolean("disable_animations", disableAnimations.value)
            ?.putBoolean("compact_mode", compactMode.value)
            ?.apply()
    }

    fun paletteById(id: String): AyxPalette =
        AYX_PALETTES.firstOrNull { it.id == id } ?: AYX_PALETTES[0]

    fun currentPalette(): AyxPalette = paletteById(palette.value)

    /** Primary for the given theme — used when ThemeStore accent is "palette". */
    fun palettePrimary(dark: Boolean): Color =
        (if (dark) currentPalette().dark else currentPalette().light).primary

    fun setPalette(id: String) {
        palette.value = id
        materialYou.value = false
        // Keep the single accent in sync so bubbles/icons (which read
        // ThemeStore.accentColor()) match the palette.
        ThemeStore.setAccent("palette")
        save()
    }

    fun setMaterialYou(enabled: Boolean) { materialYou.value = enabled; save() }
    fun setPerfMode(mode: String) { perfMode.value = mode; save() }
    fun setHaptic(enabled: Boolean) { haptic.value = enabled; save() }
    fun setSmoothMotion(enabled: Boolean) { smoothMotion.value = enabled; save() }
    fun setDisableAnimations(enabled: Boolean) { disableAnimations.value = enabled; save() }
    fun setCompactMode(enabled: Boolean) { compactMode.value = enabled; save() }

    fun isLowEnd(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem < 4L * 1024 * 1024 * 1024
    }

    /** Resolved performance: auto picks Lite on < 4 GB RAM, else Balanced. */
    fun resolvedPerf(ctx: Context): String =
        if (perfMode.value == "auto") (if (isLowEnd(ctx)) "lite" else "balanced") else perfMode.value

    /** Calm = decorative motion off (Lite, or Disable animations). */
    fun isCalm(ctx: Context): Boolean =
        disableAnimations.value || resolvedPerf(ctx) == "lite"
}

// ── Scheme builder ───────────────────────────────────────────────────────

/** Readable text/icon color on top of [bg] (black on light, white on dark). */
fun onColorFor(bg: Color): Color = if (bg.luminance() > 0.5f) Color.Black else Color.White

/**
 * Build the app ColorScheme for a theme + palette.
 * [theme] is "light" | "dusk" | "dark" | "oled" (system is resolved to
 * light/dark by the caller). [accent] is the resolved primary — the
 * palette primary when ThemeStore accent is "palette", else the named
 * accent color from the Accent page.
 */
fun lookFeelScheme(
    theme: String,
    palette: AyxPalette,
    materialYou: Boolean,
    accent: Color,
    ctx: Context,
    dark: Boolean,
): ColorScheme {
    val base: ColorScheme = when {
        // v271 FIX (user: Material You toggle did nothing): the dynamic
        // scheme must be returned as-is. The old code built it and then
        // painted the palette over every visible role (primary, background,
        // surface, containers), so the toggle looked dead. Material You
        // overrides the palette while it is on — picking a palette turns
        // it off again (LookFeelStore.setPalette). Only OLED keeps its
        // pure-black backgrounds on top of the dynamic colors.
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            if (theme == "oled") {
                return dynamic.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF242328),
                    surfaceContainerLowest = Color.Black,
                    surfaceContainerLow = Color(0xFF0E0E12),
                    surfaceContainer = Color(0xFF151519),
                    surfaceContainerHigh = Color(0xFF1B1B20),
                    surfaceContainerHighest = Color(0xFF242328),
                    surfaceDim = Color.Black,
                    surfaceBright = Color(0xFF303035),
                    outlineVariant = Color(0xFF3A3A42),
                )
            }
            return dynamic
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val roles = if (dark) palette.dark else palette.light
    val onBg = if (dark) Color(0xFFE7E4F0) else Color(0xFF32323B)
    val onSurfVar = if (dark) Color(0xFFACAAB5) else Color(0xFF5F5E68)
    var scheme = base.copy(
        primary = accent,
        onPrimary = onColorFor(accent),
        primaryContainer = roles.primaryContainer,
        onPrimaryContainer = onColorFor(roles.primaryContainer),
        secondary = accent,
        onSecondary = onColorFor(accent),
        secondaryContainer = lerp(roles.primaryContainer, roles.surfaceContainer, 0.45f),
        onSecondaryContainer = onColorFor(roles.primaryContainer),
        tertiary = roles.tertiary,
        onTertiary = onColorFor(roles.tertiary),
        tertiaryContainer = roles.tertiaryContainer,
        onTertiaryContainer = onColorFor(roles.tertiaryContainer),
        background = roles.background,
        onBackground = onBg,
        surface = roles.background,
        onSurface = onBg,
        surfaceVariant = roles.surfaceContainer,
        onSurfaceVariant = onSurfVar,
        surfaceContainer = roles.surfaceContainer,
        surfaceContainerLowest = roles.background,
        surfaceContainerLow = lerp(roles.background, roles.surfaceContainer, 0.35f),
        surfaceContainerHigh = lerp(roles.surfaceContainer, onBg, 0.06f),
        surfaceContainerHighest = lerp(roles.surfaceContainer, onBg, 0.12f),
        surfaceDim = lerp(roles.background, Color.Black, 0.08f),
        surfaceBright = lerp(roles.background, onBg, 0.10f),
        outlineVariant = lerp(roles.background, onBg, 0.22f),
    )
    // Dusk: dark scheme with surfaces lifted to a low-chroma tinted tone.
    if (theme == "dusk") {
        val bg = palette.duskBackground
        scheme = scheme.copy(
            background = bg,
            surface = bg,
            onBackground = Color(0xFFBAB9D5),
            onSurface = Color(0xFFBAB9D5),
            surfaceVariant = lerp(bg, Color.White, 0.10f),
            onSurfaceVariant = Color(0xFFBAB9D5),
            surfaceContainerLowest = lerp(bg, Color.Black, 0.25f),
            surfaceContainerLow = lerp(bg, Color.White, 0.03f),
            surfaceContainer = lerp(bg, Color.White, 0.06f),
            surfaceContainerHigh = lerp(bg, Color.White, 0.10f),
            surfaceContainerHighest = lerp(bg, Color.White, 0.14f),
            surfaceDim = lerp(bg, Color.Black, 0.15f),
            surfaceBright = lerp(bg, Color.White, 0.16f),
            outlineVariant = lerp(bg, Color.White, 0.22f),
        )
    }
    // OLED: pure black background/surfaces, near-black neutral containers.
    if (theme == "oled") {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF242328),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0E0E12),
            surfaceContainer = Color(0xFF151519),
            surfaceContainerHigh = Color(0xFF1B1B20),
            surfaceContainerHighest = Color(0xFF242328),
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF303035),
            outlineVariant = Color(0xFF3A3A42),
        )
    }
    return scheme
}

// ── Cookie shape (Orion's 9-sided "cookie") ──────────────────────────
// Original geometry for AyX: 9 rounded lobes around a circle. Used by
// the splash blob and by the selected palette swatch morph. Rotation is
// visible on it (unlike a plain circle), which is what makes Orion's
// blob spring-open-while-turning read as motion.

fun ayxCookiePath(size: Float): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val cx = size / 2f
    val cy = size / 2f
    val lobes = 9
    val rOuter = size / 2f
    val rInner = size * 0.44f
    fun pt(i: Int): androidx.compose.ui.geometry.Offset {
        val angle = (i * (360f / (lobes * 2)) - 90f) * (Math.PI / 180f).toFloat()
        val r = if (i % 2 == 0) rOuter else rInner
        return androidx.compose.ui.geometry.Offset(
            cx + r * kotlin.math.cos(angle),
            cy + r * kotlin.math.sin(angle),
        )
    }
    val n = lobes * 2
    val start = androidx.compose.ui.geometry.Offset(
        (pt(0).x + pt(n - 1).x) / 2f,
        (pt(0).y + pt(n - 1).y) / 2f,
    )
    path.moveTo(start.x, start.y)
    for (i in 0 until n) {
        val p = pt(i)
        val next = pt((i + 1) % n)
        path.quadraticBezierTo(p.x, p.y, (p.x + next.x) / 2f, (p.y + next.y) / 2f)
    }
    path.close()
    return path
}

// ── Haptics ──────────────────────────────────────────────────────────────

/** Small tap tick, only when Haptic feedback is on. */
fun lookFeelHaptic(view: View?) {
    if (!LookFeelStore.haptic.value) return
    view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
}

// ── Space Grotesk font (downloaded once) ────────────────────────────────

/**
 * Space Grotesk — Orion's default font. Not bundled (keeps the APK small);
 * downloaded once from the Google Fonts repo into filesDir/userfonts and
 * registered with FontStore like any user font. Falls back to the system
 * font until the download lands.
 */
object SpaceGroteskFont {
    const val NAME = "Space Grotesk"
    private const val URL =
        "https://github.com/google/fonts/raw/main/ofl/spacegrotesk/SpaceGrotesk%5Bwght%5D.ttf"

    /** idle | downloading | ready | failed */
    val state = mutableStateOf("idle")

    fun file(ctx: Context): File = File(ctx.filesDir, "userfonts/SpaceGrotesk.ttf")

    fun isReady(ctx: Context): Boolean {
        val f = file(ctx)
        return f.exists() && f.length() > 10_000
    }

    /**
     * Download in the background. onDone runs on the main thread with true
     * when the font is usable. Safe to call repeatedly — no-ops when ready
     * or already downloading.
     */
    fun ensure(ctx: Context, onDone: ((Boolean) -> Unit)? = null) {
        if (isReady(ctx)) {
            state.value = "ready"
            FontStore.registerDownloadedFont(ctx, NAME, file(ctx))
            onDone?.invoke(true)
            return
        }
        if (state.value == "downloading") return
        state.value = "downloading"
        Thread {
            var ok = false
            try {
                val conn = URL(URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "AyXWhatsApp")
                conn.connect()
                if (conn.responseCode == 200) {
                    val f = file(ctx).apply { parentFile?.mkdirs() }
                    conn.inputStream.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                    ok = f.exists() && f.length() > 10_000
                    if (ok) FontStore.registerDownloadedFont(ctx, NAME, f)
                }
                conn.disconnect()
            } catch (_: Exception) {
                ok = false
            }
            val done = ok
            Handler(Looper.getMainLooper()).post {
                state.value = if (done) "ready" else "failed"
                if (!done) {
                    Toast.makeText(ctx, "Space Grotesk download failed — system font kept", Toast.LENGTH_SHORT).show()
                }
                onDone?.invoke(done)
            }
        }.start()
    }
}
