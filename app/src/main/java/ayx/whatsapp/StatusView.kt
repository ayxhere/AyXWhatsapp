package ayx.whatsapp

import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// "Today, 1:04 am" / "Yesterday, 11:43 pm" / "Mon, 11:43 pm" / "12 Oct, 11:43 pm" — WhatsApp style
private fun viewTime(ts: Long): String {
    if (ts <= 0L) return ""
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)).lowercase(Locale.getDefault())
    val dayDiff = ((now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis -
        c.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L).toInt()
    val day = when {
        dayDiff <= 0 -> "Today"
        dayDiff == 1 -> "Yesterday"
        dayDiff < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ts))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
    return "$day, $time"
}

/**
 * WhatsApp-style bottom sheet listing who viewed the user's status: one row per PERSON (the gateway
 * merges @lid / phone-number / per-device ids), named from the device contacts first, then the
 * WhatsApp profile name — raw LID digits are never shown. The window behind is blurred (Android 12+)
 * on top of the Compose blur the status screen applies, and the status is paused while this is open.
 */
@Composable
fun StatusViewersSheet(statusIds: List<String>, dpCache: MutableMap<String, ImageBitmap?>, onClose: () -> Unit) {
    var viewers by remember { mutableStateOf<List<GatewayClient.StatusViewer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(statusIds, reload) {
        loading = true
        viewers = GatewayClient.getStatusViewers(statusIds)
        loading = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val win = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            win?.let { w ->
                w.setGravity(Gravity.BOTTOM)
                w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                w.setDimAmount(0.30f)
                if (Build.VERSION.SDK_INT >= 31) {
                    w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply { blurBehindRadius = 45 }   // blurs the video/photo window underneath
                }
            }
        }
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
            contentAlignment = Alignment.BottomCenter) {
            Surface(
                Modifier.fillMaxWidth().fillMaxHeight(0.62f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = Color(0xFF111A1F)
            ) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Viewed by " + viewers.size, color = Color.White, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "more", tint = Color.White) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Refresh") }, onClick = { menu = false; reload++ })
                            }
                        }
                    }
                    if (loading) {
                        Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                    } else if (viewers.isEmpty()) {
                        Text("No views yet", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(24.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(viewers, key = { it.jid }) { v ->
                                val device = ContactStore.nameFor(v.jid) ?: v.lid.takeIf { it.isNotBlank() }?.let { ContactStore.nameFor(it) }
                                val name = when {
                                    !device.isNullOrBlank() -> device
                                    v.name.isNotBlank() -> v.name
                                    v.jid.endsWith("@s.whatsapp.net") -> "+" + v.jid.substringBefore("@").filter { it.isDigit() }
                                    else -> "Unknown contact"   // unresolved hidden id — don't show its digits
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(v.jid, name, dpCache, 50.dp, CircleShape)
                                    Spacer(Modifier.width(16.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                        val t = viewTime(v.ts)
                                        if (t.isNotBlank()) Text(t, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
