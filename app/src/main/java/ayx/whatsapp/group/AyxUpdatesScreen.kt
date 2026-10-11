package ayx.whatsapp.group

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import ayx.whatsapp.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "✦ AyX updates" screen — styled like a WhatsApp chat:
 * announcements arrive as message bubbles from "✦ AyX", statuses open in the
 * full-screen WhatsApp-style viewer on tap. This is a read-only channel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyxUpdatesScreen(onBack: () -> Unit, onOpenWebsite: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var announcements by remember { mutableStateOf(AyxGroupSync.cachedAnnouncements(ctx)) }
    var statuses by remember { mutableStateOf(AyxGroupSync.cachedStatuses(ctx)) }
    var lastSync by remember { mutableLongStateOf(AyxGroupStore.lastSyncAt(ctx)) }
    var syncing by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Initial sync
        syncing = true
        try {
            AyxGroupSync.syncNow(ctx)
        } catch (_: Exception) {
            // Offline or server error: keep showing the cache.
        } finally {
            announcements = AyxGroupSync.cachedAnnouncements(ctx)
            statuses = AyxGroupSync.cachedStatuses(ctx)
            lastSync = AyxGroupStore.lastSyncAt(ctx)
            syncing = false
        }
    }

    fun doRefresh() {
        scope.launch {
            syncing = true
            try {
                AyxGroupSync.syncNow(ctx)
            } catch (_: Exception) {
                // Offline or server error: keep showing the cache.
            } finally {
                announcements = AyxGroupSync.cachedAnnouncements(ctx)
                statuses = AyxGroupSync.cachedStatuses(ctx)
                lastSync = AyxGroupStore.lastSyncAt(ctx)
                syncing = false
            }
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
                            Modifier.size(36.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("✦ AyX updates", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (lastSync > 0L) "last synced " + relativeTime(lastSync) else "not synced yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Website link
                    IconButton(onClick = onOpenWebsite) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open imayx.in")
                    }
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 14.dp).size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { doRefresh() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (announcements.isEmpty() && statuses.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No updates yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pull to refresh or tap the sync button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                reverseLayout = true, // newest at bottom, like a chat
            ) {
                // Statuses first (older), then announcements — chronological chat order
                val statusItems: List<ChatItem> = statuses.sortedBy { it.createdAt }.map { ChatItem.Status(it) }
                val annItems: List<ChatItem> = announcements.sortedBy { it.publishedAt }.map { ChatItem.Announcement(it) }
                val ordered = (statusItems + annItems).sortedBy {
                    when (it) {
                        is ChatItem.Status -> it.s.createdAt
                        is ChatItem.Announcement -> it.a.publishedAt
                    }
                }
                items(ordered, key = {
                    when (it) {
                        is ChatItem.Status -> "s:" + it.s.id
                        is ChatItem.Announcement -> "a:" + it.a.id
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.Status -> {
                            val idx = statuses.indexOfFirst { s -> s.id == item.s.id }
                            AyxStatusBubble(item.s, onClick = { viewerIndex = idx.coerceAtLeast(0) })
                        }
                        is ChatItem.Announcement -> AyxAnnouncementBubble(item.a)
                    }
                }
            }
        }
    }
}

private sealed class ChatItem {
    data class Status(val s: AyxStatus) : ChatItem()
    data class Announcement(val a: Announcement) : ChatItem()
}

/** Announcement as a received chat bubble. */
@Composable
private fun AyxAnnouncementBubble(a: Announcement) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    "✦ AyX",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00A884),
                )
                Spacer(Modifier.height(4.dp))
                if (a.title.isNotBlank()) {
                    Text(
                        a.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (a.body.isNotBlank()) {
                    Text(a.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(a.publishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Status as a tappable chat bubble — opens the full-screen viewer. */
@Composable
private fun AyxStatusBubble(s: AyxStatus, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
            modifier = Modifier.widthIn(max = 300.dp).clickable { onClick() },
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status ring (green, like WhatsApp unread)
                    Box(
                        Modifier.size(44.dp)
                            .background(Color.Transparent)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painterResource(R.drawable.ayx_updates_avatar),
                            "AyX status",
                            Modifier.size(38.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "✦ AyX status",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00A884),
                        )
                        Text(
                            formatTime(s.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!s.text.isNullOrBlank() && s.kind == "text") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                    )
                }
                if (s.kind == "image") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "📷 Photo status — tap to view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (s.kind == "video") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "▶ Video status — tap to view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
