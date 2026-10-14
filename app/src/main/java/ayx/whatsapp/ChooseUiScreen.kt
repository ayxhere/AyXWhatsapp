package ayx.whatsapp

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings → UI screen content ("Choose Your UI").
 *
 * Designed to run inside SettingsSubPage (which provides scrolling,
 * title and back navigation). Uses a plain Column — NOT a LazyColumn —
 * to avoid nested-scroll crashes.
 *
 * Separate from Appearance. Persisted via [UiStyleStore], survives restarts.
 */
@Composable
fun ChooseUiScreen(onBack: () -> Unit) {
    var selectedId by remember { mutableStateOf(UiStyleStore.uiStyle.value) }
    val accent = ThemeStore.accentColor()

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        for (style in AyxUiStyles) {
            val isSelected = selectedId == style.id
            val cardShape = RoundedCornerShape(style.cardCorner)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        style.cardElevation, cardShape,
                        ambientColor = Color.Black.copy(style.shadowIntensity),
                        spotColor = Color.Black.copy(style.shadowIntensity),
                    )
                    .clip(cardShape)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(
                            alpha = if (style.useGlass) style.glassAlpha else 1f
                        )
                    )
                    .then(
                        if (style.borderWidth > 0.dp || isSelected)
                            Modifier.border(
                                if (isSelected) 2.dp else style.borderWidth,
                                if (isSelected) accent
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                cardShape
                            )
                        else Modifier
                    )
                    .clickable {
                        selectedId = style.id
                        UiStyleStore.setUiStyle(style.id)
                    }
                    .padding(14.dp)
            ) {
                Column {
                    // genuine visual preview of the interface style
                    UiStylePreview(style)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                style.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                style.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isSelected) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check, "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Genuine visual preview of a UI style: mini chat list row, message
 * bubbles, input field and button — all rendered with the style's
 * own corners, elevations, glass and borders.
 */
@Composable
private fun UiStylePreview(style: UiStyle) {
    val bubbleShape = RoundedCornerShape(style.bubbleCorner)
    val inputShape = RoundedCornerShape(style.inputCorner)
    val btnShape = RoundedCornerShape(style.buttonCorner)
    Column(
        Modifier
            .clip(RoundedCornerShape(style.cardCorner))
            .background(MaterialTheme.colorScheme.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // chat list row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.5f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                )
            }
        }
        // message bubbles
        Box(
            Modifier
                .shadow(style.cardElevation, bubbleShape)
                .clip(bubbleShape)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = if (style.useGlass) style.glassAlpha else 1f
                    )
                )
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Box(
                Modifier
                    .width(90.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .shadow(style.buttonElevation, bubbleShape)
                    .clip(bubbleShape)
                    .background(ThemeStore.accentColor().copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Box(
                    Modifier
                        .width(70.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.85f))
                )
            }
        }
        // input + send button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(inputShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(
                        if (style.borderWidth > 0.dp)
                            Modifier.border(
                                style.borderWidth,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                inputShape
                            )
                        else Modifier
                    )
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(30.dp)
                    .shadow(style.buttonElevation, CircleShape)
                    .clip(CircleShape)
                    .background(ThemeStore.accentColor())
            )
        }
        // dialog / bottom-sheet hint
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(style.dialogElevation, RoundedCornerShape(style.cardCorner))
                .clip(RoundedCornerShape(style.cardCorner))
                .background(
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = if (style.useGlass) style.glassAlpha else 1f
                    )
                )
                .padding(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(btnShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(22.dp)
                        .shadow(style.buttonElevation, btnShape)
                        .clip(btnShape)
                        .background(ThemeStore.accentColor())
                )
            }
        }
    }
}
