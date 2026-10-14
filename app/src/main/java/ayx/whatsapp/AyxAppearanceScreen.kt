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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Settings → Appearance screen.
 *
 * Shows the four premium styles as selectable cards with live mini previews.
 * Selection applies immediately via [ThemeStore.setAppearanceStyle] and
 * persists across restarts (SharedPreferences).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyxAppearanceScreen(onBack: () -> Unit) {
    var previewId by remember { mutableStateOf(ThemeStore.appearanceStyle.value) }
    val activeTheme = ayxThemeById(ThemeStore.appearanceStyle.value)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance", color = activeTheme.colors.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = activeTheme.colors.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = activeTheme.colors.background
                ),
            )
        },
        containerColor = activeTheme.colors.background,
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(AyxThemes) { theme ->
                val selected = previewId == theme.id
                val borderWidth by animateDpAsState(
                    if (selected) 2.dp else 0.dp, tween(200), label = "sel-border"
                )
                AyxCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            borderWidth,
                            activeTheme.colors.primary,
                            RoundedCornerShape(activeTheme.shapes.cardCorner)
                        ),
                    onClick = {
                        previewId = theme.id
                        ThemeStore.setAppearanceStyle(theme.id)
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemeMiniPreview(theme, Modifier.weight(1f))
                            if (selected) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(activeTheme.colors.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Check, "Selected",
                                        tint = activeTheme.colors.onPrimary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            theme.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = activeTheme.colors.onSurface,
                        )
                        Text(
                            theme.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = activeTheme.colors.textSecondary,
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(4.dp))
                AyxButton(
                    text = "Restore Default Appearance",
                    onClick = {
                        previewId = "neumorphism"
                        ThemeStore.setAppearanceStyle("neumorphism")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Mini live preview: chat row + bubbles + composer in the given theme's tokens.
 */
@Composable
private fun ThemeMiniPreview(theme: AyxTheme, modifier: Modifier = Modifier) {
    val bubbleShape = RoundedCornerShape(theme.shapes.bubbleCorner)
    Column(
        modifier
            .clip(RoundedCornerShape(theme.shapes.cardCorner))
            .background(theme.colors.background)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // chat row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(theme.colors.surfaceVariant)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.5f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.colors.onSurface.copy(alpha = 0.75f))
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.colors.textSecondary.copy(alpha = 0.5f))
                )
            }
        }
        // received bubble
        Box(
            Modifier
                .clip(bubbleShape)
                .background(theme.colors.bubbleReceived)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Box(
                Modifier
                    .width(90.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.colors.onBubbleReceived.copy(alpha = 0.8f))
            )
        }
        // sent bubble
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .clip(bubbleShape)
                    .background(theme.colors.bubbleSent)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Box(
                    Modifier
                        .width(70.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(theme.colors.onBubbleSent.copy(alpha = 0.8f))
                )
            }
        }
        // composer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(theme.shapes.cornerFull))
                    .background(theme.colors.inputBackground)
                    .border(
                        1.dp,
                        theme.colors.border.copy(alpha = 0.6f),
                        RoundedCornerShape(theme.shapes.cornerFull)
                    )
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(theme.colors.primary)
            )
        }
        // nav hint
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == 0) theme.colors.primary
                            else theme.colors.textDisabled.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }
}
