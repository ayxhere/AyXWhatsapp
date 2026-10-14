package ayx.whatsapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AyX Premium Design System — four selectable visual themes.
 *
 * Each theme provides a complete set of design tokens. Screens read the
 * active theme via [AyxThemeProvider.current] and style themselves
 * accordingly. No hardcoded theme checks scattered in UI code.
 */

// ── Design tokens ──────────────────────────────────────────────

data class AyxColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceRaised: Color,
    val surfaceInset: Color,
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val accent: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val border: Color,
    val divider: Color,
    val bubbleSent: Color,
    val bubbleReceived: Color,
    val onBubbleSent: Color,
    val onBubbleReceived: Color,
    val inputBackground: Color,
    val cardBackground: Color,
    val badge: Color,
    val success: Color,
    val error: Color,
    val scrim: Color,
)

data class AyxShapes(
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val cornerFull: Dp,
    val bubbleCorner: Dp,
    val cardCorner: Dp,
)

data class AyxElevation(
    val card: Dp,
    val dialog: Dp,
    val button: Dp,
    val navBar: Dp,
    val shadowAlpha: Float,
)

data class AyxGlass(
    val enabled: Boolean,
    val surfaceAlpha: Float,
    val borderAlpha: Float,
    val blurRadius: Dp,
)

data class AyxTheme(
    val id: String,
    val name: String,
    val description: String,
    val colors: AyxColors,
    val shapes: AyxShapes,
    val elevation: AyxElevation,
    val glass: AyxGlass,
)

// ── Theme 1: Neumorphism ───────────────────────────────────────

val NeumorphismTheme = AyxTheme(
    id = "neumorphism",
    name = "Neumorphism",
    description = "Soft dimensional surfaces with gentle paired shadows",
    colors = AyxColors(
        background = Color(0xFF1A1D24),
        surface = Color(0xFF22262F),
        surfaceVariant = Color(0xFF262B36),
        surfaceRaised = Color(0xFF2A303C),
        surfaceInset = Color(0xFF16181E),
        primary = Color(0xFF4DD0C4),
        primaryVariant = Color(0xFF3AA89E),
        secondary = Color(0xFF82AAFF),
        accent = Color(0xFF4DD0C4),
        onBackground = Color(0xFFE8EAF0),
        onSurface = Color(0xFFE8EAF0),
        onPrimary = Color(0xFF0D1117),
        textSecondary = Color(0xFF9AA3B2),
        textDisabled = Color(0xFF5A6270),
        border = Color(0xFF2E3440),
        divider = Color(0xFF262B36),
        bubbleSent = Color(0xFF2A4A48),
        bubbleReceived = Color(0xFF262B36),
        onBubbleSent = Color(0xFFE8F5F3),
        onBubbleReceived = Color(0xFFE8EAF0),
        inputBackground = Color(0xFF16181E),
        cardBackground = Color(0xFF22262F),
        badge = Color(0xFF4DD0C4),
        success = Color(0xFF7BD88F),
        error = Color(0xFFFF6B6B),
        scrim = Color(0x99000000),
    ),
    shapes = AyxShapes(
        cornerSmall = 8.dp, cornerMedium = 14.dp, cornerLarge = 20.dp,
        cornerFull = 50.dp, bubbleCorner = 16.dp, cardCorner = 18.dp,
    ),
    elevation = AyxElevation(
        card = 0.dp, dialog = 8.dp, button = 0.dp, navBar = 4.dp, shadowAlpha = 0.35f,
    ),
    glass = AyxGlass(enabled = false, surfaceAlpha = 1f, borderAlpha = 1f, blurRadius = 0.dp),
)

// ── Theme 2: Glassmorphism ─────────────────────────────────────

val GlassmorphismTheme = AyxTheme(
    id = "glassmorphism",
    name = "Glassmorphism",
    description = "Translucent glass panels with cyan-blue lighting",
    colors = AyxColors(
        background = Color(0xFF0D1117),
        surface = Color(0x1A1E2A),
        surfaceVariant = Color(0x221E2A),
        surfaceRaised = Color(0x2A2438),
        surfaceInset = Color(0x0A0D14),
        primary = Color(0xFF6EE7F9),
        primaryVariant = Color(0xFF4CC9E8),
        secondary = Color(0xFFA78BFA),
        accent = Color(0xFF6EE7F9),
        onBackground = Color(0xFFF0F4FF),
        onSurface = Color(0xFFF0F4FF),
        onPrimary = Color(0xFF0D1117),
        textSecondary = Color(0xFFA8B3C7),
        textDisabled = Color(0xFF5A6577),
        border = Color(0x336EE7F9),
        divider = Color(0x1A6EE7F9),
        bubbleSent = Color(0x2A3A4A),
        bubbleReceived = Color(0x1E2430),
        onBubbleSent = Color(0xFFF0F4FF),
        onBubbleReceived = Color(0xFFF0F4FF),
        inputBackground = Color(0x141821),
        cardBackground = Color(0x1A1E2A),
        badge = Color(0xFF6EE7F9),
        success = Color(0xFF7BD88F),
        error = Color(0xFFFF6B6B),
        scrim = Color(0x99000000),
    ),
    shapes = AyxShapes(
        cornerSmall = 10.dp, cornerMedium = 16.dp, cornerLarge = 24.dp,
        cornerFull = 50.dp, bubbleCorner = 18.dp, cardCorner = 20.dp,
    ),
    elevation = AyxElevation(
        card = 0.dp, dialog = 12.dp, button = 2.dp, navBar = 0.dp, shadowAlpha = 0.25f,
    ),
    glass = AyxGlass(enabled = true, surfaceAlpha = 0.55f, borderAlpha = 0.35f, blurRadius = 16.dp),
)

