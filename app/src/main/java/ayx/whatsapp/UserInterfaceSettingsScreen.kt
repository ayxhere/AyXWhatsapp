package ayx.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ayx.whatsapp.ui.AnimationStyle
import ayx.whatsapp.ui.AyxZoomPage
import ayx.whatsapp.ui.BlurElement
import ayx.whatsapp.ui.UiAnimationSettings

/**
 * Settings → User Interface screen.
 *
 * Fresh implementation matching the REFERENCE screenshot's 5 rows
 * (Material Blur Effects / Elements to have blur effect / Animation
 * Style / Motion Blur in Animation / Scroll Animation). Layout, text,
 * and code are written from scratch for AyXWhatsapp — no resources or
 * code were copied from any other app.
 *
 * The screen is rendered inside SettingsSubPage (which provides scroll
 * + padding), mirroring [AppearanceSettings] — it has no own Scaffold
 * or back arrow; the top app bar provides those.
 */
@Composable
fun UserInterfaceSettingsScreen() {
    val blurEnabled by UiAnimationSettings.materialBlurEnabled
    val blurElements by UiAnimationSettings.blurElements
    val animStyle by UiAnimationSettings.animationStyle
    val motionBlur by UiAnimationSettings.motionBlurEnabled
    val scrollAnim by UiAnimationSettings.scrollAnimationEnabled

    var showElementsDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Group 1 — Blur
        UiSettingsGroup("Blur") {
            UiToggleRow(
                icon = Icons.Filled.BlurOn,
                tint = Color(0xFF8B7CFF),
                title = "Material Blur Effects",
                desc = "Apply a background blur effect to navigation and menus",
                checked = blurEnabled,
                onChange = { UiAnimationSettings.setMaterialBlur(it) },
            )
            UiDivider()
            UiNavRow(
                icon = Icons.Filled.Layers,
                tint = Color(0xFF6EC3E8),
                title = "Elements to have blur effect",
                desc = "Choose which UI elements use the blur effect",
                value = if (blurElements.isEmpty()) "None"
                else blurElements.sortedBy { it.ordinal }.joinToString(", ") { it.label },
                onClick = { showElementsDialog = true },
            )
        }

        // Group 2 — Animation
        UiSettingsGroup("Animation") {
            UiNavRow(
                icon = Icons.Filled.SwapHoriz,
                tint = Color(0xFF4DD0C4),
                title = "Animation Style",
                desc = animStyle.subtitle,
                value = animStyle.label,
                onClick = { showStyleDialog = true },
            )
            UiDivider()
            UiToggleRow(
                icon = Icons.Filled.MotionPhotosOn,
                tint = Color(0xFFE88BD0),
                title = "Motion Blur in Animation",
                desc = "Apply dynamic motion blur during zoom page transitions",
                checked = motionBlur,
                onChange = { UiAnimationSettings.setMotionBlur(it) },
            )
            UiDivider()
            UiToggleRow(
                icon = Icons.Filled.ViewCarousel,
                tint = Color(0xFF6EA8FF),
                title = "Scroll Animation",
                desc = "Fade-in animation for list items as you scroll",
                checked = scrollAnim,
                onChange = { UiAnimationSettings.setScrollAnimation(it) },
            )
        }

        // Live preview — demonstrates the current zoom + blur choices.
        UiSettingsGroup("Preview") {
            AyxZoomPage(motionBlur = motionBlur) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        )
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            "Zoom preview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Style: ${animStyle.label} · Blur: " +
                                if (blurEnabled) "ON (${blurElements.size} surfaces)" else "OFF" +
                                " · Motion blur: " + if (motionBlur) "ON" else "OFF" +
                                " · Scroll fade-in: " + if (scrollAnim) "ON" else "OFF",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(
                "Motion blur during zoom is an animated-blur approximation, " +
                    "not true optical motion blur.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }

    if (showElementsDialog) {
        var draft by remember { mutableStateOf(blurElements) }
        AlertDialog(
            onDismissRequest = { showElementsDialog = false },
            title = { Text("Elements to have blur effect") },
            text = {
                Column {
                    BlurElement.entries.forEach { element ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    draft = if (draft.contains(element)) draft - element
                                    else draft + element
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = draft.contains(element),
                                onCheckedChange = {
                                    draft = if (it) draft + element else draft - element
                                },
                            )
                            Text(element.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Apply one element at a time through the store so the
                    // persisted set always matches the visible set.
                    BlurElement.entries.forEach { element ->
                        val want = draft.contains(element)
                        val has = UiAnimationSettings.blurElements.value.contains(element)
                        if (want != has) UiAnimationSettings.toggleBlurElement(element)
                    }
                    showElementsDialog = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showElementsDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showStyleDialog) {
        var draft by remember { mutableStateOf(animStyle) }
        AlertDialog(
            onDismissRequest = { showStyleDialog = false },
            title = { Text("Animation Style") },
            text = {
                Column {
                    AnimationStyle.entries.forEach { style ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { draft = style }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = draft == style,
                                onClick = { draft = style },
                            )
                            Column {
                                Text(style.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    style.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    UiAnimationSettings.setAnimationStyle(draft)
                    showStyleDialog = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { showStyleDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun UiSettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
        )
        StyledSurface(
            shape = uiShapeFor(20),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 4.dp, horizontal = 12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun UiToggleRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(uiShapeFor(12))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UiNavRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    desc: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(uiShapeFor(12)).clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(uiShapeFor(12))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "$desc · $value",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UiDivider() {
    Spacer(Modifier.height(2.dp))
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
    Spacer(Modifier.height(2.dp))
}
