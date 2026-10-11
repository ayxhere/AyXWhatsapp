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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

private val CharcoalBubble = Color(0xFF1C1C1E).copy(alpha = 0.92f)
private val GlassHeader = Color(0xFF000000).copy(alpha = 0.72f)
private val LightText = Color(0xFFE9EDEF)
private val SubtleText = Color(0xFF8696A0)
private val AccentGreen = Color(0xFF00A884)

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
            statuses = AyxGroupSync.cachedStatuses(ctx)
            val token = AyxGroupStore.accessToken(ctx)
            if (token != null) userMessages = AyxGroupApi.myMessages(token)
        } catch (_: Exception) { }
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
            } catch (_: Exception) { }
            finally { sending = false }
        }
    }

    viewerIndex?.let { idx ->
        AyxStatusViewer(statuses = statuses, startIndex = idx, onClose = { viewerIndex = null })
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

    Box(Modifier.fillMaxSize()) {
        val wp = wallpaper
        if (wp != null) {
            Image(wp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B141A)))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
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

            if (statuses.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(statuses, key = { it.id }) { s ->
                        val idx = statuses.indexOfFirst { it.id == s.id }
                        Box(
                            modifier = Modifier.size(62.dp).clip(CircleShape)
                                .background(AccentGreen).padding(2.5.dp).clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f)).padding(2.dp)
                                .clip(CircleShape).clickable { viewerIndex = idx.coerceAtLeast(0) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painterResource(R.drawable.ayx_updates_avatar), "AyX status",
                                Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = true,
            ) {
                if (chatItems.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No updates yet. Send a message below to contact AyX.",
                                color = SubtleText, fontSize = 14.sp,
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
                            is ChatItem.AnnouncementItem -> CharcoalIncomingBubble(
                                "✦ AyX", item.a.title, item.a.body, item.a.publishedAt,
                            )
                            is ChatItem.UserMsgItem -> CharcoalOutgoingBubble(item.m.body, item.m.createdAt)
                            is ChatItem.ReplyItem -> CharcoalIncomingBubble(
                                "✦ AyX", "", item.r.body, item.r.createdAt,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.AttachFile, "Attach", tint = LightText.copy(alpha = 0.9f))
                }
                IconButton(onClick = { }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.PhotoCamera, "Camera", tint = LightText.copy(alpha = 0.9f))
                }
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it },
                    placeholder = { Text("Message", color = SubtleText, fontSize = 15.sp) },
                    shape = RoundedCornerShape(24.dp), maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                val canSend = inputText.trim().isNotEmpty() && !sending
                IconButton(
                    onClick = { doSend() }, enabled = canSend,
                    modifier = Modifier.size(46.dp).clip(CircleShape)
                        .background(if (canSend) Color(0xFF2C2C2E).copy(alpha = 0.92f) else Color.Transparent),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send, "Send",
                        tint = if (canSend) LightText else SubtleText,
                    )
                }
            }
        }
    }
}

@Composable
private fun CharcoalIncomingBubble(sender: String, title: String, body: String, ts: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = CharcoalBubble,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp),
            modifier = Modifier.widthIn(max = 300.dp), shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                Text(sender, color = AccentGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(2.dp))
                if (title.isNotBlank()) {
                    Text(title, color = LightText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(2.dp))
                }
                if (body.isNotBlank()) {
                    // Timestamp inline at bottom-end (Image 1 style)
                    Box(Modifier.fillMaxWidth()) {
                        Text(
                            body, color = LightText, fontSize = 15.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(end = 48.dp, bottom = 2.dp)
                        )
                        Text(
                            formatTime(ts), color = SubtleText, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp)
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(formatTime(ts), color = SubtleText, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CharcoalOutgoingBubble(body: String, ts: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.End) {
        Surface(
            color = CharcoalBubble,
            shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
            modifier = Modifier.widthIn(max = 300.dp), shadowElevation = 1.dp,
        ) {
            // Timestamp inline at bottom-end (Image 1 style)
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
                Text(
                    body, color = LightText, fontSize = 15.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(end = 48.dp, bottom = 2.dp)
                )
                Text(
                    formatTime(ts), color = SubtleText, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp)
                )
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0L) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase(Locale.getDefault())
}
