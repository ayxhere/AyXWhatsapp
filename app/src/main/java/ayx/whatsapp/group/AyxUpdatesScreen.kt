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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.unit.sp
import ayx.whatsapp.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// WhatsApp dark-mode colors (original)
private val WaChatBg = Color(0xFF0B141A)
private val WaHeaderBg = Color(0xFF1F2C34)
private val WaBubbleReceived = Color(0xFF1F2C34)
private val WaBubbleSent = Color(0xFF005C4B)
private val WaText = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)
private val WaGreen = Color(0xFF00A884)
private val WaStatusRing = Color(0xFF00A884)
private val WaInputBg = Color(0xFF1F2C34)

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
 * "✦ AyX updates" — WhatsApp-original 2-way chat.
 * Announcements arrive as received bubbles from "✦ AyX".
 * Users can type and send direct messages to AyX (input bar at bottom).
 * Admin replies appear as received bubbles. Statuses show as a horizontal
 * row of rings — tap to open the full-screen viewer. Background sync.
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
                // Refresh messages
                userMessages = AyxGroupApi.myMessages(token)
            } catch (_: Exception) {
                // Send failed — keep text for retry
            } finally {
                sending = false
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

    // Merge all items chronologically (oldest first, newest at bottom like WhatsApp)
    val chatItems: List<ChatItem> = remember(announcements, userMessages) {
        val list = ArrayList<ChatItem>()
        announcements.forEach { list.add(ChatItem.AnnouncementItem(it)) }
        userMessages.forEach { m ->
            list.add(ChatItem.UserMsgItem(m))
            m.replies.forEach { r -> list.add(ChatItem.ReplyItem(r)) }
        }
        list.sortedBy { it.ts }
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
        bottomBar = {
            // WhatsApp-style input bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(WaChatBg)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Message", color = WaTextSecondary, fontSize = 15.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = WaInputBg,
                        unfocusedContainerColor = WaInputBg,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = WaText,
                        unfocusedTextColor = WaText,
                        cursorColor = WaGreen,
                    ),
                    maxLines = 4,
                )
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = { doSend() },
                    enabled = !sending && inputText.trim().isNotEmpty(),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(WaGreen),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(vertical = 8.dp),
            reverseLayout = true, // newest at bottom
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

            if (chatItems.isEmpty() && statuses.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No updates yet. Send a message below to contact AyX.",
                            color = WaTextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                // Newest first (reverseLayout shows newest at bottom)
                items(chatItems.reversed(), key = {
                    when (it) {
                        is ChatItem.AnnouncementItem -> "a:${it.a.id}"
                        is ChatItem.UserMsgItem -> "m:${it.m.id}"
                        is ChatItem.ReplyItem -> "r:${it.r.id}"
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.AnnouncementItem -> AyxAnnouncementBubble(item.a)
                        is ChatItem.UserMsgItem -> AyxSentBubble(item.m.body, item.m.createdAt)
                        is ChatItem.ReplyItem -> AyxReplyBubble(item.r)
                    }
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
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 6.dp)) {
                Text(
                    "✦ AyX",
                    color = WaGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(2.dp))
                if (a.title.isNotBlank()) {
                    Text(a.title, color = WaText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(2.dp))
                }
                if (a.body.isNotBlank()) {
                    Text(a.body, color = WaText, fontSize = 15.sp, lineHeight = 20.sp)
                }
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

/** User's sent message (right-aligned, green — WhatsApp style). */
@Composable
private fun AyxSentBubble(body: String, ts: Long) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = WaBubbleSent,
            shape = RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 6.dp)) {
                Text(body, color = WaText, fontSize = 15.sp, lineHeight = 20.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(ts),
                        color = WaTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Admin's reply (received bubble from ✦ AyX). */
@Composable
private fun AyxReplyBubble(r: AdminReply) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = WaBubbleReceived,
            shape = RoundedCornerShape(2.dp, 12.dp, 12.dp, 12.dp),
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Column(Modifier.padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 6.dp)) {
                Text(
                    "✦ AyX",
                    color = WaGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(r.body, color = WaText, fontSize = 15.sp, lineHeight = 20.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        formatTime(r.createdAt),
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
