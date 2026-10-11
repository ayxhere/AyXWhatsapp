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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Original WhatsApp dark colors (from MainActivity bubbleSpec "whatsapp" style)
private val OrigSentBubble = Color(0xFF005C4B)
private val OrigRecvBubble = Color(0xFF202C33)
private val OrigText = Color.White
private val OrigHeaderBg = Color(0xFF1F2C34)
private val OrigGreen = Color(0xFF00A884)
private val OrigTimeText = Color(0xFF8696A0)

/** Unified chat item: announcement, user message, or admin reply. */
private sealed class ChatItem {
    abstract val ts: Long
    data class AnnouncementItem(val a: Announcement) : ChatItem() {
        override val ts: Long get() = a.publishedAt
    }
    data class UserMsgItem(val m: UserMessage) : ChatItem() {
        override val ts: Long get() = m.createdAt
    }
    data class ReplyItem(val r: AdminReply) : ChatItem() {
        override val ts: Long get() = r.createdAt
    }
}

/**
 * "✦ AyX updates" — uses the ORIGINAL WhatsApp chat screen style:
 * wallpaper background, original bubble colors/shapes, original input bar.
 * Announcements + admin replies = received bubbles from "✦ AyX".
 * User messages = sent bubbles (green, right). Input bar at bottom
 * for direct messaging to AyX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AyxUpdatesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var announcements by remember { mutableStateOf(AyxGroupSync.cachedAnnouncements(ctx)) }
    var statuses by remember { mutableStateOf(AyxGroupSync.cachedStatuses(ctx)) }
    var userMessages by remember { mutableStateOf<List<UserMessage>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // Load wallpaper like the original chat screen
    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        val f = File(ctx.filesDir, "wallpaper.jpg")
        wallpaper = if (f.exists()) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    // Background sync on open
    LaunchedEffect(Unit) {
        try {
            AyxGroupSync.syncNow(ctx)
            announcements = AyxGroupSync.cachedAnnouncements(ctx)
            statuses = AyxGroupSync.cachedStatuses(ctx)
            val token = AyxGroupStore.accessToken(ctx)
            if (token != null) {
                userMessages = AyxGroupApi.myMessages(token)
            }
        } catch (_: Exception) {
            // Offline: keep showing cache.
        }
    }

    fun doSend() {
        val text = inputText.trim()
        if (text.isEmpty() || sending) return
        sending = true
        scope.launch {
            try {
                val token = AyxGroupStore.accessToken(ctx) ?: return@launch
                AyxGroupApi.sendMessage(token, text, "other")
                inputText = ""
                userMessages = AyxGroupApi.myMessages(token)
            } catch (_: Exception) {
                // Keep text for retry
            } finally {
                sending = false
            }
        }
    }

    viewerIndex?.let { idx ->
        AyxStatusViewer(
            statuses = statuses,
            startIndex = idx,
            onClose = { viewerIndex = null },
        )
    }

    val chatItems: List<ChatItem> = remember(announcements, userMessages) {
        val list = ArrayList<ChatItem>()
        announcements.forEach { list.add(ChatItem.AnnouncementItem(it)) }
        userMessages.forEach { m ->
            list.add(ChatItem.UserMsgItem(m))
            m.replies.forEach { r -> list.add(ChatItem.ReplyItem(r)) }
        }
        list.sortedBy { it.ts }
    }

    // Original style: Box with wallpaper, NOT Scaffold with solid color
    Box(Modifier.fillMaxSize()) {
        // Wallpaper background (like original chat)
        wallpaper?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(Modifier.fillMaxSize().background(Color(0xFF0B141A)))

        Column(Modifier.fillMaxSize()) {
            // Header (original style)
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
                            color = OrigText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OrigText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OrigHeaderBg),
            )

            // Status rings (if any)
            if (statuses.isNotEmpty()) {
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
                                .background(OrigGreen)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0B141A))
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

            // Messages (original: LazyColumn reverseLayout, 8dp horizontal padding, 3dp spacing)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                contentPadding = PaddingValues(top = 6.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                reverseLayout = true,
            ) {
                if (chatItems.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No updates yet. Send a message below to contact AyX.",
                                color = OrigTimeText,
                                fontSize = 14.sp,
                            )
                        }
                    }
                } else {
                    items(chatItems.reversed(), key = {
                        when (it) {
                            is ChatItem.AnnouncementItem -> "a:${it.a.id}"
                            is ChatItem.UserMsgItem -> "m:${it.m.id}"
                            is ChatItem.ReplyItem -> "r:${it.r.id}"
                        }
                    }) { item ->
                        when (item) {
                            is ChatItem.AnnouncementItem -> OrigAnnouncementBubble(item.a)
                            is ChatItem.UserMsgItem -> OrigSentBubble(item.m.body, item.m.createdAt)
                            is ChatItem.ReplyItem -> OrigReplyBubble(item.r)
                        }
                    }
                }
            }

            // Input bar (ORIGINAL style: attach, camera, text field, send button)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = { /* attach - not needed for AyX chat */ }) {
                    Icon(Icons.Filled.AttachFile, "attach", tint = OrigTimeText)
                }
                IconButton(onClick = { /* camera - not needed for AyX chat */ }) {
                    Icon(Icons.Filled.PhotoCamera, "camera", tint = OrigTimeText)
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Message", color = OrigTimeText) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { doSend() },
                    enabled = inputText.isNotBlank() && !sending,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "send")
                }
            }
        }
    }
}

/** Announcement — ORIGINAL WhatsApp received bubble style. */
@Composable
private fun OrigAnnouncementBubble(a: Announcement) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = OrigRecvBubble,
            // Original whatsapp style: RoundedCornerShape(8.dp, 8.dp, 8.dp, 2.dp)
            shape = RoundedCornerShape(8.dp, 8.dp, 8.dp, 2.dp),
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "✦ AyX",
                    color = OrigGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp),
                )
                if (a.title.isNotBlank()) {
                    Text(
                        a.title,
                        color = OrigText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                if (a.body.isNotBlank()) {
                    Text(
                        a.body,
                        color = OrigText,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(a.publishedAt),
                        color = OrigTimeText,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/** User sent message — ORIGINAL WhatsApp sent bubble style. */
@Composable
private fun OrigSentBubble(body: String, ts: Long) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = OrigSentBubble,
            // Original whatsapp style: RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp)
            shape = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp),
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    body,
                    color = OrigText,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(ts),
                        color = OrigTimeText,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/** Admin reply — ORIGINAL WhatsApp received bubble style. */
@Composable
private fun OrigReplyBubble(r: AdminReply) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = OrigRecvBubble,
            shape = RoundedCornerShape(8.dp, 8.dp, 8.dp, 2.dp),
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "✦ AyX",
                    color = OrigGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp),
                )
                Text(
                    r.body,
                    color = OrigText,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(r.createdAt),
                        color = OrigTimeText,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
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
