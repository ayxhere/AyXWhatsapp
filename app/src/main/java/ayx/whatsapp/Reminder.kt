package ayx.whatsapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import android.graphics.BitmapFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val REM_GREEN = Color(0xFF25D366)
private val REM_GREY = Color(0xFF9AA0A6)
private val REM_RED = Color(0xFFFF5A5A)
private val REM_ACCENT = Color(0xFFB69DF8)

private val remClock = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun remTime(ts: Long): String = if (ts > 0) remClock.format(Date(ts)).lowercase(Locale.getDefault()) else ""
private fun remDur(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000; val m = s / 60; val h = m / 60
    return when {
        h > 0 -> "${h}h ${m % 60}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}
private fun remName(jid: String): String =
    ContactStore.nameFor(jid) ?: SetName.get(jid) ?: ("+" + jid.substringBefore("@").substringBefore(":").filter { it.isDigit() })

/**
 * Presence Reminder — watch selected contacts, get notified when they come online,
 * and see a live came-online / went-offline timeline per contact. Updates in real time
 * (the list re-polls the gateway every few seconds; no manual refresh).
 */
@Composable
fun ReminderScreen(
    dpCache: MutableMap<String, ImageBitmap?>,
    deviceContacts: List<DeviceContact>,
    recentChats: List<String>,
    onEnsureContacts: () -> Unit,
    notify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<GatewayClient.ReminderEntry>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }            // 1s tick keeps "online for Xm" counting up live
    var showAdd by remember { mutableStateOf(false) }

    // real-time: re-poll the tracker every 3s while this screen is open
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { GatewayClient.reminderList() }.getOrNull()?.let { entries = it }
            delay(3000)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(6.dp))
        // intro / how it works
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(REM_ACCENT.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Notifications, null, tint = REM_ACCENT, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Online reminders", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text("Get a notification the moment a watched contact comes online. Their online / offline timeline is tracked in the background.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // add button
        Surface(onClick = { onEnsureContacts(); showAdd = true }, shape = RoundedCornerShape(18.dp), color = REM_GREEN.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(REM_GREEN.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PersonAdd, null, tint = REM_GREEN, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add contact to watch", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Pick from your chats or contacts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.Add, null, tint = REM_GREEN)
            }
        }

        if (entries.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                }
                Text("No contacts watched yet", style = MaterialTheme.typography.titleSmall)
                Text("Add someone to start tracking when they come online.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            entries.sortedByDescending { it.online }.forEach { entry ->
                key(entry.jid) {
                    tick.let { }   // read tick so the live duration recomputes each second
                    ReminderCard(entry, dpCache,
                        onRemove = {
                            scope.launch {
                                GatewayClient.reminderRemove(entry.jid)
                                entries = GatewayClient.reminderList()
                                notify("stopped watching")
                            }
                        })
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showAdd) {
        ReminderAddDialog(deviceContacts, recentChats, dpCache,
            onDismiss = { showAdd = false },
            onPick = { jid ->
                showAdd = false
                scope.launch {
                    val ok = GatewayClient.reminderAdd(jid)
                    entries = GatewayClient.reminderList()
                    notify(if (ok) "watching " + remName(jid) else "couldn't add")
                }
            })
    }
}

@Composable
private fun ReminderCard(entry: GatewayClient.ReminderEntry, dpCache: MutableMap<String, ImageBitmap?>, onRemove: () -> Unit) {
    val now = System.currentTimeMillis()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ring = if (entry.online) REM_GREEN else REM_GREY
                Box(Modifier.size(52.dp).border(2.dp, ring, CircleShape).padding(3.dp), contentAlignment = Alignment.Center) {
                    RemAvatar(entry.jid, remName(entry.jid), dpCache, 44.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(remName(entry.jid), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Circle, null, tint = if (entry.online) REM_GREEN else REM_GREY, modifier = Modifier.size(9.dp))
                        Spacer(Modifier.width(6.dp))
                        val statusLine = if (entry.online) {
                            val since = if (entry.since > 0) remDur(now - entry.since) else ""
                            if (since.isNotBlank()) "Online · for $since" else "Online"
                        } else {
                            val last = if (entry.lastSeen > 0) entry.lastSeen else entry.events.lastOrNull { !it.online }?.ts ?: 0L
                            if (last > 0) "Offline · last seen " + remTime(last) else "Offline"
                        }
                        Text(statusLine, style = MaterialTheme.typography.bodySmall,
                            color = if (entry.online) REM_GREEN else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "stop watching", tint = REM_RED) }
            }

            // timeline of came-online / went-offline events (newest first)
            val ev = entry.events
            if (ev.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                Spacer(Modifier.height(8.dp))
                val shown = ev.indices.reversed().take(8)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEach { i ->
                        val e = ev[i]
                        val dur = if (!e.online) {
                            val prevOnline = (i - 1 downTo 0).firstOrNull { ev[it].online }
                            if (prevOnline != null) e.ts - ev[prevOnline].ts else 0L
                        } else 0L
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Circle, null, tint = if (e.online) REM_GREEN else REM_GREY, modifier = Modifier.size(8.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (e.online) "Came online" else "Went offline",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                                color = if (e.online) REM_GREEN else MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.width(8.dp))
                            Text(remTime(e.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!e.online && dur > 0) {
                                Spacer(Modifier.width(8.dp))
                                Text("· was online " + remDur(dur), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Waiting for activity…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReminderAddDialog(deviceContacts: List<DeviceContact>, recentChats: List<String>, dpCache: MutableMap<String, ImageBitmap?>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    data class Cand(val jid: String, val name: String, val sub: String)
    var query by remember { mutableStateOf("") }
    val candidates = remember(deviceContacts, recentChats) {
        val out = LinkedHashMap<String, Cand>()
        // recent chats first (individual only)
        recentChats.filter { it.endsWith("@s.whatsapp.net") }.forEach { jid ->
            val d = jid.substringBefore("@").filter { it.isDigit() }
            if (d.isNotBlank()) out.getOrPut(d) { Cand(jid, remName(jid), "+$d") }
        }
        deviceContacts.forEach { c ->
            val d = c.number.filter { it.isDigit() }
            if (d.isNotBlank() && !out.containsKey(d)) out[d] = Cand(d + "@s.whatsapp.net", c.name.ifBlank { "+$d" }, "+$d")
        }
        out.values.toList()
    }
    val filtered = candidates.filter { query.isBlank() || it.name.contains(query, true) || it.sub.contains(query) }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PersonAdd, null, tint = REM_GREEN)
                    Spacer(Modifier.width(10.dp))
                    Text("Watch a contact", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "close") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search chats & contacts") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No chats or contacts yet. Grant contacts permission or start a chat first.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(filtered, key = { _, c -> c.jid }) { _, c ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(c.jid) }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RemAvatar(c.jid, c.name, dpCache, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemAvatar(jid: String, name: String, cache: MutableMap<String, ImageBitmap?>, size: androidx.compose.ui.unit.Dp) {
    LaunchedEffect(jid) {
        if (!cache.containsKey(jid)) {
            val b = GatewayClient.dpBytes(jid)
            cache[jid] = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val dp = cache[jid]
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (dp != null) Image(dp, "dp", Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}