// ── Theme 3: Skeuomorphism ─────────────────────────────────────

val SkeuomorphismTheme = AyxTheme(
    id = "skeuomorphism",
    name = "Skeuomorphism",
    description = "Rich material depth with tactile physical controls",
    colors = AyxColors(
        background = Color(0xFF14161C),
        surface = Color(0xFF1E2129),
        surfaceVariant = Color(0xFF242833),
        surfaceRaised = Color(0xFF2A2F3B),
        surfaceInset = Color(0xFF0E1015),
        primary = Color(0xFF5BC8BE),
        primaryVariant = Color(0xFF48A89F),
        secondary = Color(0xFF8B9BB4),
        accent = Color(0xFF5BC8BE),
        onBackground = Color(0xFFEDEFF5),
        onSurface = Color(0xFFEDEFF5),
        onPrimary = Color(0xFF0D1117),
        textSecondary = Color(0xFF9BA3B5),
        textDisabled = Color(0xFF5C6373),
        border = Color(0xFF333947),
        divider = Color(0xFF242833),
        bubbleSent = Color(0xFF23403D),
        bubbleReceived = Color(0xFF242833),
        onBubbleSent = Color(0xFFEDEFF5),
        onBubbleReceived = Color(0xFFEDEFF5),
        inputBackground = Color(0xFF0E1015),
        cardBackground = Color(0xFF1E2129),
        badge = Color(0xFF5BC8BE),
        success = Color(0xFF7BD88F),
        error = Color(0xFFFF6B6B),
        scrim = Color(0x99000000),
    ),
    shapes = AyxShapes(
        cornerSmall = 6.dp, cornerMedium = 10.dp, cornerLarge = 14.dp,
        cornerFull = 50.dp, bubbleCorner = 12.dp, cardCorner = 12.dp,
    ),
    elevation = AyxElevation(
        card = 4.dp, dialog = 12.dp, button = 3.dp, navBar = 6.dp, shadowAlpha = 0.45f,
    ),
    glass = AyxGlass(enabled = false, surfaceAlpha = 1f, borderAlpha = 1f, blurRadius = 0.dp),
)

// ── Theme 4: Claymorphism ──────────────────────────────────────

val ClaymorphismTheme = AyxTheme(
    id = "claymorphism",
    name = "Claymorphism",
    description = "Soft clay surfaces with friendly rounded depth",
    colors = AyxColors(
        background = Color(0xFF191C26),
        surface = Color(0xFF232636),
        surfaceVariant = Color(0xFF282C3E),
        surfaceRaised = Color(0xFF2E3348),
        surfaceInset = Color(0xFF14161F),
        primary = Color(0xFF7ED6CC),
        primaryVariant = Color(0xFF5FB8AE),
        secondary = Color(0xFF9D8DF1),
        accent = Color(0xFF7ED6CC),
        onBackground = Color(0xFFEEF0F8),
        onSurface = Color(0xFFEEF0F8),
        onPrimary = Color(0xFF0D1117),
        textSecondary = Color(0xFFA2A8BE),
        textDisabled = Color(0xFF60667C),
        border = Color(0xFF30354A),
        divider = Color(0xFF282C3E),
        bubbleSent = Color(0xFF2B4A47),
        bubbleReceived = Color(0xFF282C3E),
        onBubbleSent = Color(0xFFEEF0F8),
        onBubbleReceived = Color(0xFFEEF0F8),
        inputBackground = Color(0xFF14161F),
        cardBackground = Color(0xFF232636),
        badge = Color(0xFF7ED6CC),
        success = Color(0xFF8BD89A),
        error = Color(0xFFFF7B7B),
        scrim = Color(0x99000000),
    ),
    shapes = AyxShapes(
        cornerSmall = 12.dp, cornerMedium = 20.dp, cornerLarge = 28.dp,
        cornerFull = 50.dp, bubbleCorner = 22.dp, cardCorner = 24.dp,
    ),
    elevation = AyxElevation(
        card = 6.dp, dialog = 10.dp, button = 4.dp, navBar = 6.dp, shadowAlpha = 0.3f,
    ),
    glass = AyxGlass(enabled = false, surfaceAlpha = 1f, borderAlpha = 1f, blurRadius = 0.dp),
)

// ── Registry ───────────────────────────────────────────────────

val AyxThemes: List<AyxTheme> = listOf(
    NeumorphismTheme, GlassmorphismTheme, SkeuomorphismTheme, ClaymorphismTheme,
)

fun ayxThemeById(id: String): AyxTheme =
    AyxThemes.firstOrNull { it.id == id } ?: NeumorphismTheme
