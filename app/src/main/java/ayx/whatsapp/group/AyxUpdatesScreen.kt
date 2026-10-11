package ayx.whatsapp.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ayx.whatsapp.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// WhatsApp dark-mode colors (original)
private val WaChatBg = Color(0xFF0B141A)
private val WaHeaderBg = Color(0xFF1F2C34)
private val WaBubbleReceived = Color(0xFF1F2C34)
private val WaText = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)
private val WaGreen = Color(0xFF00A884)
private val WaStatusRing = Color(0xFF00A884)

/**
 * "✦ AyX updates" — WhatsApp-original chat screen style.
 * Announcements arrive as received message bubbles from "✦ AyX".
 * Statuses show as a horizontal row of rings (like WhatsApp Status tab) —
 * tap to open the full-screen viewer. Read-only channel, background sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyxUpdatesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var announcements by remember { mutableStateOf(AyxGroupSync.cachedAnnouncements(ctx)) }
    var statuses by remember { mutableStateOf(AyxGroupSync.cachedStatuses(ctx)) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // Background sync on open (debounced inside AyxGroupSync)
    LaunchedEffect(Unit) {
        try {
            AyxGroupSync.syncNow(ctx)
            announcements = AyxGroupSync.cachedAnnouncements(ctx)
            statuses = AyxGroupSync.cachedStatuses(ctx)
        } catch (_: Exception) {
            // Offline: keep showing cache.
        }
    }

    // Full-screen status viewer
    viewerIndex?.let { idx ->
        AyxStatusViewer(
            statuses = statuses,
            startIndex = idx,
            onClose = { viewerIndex = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.drawable.ayx_updates_avatar),
                            "AyX",
                            Modifier.size(40.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "✦ AyX updates",
                            color = WaText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = WaText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WaHeaderBg),
            )
        },
        containerColor = WaChatBg,
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Status rings (horizontal, like WhatsApp Status tab) — no text
            if (statuses.isNotEmpty()) {
                item(key = "status_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(statuses, key = { it.id }) { s ->
                            val idx = statuses.indexOfFirst { it.id == s.id }
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(WaStatusRing)
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(WaChatBg)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .clickable { viewerIndex = idx.coerceAtLeast(0) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painterResource(R.drawable.ayx_updates_avatar),
                                    "AyX status",
                                    Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
            }

            // Announcements as WhatsApp received bubbles (newest first)
            val sorted = announcements.sortedByDescending { it.publishedAt }
            if (sorted.isEmpty() && statuses.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No updates yet.",
                            color = WaTextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                items(sorted, key = { it.id }) { a ->
                    AyxAnnouncementBubble(a)
                }
            }
        }
    }
}

/** Announcement as a WhatsApp-original received bubble. */
@Composable
private fun AyxAnnouncementBubble(a: Announcement) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = WaBubbleReceived,
            shape = RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 6.dp)) {
                // Sender name (WhatsApp group style, teal)
                Text(
                    "✦ AyX",
                    color = WaGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(2.dp))
                if (a.title.isNotBlank()) {
                    Text(
                        a.title,
                        color = WaText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (a.body.isNotBlank()) {
                    Text(
                        a.body,
                        color = WaText,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                    )
                }
                // Timestamp bottom-right (WhatsApp style)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(a.publishedAt),
                        color = WaTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0L) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase(Locale.getDefault())
}
