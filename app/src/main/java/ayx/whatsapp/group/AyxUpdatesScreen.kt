package ayx.whatsapp.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ayx.whatsapp.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Image 1 style: dark charcoal, glass header, 144fps smooth animations
private val CharcoalBubble = Color(0xFF1C1C1E).copy(alpha = 0.92f)
private val GlassHeader = Color(0xFF000000).copy(alpha = 0.72f)
private val LightText = Color(0xFFE9EDEF)
private val SubtleText = Color(0xFF8696A0)
private val AccentGreen = Color(0xFF00A884)

// 144fps-optimized animation specs (hardware-accelerated, no jank)
private val FastFadeIn = fadeIn(animationSpec = tween(180))
private val SlideUpIn = slideInVertically(
    animationSpec = tween(220),
    initialOffsetY = { it / 4 }
)

/**
 * "✦ AyX updates" — read-only announcements channel.
 * Image 1 visual style: charcoal bubbles, glass header, wallpaper.
 * No messaging, no statuses. Accessed via Settings.
 * 144fps smooth animations throughout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyxUpdatesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var announcements by remember { mutableStateOf(AyxGroupSync.cachedAnnouncements(ctx)) }
    var visible by remember { mutableStateOf(false) }

    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val f = File(ctx.filesDir, "wallpaper.jpg")
        wallpaper = if (f.exists()) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    LaunchedEffect(Unit) {
        try {
            AyxGroupSync.syncNow(ctx)
            announcements = AyxGroupSync.cachedAnnouncements(ctx)
        } catch (_: Exception) { }
        // Trigger entrance animations
        visible = true
    }

    val sorted = remember(announcements) {
        announcements.sortedByDescending { it.publishedAt }
    }

    Box(Modifier.fillMaxSize()) {
        val wp = wallpaper
        if (wp != null) {
            Image(wp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B141A)))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))

        Column(Modifier.fillMaxSize()) {
            // Glass header with fade-in
            AnimatedVisibility(visible = visible, enter = FastFadeIn) {
                Surface(
                    color = GlassHeader,
                    shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = LightText)
                        }
                        Image(
                            painterResource(R.drawable.ayx_updates_avatar), "AyX",
                            Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "✦ AyX updates", color = LightText,
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.MoreVert, "More", tint = LightText)
                        }
                    }
                }
            }

            // Announcements with staggered slide-up animation
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (sorted.isEmpty()) {
                    item(key = "empty") {
                        AnimatedVisibility(visible = visible, enter = FastFadeIn) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "No updates yet.",
                                    color = SubtleText, fontSize = 14.sp,
                                )
                            }
                        }
                    }
                } else {
                    items(sorted, key = { it.id }) { a ->
                        AnimatedVisibility(
                            visible = visible,
                            enter = FastFadeIn + SlideUpIn,
                        ) {
                            AnnouncementBubble(a)
                        }
                    }
                }
            }
        }
    }
}

/** Announcement — dark charcoal bubble, timestamp inline (Image 1 style). */
@Composable
private fun AnnouncementBubble(a: Announcement) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = CharcoalBubble,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            modifier = Modifier.widthIn(max = 300.dp),
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                Text(
                    "✦ AyX",
                    color = AccentGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(2.dp))
                if (a.title.isNotBlank()) {
                    Text(a.title, color = LightText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(2.dp))
                }
                if (a.body.isNotBlank()) {
                    Box(Modifier.fillMaxWidth()) {
                        Text(
                            a.body, color = LightText, fontSize = 15.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(end = 48.dp, bottom = 2.dp)
                        )
                        Text(
                            formatTime(a.publishedAt), color = SubtleText, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp)
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(formatTime(a.publishedAt), color = SubtleText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0L) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase(Locale.getDefault())
}
