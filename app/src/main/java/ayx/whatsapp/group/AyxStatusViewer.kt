package ayx.whatsapp.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ayx.whatsapp.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WhatsApp-style full-screen status viewer for AyX statuses.
 * - Black background, progress bars at top (one per status)
 * - Tap left/right thirds to navigate, auto-advances after 5s for text/image
 * - Text statuses show centered large text (like WhatsApp text statuses)
 * - Image statuses show full-screen image
 * - Header shows AyX avatar + name + time
 */
@Composable
fun AyxStatusViewer(
    statuses: List<AyxStatus>,
    startIndex: Int,
    onClose: () -> Unit,
) {
    if (statuses.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var index by remember { mutableStateOf(startIndex.coerceIn(0, statuses.size - 1)) }
    val status = statuses.getOrNull(index)
    if (status == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    fun goNext() {
        if (index < statuses.size - 1) index++ else onClose()
    }
    fun goPrev() {
        if (index > 0) index-- // at first: do nothing (WhatsApp style)
    }

    var progress by remember(index) { mutableStateOf(0f) }
    var imgLoading by remember(status.id) { mutableStateOf(status.kind == "image") }

    // Auto-advance for text/image (5s like WhatsApp). Video: no auto-advance here
    // (AyX videos open externally); pause while loading.
    LaunchedEffect(index, imgLoading) {
        if (status.kind == "video" || imgLoading) return@LaunchedEffect
        progress = 0f
        val dur = 5000L
        val step = 50L
        var elapsed = 0L
        while (elapsed < dur) {
            delay(step)
            elapsed += step
            progress = (elapsed.toFloat() / dur).coerceIn(0f, 1f)
        }
        goNext()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                // --- content ---
                AyxStatusContent(status, onImageLoading = { imgLoading = it })

                // --- tap zones for navigation ---
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { goPrev() },
                    )
                    Box(
                        Modifier.weight(2f).fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { goNext() },
                    )
                }

                // --- top: progress bars + header ---
                Column(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    // Progress bars
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        statuses.forEachIndexed { i, _ ->
                            val p = when {
                                i < index -> 1f
                                i > index -> 0f
                                else -> progress
                            }
                            Box(
                                Modifier.weight(1f).height(2.5.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.35f)),
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(p).height(2.5.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Header: avatar + name + time + close
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painterResource(R.drawable.ayx_updates_avatar),
                            "AyX",
                            Modifier.size(38.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "✦ AyX",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                relativeTime(status.createdAt),
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, "Close", tint = Color.White)
                        }
                    }
                }

                // --- loading indicator ---
                if (imgLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AyxStatusContent(status: AyxStatus, onImageLoading: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember(status.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(status.id) {
        if (status.kind == "image") {
            onImageLoading(true)
            bmp = withContext(Dispatchers.IO) { AyxGroupSync.statusImageBitmap(ctx, status) }
            onImageLoading(false)
        }
    }

    when (status.kind) {
        "image" -> {
            val b = bmp
            if (b != null) {
                Image(
                    b,
                    "Status image",
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // Placeholder while loading (spinner is overlaid)
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
        "video" -> {
            // AyX video statuses: show a play affordance; tapping opens the URL externally.
            // (Inline video playback is handled by the system viewer via the media URL.)
            Box(Modifier.fillMaxSize().background(Color(0xFF0B141A)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▶", color = Color.White, style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(12.dp))
                    if (!status.text.isNullOrBlank()) {
                        Text(
                            status.text,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            }
        }
        else -> {
            // Text status: centered large text on a colored background (WhatsApp style)
            Box(
                Modifier.fillMaxSize().background(Color(0xFF075E54)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    status.text.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}
