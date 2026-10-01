package ayx.whatsapp

// cnm.kt — "Contact N Memory": the AI Memory page + the reply-language selector.
// The AI (node side) saves every contact's lid, name, language and full chat history; this screen
// shows that memory and lets you clear it. Kept self-contained (own small UI helpers) + iOS-smooth.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import org.json.JSONObject
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val AVATAR_COLORS = listOf(
    Color(0xFF82AAFF), Color(0xFF4DD0C4), Color(0xFFFFB26B),
    Color(0xFFB69DF8), Color(0xFFFF7EB6), Color(0xFF4DD07A),
)

private fun langLabel(code: String): String = when (code) {
    "bangla-script" -> "Bangla"
    "hindi-script" -> "Hindi"
    else -> ""
}

private fun timeOf(ts: Long): String =
    if (ts <= 0) "" else runCatching { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts)) }.getOrDefault("")

@Composable
private fun InitialAvatar(label: String, size: Dp = 44.dp) {
    val ch = label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val col = AVATAR_COLORS[abs(label.hashCode()) % AVATAR_COLORS.size]
    Box(Modifier.size(size).clip(CircleShape).background(col.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Text(ch, color = col, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    }
}

// ---- Reply-language selector: pick ONE language so the AI stops mixing Hindi/Bangla ----
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LanguageSelector(current: String, onSelect: (String) -> Unit) {
    val opts = listOf(
        "auto" to "Auto (mirror)",
        "hinglish" to "Hinglish",
        "bangla" to "Roman Bangla",
        "english" to "English",
        "hindi" to "हिंदी",
        "banglascript" to "বাংলা",
        "custom" to "Custom",
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        opts.forEach { (key, label) ->
            FilterChip(selected = current == key, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

// ---- AI Memory screen ----
@Composable
internal fun AiMemoryScreen() {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<GatewayClient.MemContact>>(emptyList()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }

    fun refresh() { scope.launch { loading = true; items = GatewayClient.getAiMemory(); loading = false } }
    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI Memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (loading) "Loading…" else "${items.size} contacts remembered",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { refresh() }) { Text("Refresh") }
            }
        }

        if (!loading && items.isEmpty()) {
            Text("Abhi koi memory nahi. Jaise log message karenge, yahan unka naam, number/lid, language aur poori chat history save hoti rahegi — aur AI wahi dekh ke reply karega.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
        }

        items.forEach { c ->
            MemCard(c, expanded == c.jid,
                onToggle = { expanded = if (expanded == c.jid) null else c.jid },
                onClear = { scope.launch { GatewayClient.clearAiMemory(c.jid); if (expanded == c.jid) expanded = null; refresh() } })
        }

        if (items.isNotEmpty()) {
            OutlinedButton(onClick = { confirmClearAll = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear all memory") }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all memory?") },
            text = { Text("Saari contacts ki chat history aur saved info delete ho jayegi.") },
            confirmButton = { TextButton(onClick = { confirmClearAll = false; scope.launch { GatewayClient.clearAiMemory(""); refresh() } }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MemCard(c: GatewayClient.MemContact, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit) {
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "arrow")
    val title = c.name.ifBlank { c.number.ifBlank { "Unknown" } }
    val lang = langLabel(c.lang)
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(title)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = buildString {
                        if (c.number.isNotBlank()) append(c.number)
                        append(if (isEmpty()) "" else " · ")
                        append("${c.msgCount} msgs")
                        if (lang.isNotBlank()) append(" · $lang")
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Filled.KeyboardArrowDown, "expand", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(arrow))
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(180)) + expandVertically(tween(200)), exit = fadeOut(tween(140)) + shrinkVertically(tween(180))) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))
                    if (c.lid.isNotBlank()) {
                        Text("lid: ${c.lid}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (c.recent.isEmpty()) {
                        Text("No saved messages yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        c.recent.forEach { m -> LogLine(m) }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onClear) { Text("Clear this chat's memory") }
                }
            }
        }
    }
}

@Composable
private fun LogLine(m: GatewayClient.MemMsg) {
    val mine = m.role == "assistant"   // assistant = the AI (Raju / you); user = them
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(14.dp), color = bubbleColor, modifier = Modifier.fillMaxWidth(0.82f)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(if (mine) "AI" else "Them", style = MaterialTheme.typography.labelSmall,
                    color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(m.content, style = MaterialTheme.typography.bodyMedium)
                val t = timeOf(m.ts)
                if (t.isNotBlank()) Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

// ---- AI Voice picker: male/female artists, demo-play then select (free Edge voices, no key) ----
private data class VoiceOpt(val id: String, val label: String, val sub: String)

// Groq PlayAI voices (English) — run on YOUR Groq key, real & different male/female. Most reliable here.
private val GROQ_FEMALE = listOf(
    VoiceOpt("Arista-PlayAI", "Arista", "English · natural"),
    VoiceOpt("Celeste-PlayAI", "Celeste", "English · warm"),
    VoiceOpt("Deedee-PlayAI", "Deedee", "English · soft"),
    VoiceOpt("Gail-PlayAI", "Gail", "English · bright"),
    VoiceOpt("Quinn-PlayAI", "Quinn", "English · calm"),
    VoiceOpt("Cheyenne-PlayAI", "Cheyenne", "English · young"),
)
private val GROQ_MALE = listOf(
    VoiceOpt("Fritz-PlayAI", "Fritz", "English · deep"),
    VoiceOpt("Atlas-PlayAI", "Atlas", "English · strong"),
    VoiceOpt("Mason-PlayAI", "Mason", "English · casual"),
    VoiceOpt("Mitch-PlayAI", "Mitch", "English · friendly"),
    VoiceOpt("Thunder-PlayAI", "Thunder", "English · bold"),
    VoiceOpt("Calum-PlayAI", "Calum", "English · chill"),
)
// Hindi / Bangla neural voices (Edge) — use these when reply language is Hindi/Bangla (need open network)
private val INDIC_VOICES = listOf(
    VoiceOpt("hi-IN-SwaraNeural", "Swara", "Hindi · female 🇮🇳"),
    VoiceOpt("hi-IN-MadhurNeural", "Madhur", "Hindi · male 🇮🇳"),
    VoiceOpt("bn-IN-TanishaaNeural", "Tanishaa", "Bangla · female 🇮🇳"),
    VoiceOpt("bn-IN-BashkarNeural", "Bashkar", "Bangla · male 🇮🇳"),
)
// reliable backup (Amazon Polly via StreamElements) — used if the above don't play
private val BACKUP_VOICES = listOf(
    VoiceOpt("Aditi", "Aditi", "Backup · Indian"),
    VoiceOpt("Joanna", "Joanna", "Backup · US female"),
    VoiceOpt("Matthew", "Matthew", "Backup · US male"),
)

@Composable
internal fun VoicePickerScreen(settings: GatewayClient.Settings, onToggle: (JSONObject) -> Unit, ctx: Context) {
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(settings.aiTtsVoice) }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

    fun stop() { runCatching { if (player.isPlaying) player.stop() }; playing = null; loading = null }

    fun demo(v: String) {
        if (playing == v) { stop(); return }
        runCatching {
            player.reset()
            player.setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            loading = v; playing = null
            player.setDataSource(GatewayClient.ttsDemoUrl(v))
            player.setOnPreparedListener { loading = null; playing = v; it.start() }
            player.setOnCompletionListener { playing = null }
            player.setOnErrorListener { _, _, _ -> loading = null; playing = null; Toast.makeText(ctx, "Demo nahi chala, dobara try kar", Toast.LENGTH_SHORT).show(); true }
            player.prepareAsync()
        }.onFailure { loading = null; Toast.makeText(ctx, "Demo failed", Toast.LENGTH_SHORT).show() }
    }

    fun pick(v: String) {
        selected = v
        onToggle(JSONObject().put("aiTtsVoice", v))
        Toast.makeText(ctx, "Voice set", Toast.LENGTH_SHORT).show()
    }

    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Text("Voice chuno — ▶ se demo suno, phir tap karke select. AI voice-note isi awaaz me reply dega.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
        Text("English voices (neeche) teri Groq key se chalti hain — asli, alag-alag. Pehli baar ek baar console.groq.com pe PlayAI TTS ki terms accept karni pad sakti hai.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        FilledTonalButton(onClick = {
            checking = true
            scope.launch {
                val r = GatewayClient.voiceCheck(selected)
                checkResult = if (r.optBoolean("ok", false)) "✅ Groq voice chal rahi hai! Ab koi bhi English voice select kar."
                else (r.optString("message").ifBlank { "Groq TTS abhi nahi chala." })
                checking = false
            }
        }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (checking) "Checking…" else "Check Groq voice")
        }

        Text("ENGLISH · FEMALE (Groq)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        GROQ_FEMALE.forEach { v -> VoiceRow(v, selected == v.id, playing == v.id, loading == v.id, { demo(v.id) }, { pick(v.id) }) }

        Text("ENGLISH · MALE (Groq)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        GROQ_MALE.forEach { v -> VoiceRow(v, selected == v.id, playing == v.id, loading == v.id, { demo(v.id) }, { pick(v.id) }) }

        Text("HINDI / BANGLA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        INDIC_VOICES.forEach { v -> VoiceRow(v, selected == v.id, playing == v.id, loading == v.id, { demo(v.id) }, { pick(v.id) }) }

        Text("BACKUP (agar upar wale na chale)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        BACKUP_VOICES.forEach { v -> VoiceRow(v, selected == v.id, playing == v.id, loading == v.id, { demo(v.id) }, { pick(v.id) }) }

        Spacer(Modifier.height(20.dp))
    }

    if (checkResult != null) {
        AlertDialog(
            onDismissRequest = { checkResult = null },
            title = { Text("Groq voice") },
            text = { Text(checkResult ?: "") },
            confirmButton = { TextButton(onClick = { checkResult = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun VoiceRow(v: VoiceOpt, isSelected: Boolean, isPlaying: Boolean, isLoading: Boolean, onPlay: () -> Unit, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(v.label, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(v.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
                Icon(Icons.Filled.Check, "selected", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = onPlay) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, "demo", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
