package ayx.whatsapp.group

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "✦ AyX updates" screen: announcements + statuses from the AyX Group
 * backend. This is a dedicated channel — it is NOT a chat, so none of the
 * chat mute/block/hide machinery applies to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyxUpdatesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var announcements by remember { mutableStateOf(AyxGroupSync.cachedAnnouncements(ctx)) }
    var statuses by remember { mutableStateOf(AyxGroupSync.cachedStatuses(ctx)) }
    var lastSync by remember { mutableLongStateOf(AyxGroupStore.lastSyncAt(ctx)) }
    var syncing by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    suspend fun refresh() {
        syncing = true
        try {
            AyxGroupSync.syncNow(ctx)
        } catch (_: Exception) {
            // Offline or server error: keep showing the cache; footer shows last sync.
        } finally {
            announcements = AyxGroupSync.cachedAnnouncements(ctx)
            statuses = AyxGroupSync.cachedStatuses(ctx)
            lastSync = AyxGroupStore.lastSyncAt(ctx)
            syncing = false
        }
    }

    LaunchedEffect(Unit) {
        // Ask for notification permission on first open of AyX updates (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("✦ AyX updates", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 14.dp).size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { scope.launch { refresh() } }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Sync now")
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Announcements",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (announcements.isEmpty()) {
                item {
                    Text(
                        "No announcements yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(announcements, key = { it.id }) { a -> AnnouncementCard(a) }

            item {
                Text(
                    "Statuses",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (statuses.isEmpty()) {
                item {
                    Text(
                        "No active statuses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(statuses, key = { it.id }) { s -> AyxStatusCard(s) }

            item {
                Text(
                    if (lastSync > 0L) "Last synced " + relativeTime(lastSync) else "Not synced yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AnnouncementCard(a: Announcement) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                a.title.ifBlank { "AyX update" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (a.body.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(a.body, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                relativeTime(a.publishedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AyxStatusCard(s: AyxStatus) {
    val ctx = LocalContext.current
    var bmp by remember(s.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(s.id) {
        bmp = withContext(Dispatchers.IO) { AyxGroupSync.statusImageBitmap(ctx, s) }
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (!s.text.isNullOrBlank()) {
                Text(s.text, style = MaterialTheme.typography.bodyLarge)
            }
            val b = bmp
            if (s.kind == "image" && b != null) {
                Spacer(Modifier.height(8.dp))
                Image(
                    b,
                    contentDescription = "Status image",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            if (s.kind == "video") {
                Spacer(Modifier.height(8.dp))
                Text(
                    "▶ Video status",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Expires " + relativeTime(s.expiresAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
