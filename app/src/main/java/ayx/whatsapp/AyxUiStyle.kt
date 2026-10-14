package ayx.whatsapp

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AyX UI Styles — distinct interface design systems.
 *
 * These are UI STYLES (component shapes, surfaces, elevations, borders),
 * not color themes. They work alongside the existing Appearance settings
 * (theme mode, accent color) without replacing them.
 */

data class UiStyle(
    val id: String,
    val name: String,
    val description: String,
    val cardCorner: Dp,
    val buttonCorner: Dp,
    val inputCorner: Dp,
    val bubbleCorner: Dp,
    val chipCorner: Dp,
    val cardElevation: Dp,
    val buttonElevation: Dp,
    val dialogElevation: Dp,
    val pressedScale: Float,
    val useGlass: Boolean,
    val glassAlpha: Float,
    val borderWidth: Dp,
    val shadowIntensity: Float,
    val isDefault: Boolean = false,
)

/** System default — standard Material shapes, no special treatment. */
val DefaultUi = UiStyle(
    id = "default",
    name = "Default",
    description = "System default interface style",
    cardCorner = 12.dp, buttonCorner = 8.dp, inputCorner = 8.dp,
    bubbleCorner = 12.dp, chipCorner = 8.dp,
    cardElevation = 1.dp, buttonElevation = 1.dp, dialogElevation = 6.dp,
    pressedScale = 1f, useGlass = false, glassAlpha = 1f,
    borderWidth = 0.dp, shadowIntensity = 0.2f,
    isDefault = true,
)

val NeumorphismUi = UiStyle(
    id = "neumorphism",
    name = "Neumorphism",
    description = "Soft raised and recessed surfaces with dual shadows",
    cardCorner = 18.dp, buttonCorner = 14.dp, inputCorner = 14.dp,
    bubbleCorner = 16.dp, chipCorner = 20.dp,
    cardElevation = 0.dp, buttonElevation = 0.dp, dialogElevation = 8.dp,
    pressedScale = 0.97f, useGlass = false, glassAlpha = 1f,
    borderWidth = 0.dp, shadowIntensity = 0.35f,
)

val GlassmorphismUi = UiStyle(
    id = "glassmorphism",
    name = "Glassmorphism",
    description = "Translucent glass cards with frosted blur and depth",
    cardCorner = 20.dp, buttonCorner = 16.dp, inputCorner = 16.dp,
    bubbleCorner = 18.dp, chipCorner = 20.dp,
    cardElevation = 0.dp, buttonElevation = 2.dp, dialogElevation = 12.dp,
    pressedScale = 0.98f, useGlass = true, glassAlpha = 0.55f,
    borderWidth = 1.dp, shadowIntensity = 0.25f,
)

val SkeuomorphismUi = UiStyle(
    id = "skeuomorphism",
    name = "Skeuomorphism",
    description = "Tactile physical controls with realistic depth",
    cardCorner = 12.dp, buttonCorner = 10.dp, inputCorner = 8.dp,
    bubbleCorner = 12.dp, chipCorner = 14.dp,
    cardElevation = 4.dp, buttonElevation = 3.dp, dialogElevation = 12.dp,
    pressedScale = 0.96f, useGlass = false, glassAlpha = 1f,
    borderWidth = 1.dp, shadowIntensity = 0.45f,
)

val ClaymorphismUi = UiStyle(
    id = "claymorphism",
    name = "Claymorphism",
    description = "Soft puffy clay surfaces with playful 3D depth",
    cardCorner = 24.dp, buttonCorner = 20.dp, inputCorner = 20.dp,
    bubbleCorner = 22.dp, chipCorner = 24.dp,
    cardElevation = 6.dp, buttonElevation = 4.dp, dialogElevation = 10.dp,
    pressedScale = 0.95f, useGlass = false, glassAlpha = 1f,
    borderWidth = 0.dp, shadowIntensity = 0.3f,
)

val AyxUiStyles: List<UiStyle> = listOf(
    DefaultUi, NeumorphismUi, GlassmorphismUi, SkeuomorphismUi, ClaymorphismUi,
)

fun uiStyleById(id: String): UiStyle =
    AyxUiStyles.firstOrNull { it.id == id } ?: DefaultUi
