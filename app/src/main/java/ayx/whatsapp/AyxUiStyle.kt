package ayx.whatsapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AyX UI Styles — the single source of truth for interface design.
 *
 * Each style defines a complete visual identity:
 * - Corners: shape language (card, button, input, bubble, chip)
 * - Elevation: depth (card, button, dialog)
 * - Surface: glass translucency, borders, shadows
 * - Treatment: style-specific visual effects
 *
 * These are UI STYLES, not color themes. They work alongside Appearance
 * settings (theme mode, accent color) without replacing them.
 */
data class UiStyle(
    val id: String,
    val name: String,
    val description: String,
    // corners
    val cardCorner: Dp,
    val buttonCorner: Dp,
    val inputCorner: Dp,
    val bubbleCorner: Dp,
    val chipCorner: Dp,
    // elevation
    val cardElevation: Dp,
    val buttonElevation: Dp,
    val dialogElevation: Dp,
    // interaction
    val pressedScale: Float,
    // glassmorphism
    val useGlass: Boolean,
    val glassAlpha: Float,
    // borders
    val borderWidth: Dp,
    val borderAlpha: Float,
    // shadows
    val shadowIntensity: Float,
    // style-specific: neumorphism dual shadow
    val useDualShadow: Boolean,
    val lightShadowColor: Color,
    val darkShadowColor: Color,
    // style-specific: skeuomorphism gradient
    val useGradient: Boolean,
    // style-specific: claymorphism inflated
    val inflated: Boolean,
    val isDefault: Boolean = false,
)

/** System default — standard Material, no special treatment. */
val DefaultUi = UiStyle(
    id = "default",
    name = "Default",
    description = "System default interface style",
    cardCorner = 12.dp, buttonCorner = 8.dp, inputCorner = 8.dp,
    bubbleCorner = 12.dp, chipCorner = 8.dp,
    cardElevation = 1.dp, buttonElevation = 1.dp, dialogElevation = 6.dp,
    pressedScale = 1f,
    useGlass = false, glassAlpha = 1f,
    borderWidth = 0.dp, borderAlpha = 0f,
    shadowIntensity = 0.2f,
    useDualShadow = false,
    lightShadowColor = Color.White, darkShadowColor = Color.Black,
    useGradient = false,
    inflated = false,
    isDefault = true,
)

val NeumorphismUi = UiStyle(
    id = "neumorphism",
    name = "Neumorphism",
    description = "Soft raised and recessed surfaces with dual shadows",
    cardCorner = 18.dp, buttonCorner = 14.dp, inputCorner = 14.dp,
    bubbleCorner = 16.dp, chipCorner = 20.dp,
    cardElevation = 0.dp, buttonElevation = 0.dp, dialogElevation = 8.dp,
    pressedScale = 0.97f,
    useGlass = false, glassAlpha = 1f,
    borderWidth = 0.dp, borderAlpha = 0f,
    shadowIntensity = 0.35f,
    useDualShadow = true,
    lightShadowColor = Color(0xFFFFFFFF), darkShadowColor = Color(0xFF000000),
    useGradient = false,
    inflated = false,
)

val GlassmorphismUi = UiStyle(
    id = "glassmorphism",
    name = "Glassmorphism",
    description = "Translucent glass cards with frosted blur and depth",
    cardCorner = 20.dp, buttonCorner = 16.dp, inputCorner = 16.dp,
    bubbleCorner = 18.dp, chipCorner = 20.dp,
    cardElevation = 0.dp, buttonElevation = 2.dp, dialogElevation = 12.dp,
    pressedScale = 0.98f,
    useGlass = true, glassAlpha = 0.55f,
    borderWidth = 1.dp, borderAlpha = 0.4f,
    shadowIntensity = 0.25f,
    useDualShadow = false,
    lightShadowColor = Color.White, darkShadowColor = Color.Black,
    useGradient = false,
    inflated = false,
)

val SkeuomorphismUi = UiStyle(
    id = "skeuomorphism",
    name = "Skeuomorphism",
    description = "Tactile physical controls with realistic depth",
    cardCorner = 12.dp, buttonCorner = 10.dp, inputCorner = 8.dp,
    bubbleCorner = 12.dp, chipCorner = 14.dp,
    cardElevation = 4.dp, buttonElevation = 3.dp, dialogElevation = 12.dp,
    pressedScale = 0.96f,
    useGlass = false, glassAlpha = 1f,
    borderWidth = 1.dp, borderAlpha = 0.5f,
    shadowIntensity = 0.45f,
    useDualShadow = false,
    lightShadowColor = Color.White, darkShadowColor = Color.Black,
    useGradient = true,
    inflated = false,
)

val ClaymorphismUi = UiStyle(
    id = "claymorphism",
    name = "Claymorphism",
    description = "Soft puffy clay surfaces with playful 3D depth",
    cardCorner = 24.dp, buttonCorner = 20.dp, inputCorner = 20.dp,
    bubbleCorner = 22.dp, chipCorner = 24.dp,
    cardElevation = 6.dp, buttonElevation = 4.dp, dialogElevation = 10.dp,
    pressedScale = 0.95f,
    useGlass = false, glassAlpha = 1f,
    borderWidth = 0.dp, borderAlpha = 0f,
    shadowIntensity = 0.3f,
    useDualShadow = false,
    lightShadowColor = Color.White, darkShadowColor = Color.Black,
    useGradient = false,
    inflated = true,
)

val AyxUiStyles: List<UiStyle> = listOf(
    DefaultUi, NeumorphismUi, GlassmorphismUi, SkeuomorphismUi, ClaymorphismUi,
)

/** The four main styles (excluding Default). */
val AyxMainUiStyles: List<UiStyle> = listOf(
    NeumorphismUi, GlassmorphismUi, SkeuomorphismUi, ClaymorphismUi,
)

fun uiStyleById(id: String): UiStyle =
    AyxUiStyles.firstOrNull { it.id == id } ?: DefaultUi
