package ayx.whatsapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import android.util.Base64
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import java.io.FileOutputStream
import java.io.File
import android.app.KeyguardManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import android.provider.ContactsContract
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val APP_NAME = "WhatsAyX"
private val IOS_BLUE = Color(0xFF0A84FF)
private val AYX_GREEN = Color(0xFF25D366)
private val AYX_RED = Color(0xFFFF5A5A)

// One shared background for ALL dialogs/sheets app-wide. Follows the active theme:
// black in dark / amoled, white in light. (A hair off pure values so there's a faint edge.)
@Composable
internal fun dialogBg(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color(0xFF0A0A0A) else Color(0xFFFFFFFF)
}

private const val PRIVACY_TEXT = """WA Gateway runs entirely on your device. It does not collect, sell, or send your chats, contacts, or personal data to us or any third party.

1. Local only. Your WhatsApp session, messages, and media stay on your phone. There is no server owned by us.

2. WhatsApp. The app connects to WhatsApp's own servers to work as a linked device, exactly like WhatsApp Web.

3. AI auto-reply (optional). If you turn on AI reply, only the incoming message text is sent to the AI provider you configure (e.g. Groq) to generate a reply. This uses your own API key and is your choice. Keep it off if you don't want it.

4. Media and history. Saved media and message logs are stored only inside this app on your device. Unlink/reset or clearing app data removes them.

5. No tracking. No analytics, no ads, no telemetry.

6. Your responsibility. This is an unofficial WhatsApp tool. WhatsApp may restrict or ban accounts that use automation. Do not use it for spam. Use at your own risk.

By continuing you agree to use this app responsibly and accept these terms."""

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun handleDeepLink(intent: Intent?) {
        intent?.getStringExtra("openChat")?.let { AppNav.pendingOpenChat.value = it }
        intent?.data?.let { uri ->
            val fromPath = uri.pathSegments.firstOrNull()?.filter { it.isDigit() }
            val fromQuery = uri.getQueryParameter("phone")?.filter { it.isDigit() }
            val num = listOf(fromPath, fromQuery).firstOrNull { !it.isNullOrBlank() && it.length in 8..15 }
            if (!num.isNullOrBlank()) AppNav.pendingOpenChat.value = num + "@s.whatsapp.net"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        handleDeepLink(intent)

        StatusData.init(applicationContext)
        StatusFlags.init(applicationContext)
        SetName.init(applicationContext)
        ThemeStore.init(applicationContext)
        ChatStyle.init(applicationContext)
        TranslateStore.init(applicationContext)
        FontStore.init(applicationContext)
        EmojiStore.init(applicationContext)
        setContent {
            val sysDark = isSystemInDarkTheme()
            val mode = ThemeStore.mode.value
            val amoled = mode == "amoled"
            val dark = when (mode) { "light" -> false; "dark", "amoled" -> true; else -> sysDark }
            val accent = ThemeStore.accentColor()
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val scheme = base.copy(
                primary = accent,
                secondary = accent,
                tertiary = accent,
                background = if (amoled) Color(0xFF000000) else base.background,
                surface = if (amoled) Color(0xFF0B090D) else base.surface,
                surfaceVariant = if (amoled) Color(0xFF161318) else base.surfaceVariant,
            )
            val appTypo = FontStore.typographyFor(FontStore.appFamily(LocalContext.current))
            MaterialTheme(colorScheme = scheme, typography = appTypo) {
                val view = LocalView.current
                val barColor = MaterialTheme.colorScheme.surface
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                }
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize()) {
                        val ctx = LocalContext.current
                        val prefs = remember { ctx.getSharedPreferences("wagw", Context.MODE_PRIVATE) }
                        var agreed by remember { mutableStateOf(prefs.getBoolean("privacy_agreed", false)) }
                        if (!agreed) PrivacyGate { prefs.edit().putBoolean("privacy_agreed", true).apply(); agreed = true }
                        else GatewayApp()
                    }
                    var splashDone by remember { mutableStateOf(false) }
                    if (!splashDone) SplashScreen(dark) { splashDone = true }
                }
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
private fun fmt(ts: Long) = if (ts > 0) timeFmt.format(Date(ts)).lowercase(Locale.getDefault()) else ""

object ChatFlags {
    val hidden = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val locked = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    // chat jid -> epoch millis until which the chat is muted (Long.MAX_VALUE = permanently)
    val muteUntil = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    const val MUTE_FOREVER = Long.MAX_VALUE
    var reveal by androidx.compose.runtime.mutableStateOf(false)
    var prefs: android.content.SharedPreferences? = null
    private fun save() {
        prefs?.edit()
            ?.putStringSet("hidden", hidden.keys.toSet())
            ?.putStringSet("locked", locked.keys.toSet())
            ?.putStringSet("mute", muteUntil.entries.map { it.key + "|" + it.value }.toSet())
            ?.apply()
    }
    fun loadMutes() { prefs?.getStringSet("mute", emptySet())?.forEach { val i = it.lastIndexOf('|'); if (i > 0) muteUntil[it.substring(0, i)] = it.substring(i + 1).toLongOrNull() ?: 0L } }
    // load from prefs on demand (e.g. the background service checking mute before posting a notification)
    fun ensure(ctx: android.content.Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("wagw", android.content.Context.MODE_PRIVATE)
        prefs = p
        p.getStringSet("hidden", emptySet())?.forEach { hidden[it] = true }
        p.getStringSet("locked", emptySet())?.forEach { locked[it] = true }
        loadMutes()
    }
    fun toggleHidden(jid: String) { if (hidden[jid] == true) hidden.remove(jid) else hidden[jid] = true; save() }
    fun toggleLocked(jid: String) { if (locked[jid] == true) locked.remove(jid) else locked[jid] = true; save() }
    fun muteFor(jid: String, millis: Long) { muteUntil[jid] = if (millis >= MUTE_FOREVER) MUTE_FOREVER else System.currentTimeMillis() + millis; save() }
    fun unmute(jid: String) { muteUntil.remove(jid); save() }
    fun isMuted(jid: String): Boolean { val u = muteUntil[jid] ?: return false; if (u == MUTE_FOREVER) return true; if (u > System.currentTimeMillis()) return true; muteUntil.remove(jid); return false }
}

// per-status-sender flags — same idea as ChatFlags but for the Status tab (long-press = mute / hide / lock)
object StatusFlags {
    val muted = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val hidden = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    val locked = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var reveal by androidx.compose.runtime.mutableStateOf(false)
    private var prefs: android.content.SharedPreferences? = null
    // status senders come in several jid shapes (…@s.whatsapp.net, …:12@…, lid) — key by the digits only so a flag sticks
    fun keyOf(sender: String): String = sender.substringBefore("@").substringBefore(":").filter { it.isDigit() }.ifBlank { sender }
    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("statusflags", Context.MODE_PRIVATE)
        prefs?.getStringSet("muted", emptySet())?.forEach { muted[it] = true }
        prefs?.getStringSet("hidden", emptySet())?.forEach { hidden[it] = true }
        prefs?.getStringSet("locked", emptySet())?.forEach { locked[it] = true }
    }
    private fun save() { prefs?.edit()?.putStringSet("muted", muted.keys.toSet())?.putStringSet("hidden", hidden.keys.toSet())?.putStringSet("locked", locked.keys.toSet())?.apply() }
    fun isMuted(sender: String) = muted[keyOf(sender)] == true
    fun isHidden(sender: String) = hidden[keyOf(sender)] == true
    fun isLocked(sender: String) = locked[keyOf(sender)] == true
    fun toggleMuted(sender: String) { val k = keyOf(sender); if (muted[k] == true) muted.remove(k) else muted[k] = true; save() }
    fun toggleHidden(sender: String) { val k = keyOf(sender); if (hidden[k] == true) hidden.remove(k) else hidden[k] = true; save() }
    fun toggleLocked(sender: String) { val k = keyOf(sender); if (locked[k] == true) locked.remove(k) else locked[k] = true; save() }
}

// Message translation: mode (off / manual double-tap / auto-all) + target language, persisted.
// cache holds msgId -> translated text so a translated bubble shows its translation under the original.
object TranslateStore {
    var mode by androidx.compose.runtime.mutableStateOf("manual")   // off | manual | auto
    var lang by androidx.compose.runtime.mutableStateOf("English")
    var configured by androidx.compose.runtime.mutableStateOf(false)   // user picked a language at least once
    val cache = androidx.compose.runtime.mutableStateMapOf<String, String>()
    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("translate", Context.MODE_PRIVATE)
        prefs = p
        mode = p.getString("mode", "manual") ?: "manual"
        lang = p.getString("lang", "English") ?: "English"
        configured = p.getBoolean("configured", false)
    }
    private fun save() { prefs?.edit()?.putString("mode", mode)?.putString("lang", lang)?.putBoolean("configured", configured)?.apply() }
    fun chooseMode(m: String) { mode = m; save() }
    fun chooseLang(l: String) { lang = l; configured = true; save() }
    fun enabled() = mode != "off"
}


private fun chatTitle(msgs: List<GatewayClient.Msg>): String {
    val chat = msgs.firstOrNull()?.chat ?: return "Unknown"
    ContactStore.nameFor(chat)?.let { if (it.isNotBlank()) return it }
    msgs.firstOrNull { !it.fromMe && it.name.isNotBlank() }?.let { return it.name }
    return when {
        chat.endsWith("@g.us") -> "Group"
        chat.endsWith("@lid") -> "Unknown contact"   // hidden WhatsApp id, not a phone number
        else -> { val n = chat.substringBefore("@").filter { it.isDigit() }; if (n.isNotBlank()) "+" + n else "Unknown" }
    }
}

private fun decodeThumb(b64: String?): ImageBitmap? = b64?.let {
    runCatching { val by = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(by, 0, by.size)?.asImageBitmap() }.getOrNull()
}

private fun downloadMedia(scope: CoroutineScope, ctx: Context, m: GatewayClient.Msg, onLog: (String) -> Unit) {
    val name = m.mediaName ?: return
    scope.launch {
        val bytes = GatewayClient.mediaBytes(name)
        if (bytes == null) { onLog("turn on Save media (Settings) to download"); return@launch }
        val ok = MediaSaver.save(ctx, bytes, name, m.mediaType ?: "document")
        onLog(if (ok) "saved to $APP_NAME folder" else "save failed")
    }
}

private suspend fun loadPreview(ctx: Context, m: GatewayClient.Msg): ImageBitmap? {
    val name = m.mediaName ?: return null
    return withContext(Dispatchers.IO) {
        try {
            if (m.mediaType == "video") {
                val r = MediaMetadataRetriever()
                r.setDataSource(GatewayClient.mediaUrl(name), HashMap<String, String>())
                val bmp = r.getFrameAtTime(1_000_000L)
                r.release()
                bmp?.asImageBitmap()
            } else {
                val bytes = GatewayClient.mediaBytes(name) ?: return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }
        } catch (e: Exception) { null }
    }
}

private fun shareMedia(scope: CoroutineScope, ctx: Context, m: GatewayClient.Msg, onLog: (String) -> Unit) {
    val name = m.mediaName ?: return
    scope.launch {
        val bytes = GatewayClient.mediaBytes(name)
        if (bytes == null) { onLog("turn on Save media to share"); return@launch }
        try {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                val f = File(dir, name); f.writeBytes(bytes)
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            }
            val mime = when (m.mediaType) { "image" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"; else -> "*/*" }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { onLog("share failed: ${e.message}") }
    }
}

private fun decodeScaled(path: String, maxDim: Int): android.graphics.Bitmap? {
    return try {
        val o1 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o1)
        var sample = 1
        val big = maxOf(o1.outWidth, o1.outHeight)
        while (big / sample > maxDim) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, o2)
    } catch (e: Exception) { null }
}

data class DeviceContact(val name: String, val number: String)

private fun normNumber(raw: String): String {
    var d = raw.filter { it.isDigit() }
    if (d.length == 11 && d.startsWith("0")) d = "91" + d.substring(1)
    else if (d.length == 10) d = "91" + d
    return d
}

private fun loadDeviceContacts(ctx: Context): List<DeviceContact> {
    val out = ArrayList<DeviceContact>()
    val seen = HashSet<String>()
    try {
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cur?.use { c ->
            val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = if (ni >= 0) (c.getString(ni) ?: "") else ""
                val raw = if (pi >= 0) (c.getString(pi) ?: "") else ""
                val num = normNumber(raw)
                if (num.length < 10) continue
                if (seen.add(num)) out.add(DeviceContact(name.ifBlank { num }, num))
            }
        }
    } catch (_: Exception) {}
    return out
}

private fun queryName(ctx: Context, uri: Uri): String? =
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

@Composable
fun PrivacyGate(onAgree: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(26.dp))
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(AYX_GREEN.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Shield, null, tint = AYX_GREEN, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Privacy Policy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Your data never leaves your device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f), modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAgree, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("I Agree & Continue", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))
    }
}

// Smooth launch splash: gradient logo scales in over a soft breathing glow, name + three-dot pulse, then fades out.
@Composable
private fun SplashScreen(dark: Boolean, onDone: () -> Unit) {
    var start by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (start) 1f else 0.70f, animationSpec = tween(620, easing = FastOutSlowInEasing), label = "splashScale")
    val appear by animateFloatAsState(if (start) 1f else 0f, animationSpec = tween(520), label = "splashAppear")
    val out by animateFloatAsState(if (gone) 0f else 1f, animationSpec = tween(440), label = "splashOut")
    val inf = rememberInfiniteTransition(label = "splashGlow")
    val glow by inf.animateFloat(0.30f, 0.85f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "splashGlowV")
    LaunchedEffect(Unit) { start = true; delay(1450); gone = true; delay(450); onDone() }
    val bg = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = out }.background(bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(154.dp).graphicsLayer { alpha = glow * appear }
                    .background(Brush.radialGradient(listOf(AYX_GREEN.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
                Box(Modifier.size(92.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = appear }
                    .clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(AYX_GREEN, IOS_BLUE))),
                    contentAlignment = Alignment.Center) {
                    Text("Ay", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displaySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(APP_NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = if (dark) Color.White else Color(0xFF111111), modifier = Modifier.graphicsLayer { alpha = appear })
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.graphicsLayer { alpha = appear }) {
                repeat(3) { i ->
                    val d by inf.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "splashDot$i")
                    Box(Modifier.size(8.dp).graphicsLayer { alpha = d }.clip(CircleShape).background(AYX_GREEN))
                }
            }
        }
    }
}

class OptMsg(val chat: String, val text: String, val ts: Long, val quotedText: String? = null)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GatewayApp() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var status by remember { mutableStateOf(GatewayClient.Status()) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastQrHash by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(GatewayClient.Settings()) }
    var messages by remember { mutableStateOf(listOf<GatewayClient.Msg>()) }
    var settingsLoaded by remember { mutableStateOf(false) }

    var screen by remember { mutableStateOf("chats") }
    var openChat by remember { mutableStateOf<String?>(null) }
    var settingsPage by remember { mutableStateOf("home") }
    var wallpaperVersion by remember { mutableStateOf(0) }
    var showSetName by remember { mutableStateOf(false) }
    var forwardMsg by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    var viewImg by remember { mutableStateOf<ImageBitmap?>(null) }
    var viewVideoUrl by remember { mutableStateOf<String?>(null) }
    var dpView by remember { mutableStateOf<String?>(null) }   // jid whose profile photo is previewed full-screen
    var toast by remember { mutableStateOf<String?>(null) }
    val optimistic = remember { mutableStateListOf<OptMsg>() }
    val dpCache = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val previewCache = remember { mutableStateMapOf<String, ImageBitmap?>() }

    fun notify(s: String) { toast = s }
    fun openMedia(m: GatewayClient.Msg) {
        if (m.mediaType == "video") {
            if (m.mediaName != null) viewVideoUrl = GatewayClient.mediaUrl(m.mediaName!!) else notify("turn on Save media to play video")
            return
        }
        scope.launch {
            val full = m.mediaName?.let { GatewayClient.mediaBytes(it) }
            val bmp = if (full != null) BitmapFactory.decodeByteArray(full, 0, full.size)?.asImageBitmap() else decodeThumb(m.thumb)
            if (bmp != null) viewImg = bmp else notify("turn on Save media to view full image")
        }
    }

    // file picker for sending media
    var pendingMedia by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var chatsPage by remember { mutableStateOf(0) }
    var storyView by remember { mutableStateOf<String?>(null) }
    var myJid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status.registered) { if (status.registered) { val j = GatewayClient.getMe(); if (j.isNotBlank()) myJid = j } }
    var pendingLockOpen by remember { mutableStateOf<String?>(null) }
    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pendingLockOpen?.let { openChat = it }
        pendingLockOpen = null
    }
    val revealUnlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) ChatFlags.reveal = true
    }
    // reveal hidden statuses (keyguard-gated, like hidden chats)
    val statusRevealUnlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) StatusFlags.reveal = true
    }
    // open a locked status only after device unlock
    var pendingStatusOpen by remember { mutableStateOf<String?>(null) }
    val statusUnlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) pendingStatusOpen?.let { storyView = it }
        pendingStatusOpen = null
    }
    var pendingStatus by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var statusFabMenu by remember { mutableStateOf(false) }
    var textStatusDlg by remember { mutableStateOf(false) }
    val statusPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            val mime = ctx.contentResolver.getType(uri) ?: ""
            pendingStatus = uri to (if (mime.startsWith("video")) "video" else "image")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && openChat != null) {
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val type = when {
                mime.startsWith("image") -> "image"; mime.startsWith("video") -> "video"
                mime.startsWith("audio") -> "audio"; else -> "document"
            }
            pendingMedia = uri to type
        }
    }

    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    fun loadWallpaper() {
        val f = File(ctx.filesDir, "wallpaper.jpg")
        wallpaper = if (f.exists()) runCatching { decodeScaled(f.absolutePath, 1440)?.asImageBitmap() }.getOrNull() else null
    }
    LaunchedEffect(Unit) { loadWallpaper() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val jid = openChat; val u = cameraUri
        if (ok && u != null && jid != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                if (bytes != null) { notify("sending photo…"); GatewayClient.sendMedia(jid, Base64.encodeToString(bytes, Base64.NO_WRAP), "image", "camera.jpg", "") }
            } catch (e: Exception) { notify("send failed: ${e.message}") }
        }
    }
    val cameraPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraUri?.let { cameraLauncher.launch(it) } else notify("camera permission needed")
    }
    fun openCamera() {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, "cam_" + System.currentTimeMillis() + ".jpg")
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        cameraUri = u
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cameraLauncher.launch(u)
        else cameraPerm.launch(Manifest.permission.CAMERA)
    }

    var deviceContacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(false) }
    fun doLoadContacts() {
        contactsLoading = true
        scope.launch {
            val all = withContext(Dispatchers.IO) { loadDeviceContacts(ctx) }
            all.forEach { ContactStore.put(it.number, it.name) }
            val reg = GatewayClient.onWhatsApp(all.map { it.number })   // number -> lid?
            all.forEach { c -> reg[c.number]?.let { lid -> if (lid.isNotBlank()) ContactStore.put(lid, c.name) } }
            deviceContacts = if (reg.isEmpty()) all else all.filter { reg.containsKey(it.number) }
            contactsLoading = false
        }
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doLoadContacts() else notify("contacts permission needed")
    }
    fun ensureContacts() {
        if (deviceContacts.isNotEmpty() || contactsLoading) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) doLoadContacts()
        else contactsPerm.launch(Manifest.permission.READ_CONTACTS)
    }

    var statuses by remember { mutableStateOf(StatusData.load()) }

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    File(ctx.filesDir, "wallpaper.jpg").outputStream().use { input.copyTo(it) }
                }
            }
            loadWallpaper(); wallpaperVersion++; notify("wallpaper set")
        }
    }
    val profilePicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                if (bytes != null) { GatewayClient.setProfilePicture(Base64.encodeToString(bytes, Base64.NO_WRAP)); notify("profile photo updated") }
            } catch (e: Exception) { notify("failed: ${e.message}") }
        }
    }

    var chatWp by remember { mutableStateOf<ImageBitmap?>(null) }
    fun loadChatWp(jid: String?) {
        val perChat = jid?.let { File(ctx.filesDir, "wp_${it.hashCode()}.jpg") }
        val f = if (perChat != null && perChat.exists()) perChat else File(ctx.filesDir, "wallpaper.jpg")
        chatWp = if (f.exists()) runCatching { decodeScaled(f.absolutePath, 1440)?.asImageBitmap() }.getOrNull() else null
    }
    val chatWallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val jid = openChat
        if (uri != null && jid != null) scope.launch {
            withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    File(ctx.filesDir, "wp_${jid.hashCode()}.jpg").outputStream().use { input.copyTo(it) }
                }
            }
            loadChatWp(jid); notify("chat wallpaper set")
        }
    }
    var lastNotifTs by remember { mutableStateOf(System.currentTimeMillis()) }
    var showNewChat by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var chatPresence by remember { mutableStateOf<GatewayClient.Presence?>(null) }
    val blkPrefs = remember { ctx.getSharedPreferences("wagw", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        ChatFlags.prefs = blkPrefs
        ChatFlags.reveal = false
        StatusFlags.reveal = false
        blkPrefs.getStringSet("hidden", emptySet())!!.forEach { ChatFlags.hidden[it] = true }
        blkPrefs.getStringSet("locked", emptySet())!!.forEach { ChatFlags.locked[it] = true }
        ChatFlags.loadMutes()
    }
    var blockedJids by remember { mutableStateOf(blkPrefs.getStringSet("blocked", emptySet())!!.toSet()) }
    LaunchedEffect(openChat) { loadChatWp(openChat); NodeService.currentOpenChat = openChat; openChat?.let { NotificationHelper.cancel(ctx, it) } }
    LaunchedEffect(openChat) {
        chatPresence = null
        val c = openChat
        if (c != null && c.endsWith("@s.whatsapp.net")) {
            while (openChat == c) { chatPresence = GatewayClient.getPresence(c); delay(5000) }
        }
    }
    // apply a pending deep-link/notification chat ONLY once WhatsApp is registered,
    // otherwise the startup status-poll (which nulls openChat while not registered) wipes it
    LaunchedEffect(AppNav.pendingOpenChat.value, status.registered) {
        if (status.registered) {
            AppNav.pendingOpenChat.value?.let { openChat = it; screen = "chats"; AppNav.pendingOpenChat.value = null }
        }
    }

    LaunchedEffect(ChatStyle.runBackground.value) {
        // node runs in-process regardless; the foreground service (silent notification) is only for background keep-alive
        withContext(Dispatchers.IO) { NodeRuntime.ensureStarted(ctx.applicationContext) }
        if (ChatStyle.runBackground.value) NodeService.start(ctx) else NodeService.stop(ctx)
    }
    LaunchedEffect(Unit) {
        ContactStore.init(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            val all = withContext(Dispatchers.IO) { loadDeviceContacts(ctx) }
            all.forEach { ContactStore.put(it.number, it.name) }
            doLoadContacts()
        } else contactsPerm.launch(Manifest.permission.READ_CONTACTS)
    }
    LaunchedEffect(status.registered) {
        if (status.registered) {
            val wa = runCatching { GatewayClient.getContacts() }.getOrDefault(emptyList())
            wa.forEach { c -> if (c.name.isNotBlank()) { ContactStore.put(c.jid, c.name); if (c.number.isNotBlank()) ContactStore.put(c.number, c.name) } }
        }
    }
    LaunchedEffect(Unit) {
        var triedRestore = false
        var didBackup = false
        while (true) {
            status = GatewayClient.status()
            if (!status.registered) {
                settingsLoaded = false; openChat = null
                // One-shot: restore a saved login from Download/WhatsAyX/session (survives reinstall / reset).
                if (status.reachable && !triedRestore) {
                    triedRestore = true
                    runCatching {
                        if (SessionBackup.exists(ctx)) {
                            SessionBackup.load(ctx)?.let { js ->
                                notify("restoring login…")
                                if (GatewayClient.importSession(js)) { status = GatewayClient.status(); notify("login restored") }
                            }
                        }
                    }
                }
                if (!status.registered) {
                    val b = GatewayClient.qrBytes()
                    if (b != null) {
                        val h = b.contentHashCode()
                        if (h != lastQrHash) BitmapFactory.decodeByteArray(b, 0, b.size)?.let { qr = it.asImageBitmap(); lastQrHash = h }
                    }
                }
            } else {
                qr = null; lastQrHash = 0
                if (!settingsLoaded) {
                    settings = GatewayClient.getSettings(); settingsLoaded = true
                    // keep the download-link auto-reply fed from the encrypted AyxHere store (single source of truth)
                    runCatching { GatewayClient.patchSettings(JSONObject().put("ayxDownloadUrl", AyxHere.githubReleases).put("ayxShareMsg", AyxHere.shareMsg)) }
                }
                // Back up the login once per session so a later reset / reinstall keeps it.
                if (!didBackup) {
                    didBackup = true
                    runCatching { val js = GatewayClient.exportSession(); if (js.isNotBlank() && js != "{}") SessionBackup.save(ctx, js) }
                }
                messages = GatewayClient.getMessages()
                optimistic.removeAll { opt -> messages.any { it.fromMe && it.chat == opt.chat && it.text == opt.text && it.ts >= opt.ts - 8000 } }
            }
            delay(3000)
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2500); toast = null } }

    BackHandler(enabled = viewImg != null || viewVideoUrl != null || openChat != null || screen == "settings" || screen == "newchat" || screen == "profile") {
        when {
            viewImg != null -> viewImg = null
            viewVideoUrl != null -> viewVideoUrl = null
            openChat != null -> openChat = null
            screen == "settings" && settingsPage != "home" -> settingsPage = settingsParent(settingsPage)
            else -> screen = "chats"
        }
    }


    storyView?.let { sender ->
        StatusViewer(statuses, sender, dpCache,
            onDownload = { st -> downloadMedia(scope, ctx, GatewayClient.Msg(st.sender, "", false, st.text, st.ts, st.mediaName, st.mediaType)) { m -> notify(m) } },
            onReply = { jid, text -> scope.launch { runCatching { GatewayClient.sendToJid(jid, text) }.onSuccess { notify("reply sent") }.onFailure { notify("reply failed: " + it.message) } } },
            onDeleteStatus = { id -> scope.launch {
                        val res = StatusDl.deleteEverywhere(id)
                        statuses = statuses.filterNot { it.id == id }
                        notify(res.second)
                    } },
            onSeen = { st -> val sid = st.id
                StatusData.markSeen(sid)                                   // local read-ordering: always track, regardless of the receipt setting
                if (!settings.hideStatusRead && sid != null) scope.launch { GatewayClient.markStatusRead(sid, st.sender) } },
            onClose = { storyView = null })
    }

    pendingStatus?.let { ps ->
        StatusEditor(ps.first, ps.second, deviceContacts, onUpload = { caption, audience, jids, song, tStart, tEnd, lyrics, lyricY, lyricScale, animStyle, romanize ->
            val u = ps.first; val t = ps.second
            pendingStatus = null
            // 'all' audience: send to every WhatsApp contact on the device so distribution never depends on
            // the gateway's accumulated contact list (which can be empty after a fresh link / clear data).
            val recipients: List<String> = if (audience == "all") deviceContacts.map { c -> c.number + "@s.whatsapp.net" } else jids
            scope.launch {
                try {
                    if (song == null) {
                        val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                        if (bytes != null) { notify("uploading status…"); val res = GatewayClient.postStatus(t, Base64.encodeToString(bytes, Base64.NO_WRAP), caption, audience, recipients); notify((if (res.first) "✅ " else "❌ ") + res.second); if (res.first) { delay(1200); statuses = StatusData.merge(GatewayClient.getStatuses()) } }
                    } else {
                        notify("Creating status video…")
                        val finalMp4 = withContext(Dispatchers.IO) {
                            val dir = ctx.cacheDir
                            val stamp = System.currentTimeMillis()
                            val vFile = File(dir, "sv_$stamp.mp4")
                            val aFile = File(dir, "ta_$stamp.m4a")
                            val outFile = File(dir, "final_$stamp.mp4")
                            val durMs = (tEnd - tStart).coerceAtLeast(3000L)
                            val ly = if (lyrics.isNotEmpty()) lyrics else runCatching { GatewayClient.getLyrics(song.title, song.artist) }.getOrDefault(emptyList())
                            val adjusted0 = ly.filter { it.first in tStart..tEnd }.map { ((it.first - tStart - 450L).coerceAtLeast(0L)) to it.second }
                            // Hindi (or any) lyrics → English letters when requested
                            val adjusted = if (romanize && adjusted0.isNotEmpty()) {
                                val rom = runCatching { GatewayClient.translateLines(adjusted0.map { it.second }, true) }.getOrDefault(adjusted0.map { it.second })
                                adjusted0.mapIndexed { i, pr -> pr.first to (rom.getOrNull(i)?.ifBlank { pr.second } ?: pr.second) }
                            } else adjusted0
                            val vOk = if (t == "image") MediaTools.photosToVideo(ctx, listOf(u), vFile, durMs, adjusted, lyricY, lyricScale, animStyle) { }
                                      else runCatching { ctx.contentResolver.openInputStream(u)?.use { inp -> FileOutputStream(vFile).use { inp.copyTo(it) } }; true }.getOrDefault(false)
                            if (!vOk) return@withContext null
                            notify("Preparing audio…")
                            // song audio; if it fails, fall back to a silent AAC track so the MP4 always has audio (WhatsApp needs it)
                            val haveSong = song != null && MediaTools.downloadAndTrimAudio(song.url, aFile, tStart, tEnd) && aFile.length() > 0
                            val audioSrc = if (haveSong) aFile else File(dir, "sil_$stamp.m4a").also { MediaTools.makeSilentAac(durMs, it) }
                            notify("Finalizing video…")
                            val muxTmp = File(dir, "mux_$stamp.mp4")
                            if (!MediaTools.muxVideoAudio(vFile, audioSrc, muxTmp)) return@withContext null
                            // move moov atom to front (faststart) so WhatsApp can play it; fall back to raw mux if it fails
                            if (!MediaTools.faststart(muxTmp, outFile)) runCatching { muxTmp.copyTo(outFile, overwrite = true) }
                            if (!MediaTools.isValidMp4(outFile)) return@withContext null
                            runCatching { vFile.delete(); aFile.delete(); muxTmp.delete(); File(dir, "sil_$stamp.m4a").delete() }
                            outFile
                        }
                        if (finalMp4 != null && finalMp4.exists()) {
                            notify("Uploading…")
                            val bytes = withContext(Dispatchers.IO) { finalMp4.readBytes() }
                            val res = GatewayClient.postStatus("video", Base64.encodeToString(bytes, Base64.NO_WRAP), caption, audience, recipients)
                            notify((if (res.first) "✅ " else "❌ ") + res.second)
                            runCatching { finalMp4.delete() }
                            if (res.first) { delay(1200); statuses = StatusData.merge(GatewayClient.getStatuses()) }
                        } else notify("video processing failed")
                    }
                } catch (e: Exception) { notify("failed: " + e.message) }
            }
        }, onCancel = { pendingStatus = null })
    }

    if (textStatusDlg) {
        var vtext by remember { mutableStateOf("") }
        val bgColors = remember { listOf(0xFF128C7E, 0xFF075E54, 0xFF1F7AEC, 0xFF7E57C2, 0xFFEF5350, 0xFFFF7043, 0xFF5C6BC0, 0xFF26A69A, 0xFF8D6E63, 0xFF000000) }
        var bgIdx by remember { mutableStateOf(0) }
        fun hex(c: Long): String = "#" + (c and 0xFFFFFFL).toString(16).padStart(6, '0').uppercase()
        AlertDialog(
            onDismissRequest = { textStatusDlg = false },
            containerColor = dialogBg(),
            shape = RoundedCornerShape(24.dp),
            icon = { Icon(Icons.Filled.TextFields, null, tint = AYX_GREEN) },
            title = { Text("Text status") },
            text = {
                Column {
                    // live preview on the chosen background colour
                    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(Color(bgColors[bgIdx])), contentAlignment = Alignment.Center) {
                        Text(vtext.ifBlank { "Type your status…" }, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(18.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(vtext, { vtext = it }, placeholder = { Text("Type your status…") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), maxLines = 4)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bgColors.forEachIndexed { i, c ->
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                .border(if (i == bgIdx) 3.dp else 1.dp, if (i == bgIdx) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f), CircleShape)
                                .clickable { bgIdx = i })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = vtext.isNotBlank(), onClick = {
                    val t = vtext.trim(); textStatusDlg = false
                    if (t.isNotEmpty()) scope.launch {
                        notify("Posting text status…")
                        val res = GatewayClient.postTextStatus(t, hex(bgColors[bgIdx]), "all", emptyList())
                        notify((if (res.first) "✅ " else "❌ ") + res.second)
                        if (res.first) { delay(1200); statuses = StatusData.merge(GatewayClient.getStatuses()) }
                    }
                }) { Text("Post", color = AYX_GREEN) }
            },
            dismissButton = { TextButton(onClick = { textStatusDlg = false }) { Text("Cancel") } }
        )
    }

    pendingMedia?.let { pm ->
        val uri = pm.first; val mtype = pm.second
        var cap by remember(uri) { mutableStateOf("") }
        var original by remember(uri) { mutableStateOf(false) }
        Dialog(onDismissRequest = { pendingMedia = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = dialogBg()) {
                Column(Modifier.padding(16.dp).widthIn(max = 340.dp)) {
                    Text("Send " + mtype, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (mtype == "image") {
                        val bmp = remember(uri) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
                        bmp?.let { Image(it, null, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit) }
                    } else Text(queryName(ctx, uri) ?: "file", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(cap, { cap = it }, placeholder = { Text("Add a caption…") }, modifier = Modifier.fillMaxWidth())
                    if (mtype == "image" || mtype == "video") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(original, { original = it })
                            Text("Original quality (send as file)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pendingMedia = null }) { Text("Cancel") }
                        Button(onClick = {
                            val jid = openChat; val u = uri
                            val sendType = if (original) "document" else mtype; val caption = cap.trim()
                            pendingMedia = null
                            if (jid != null) scope.launch {
                                try {
                                    val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } }
                                    if (bytes == null) { notify("can't read file"); return@launch }
                                    notify("sending…")
                                    GatewayClient.sendMedia(jid, Base64.encodeToString(bytes, Base64.NO_WRAP), sendType, queryName(ctx, u) ?: "file", caption)
                                } catch (e: Exception) { notify("send failed: ${e.message}") }
                            }
                        }) { Text("Send") }
                    }
                }
            }
        }
    }

    // image viewer (full screen)
    viewImg?.let { img ->
        Dialog(onDismissRequest = { viewImg = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewImg = null }, contentAlignment = Alignment.Center) {
                Image(img, "image", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
    // video viewer (full screen, in-app player)
    viewVideoUrl?.let { url ->
        Dialog(onDismissRequest = { viewVideoUrl = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(Uri.parse(url))
                        val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                        setOnPreparedListener { it.start() }
                    }
                }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { viewVideoUrl = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
    // profile-photo viewer (tap a contact avatar in a chat) — preview full DP + download
    dpView?.let { jid ->
        val nm = chatTitleOf(messages, jid)
        ProfilePhotoViewer(jid, nm,
            onDownload = {
                scope.launch {
                    val bytes = GatewayClient.dpBytes(jid)
                    if (bytes == null) notify("no profile photo to download")
                    else {
                        val ok = MediaSaver.save(ctx, bytes, "dp_" + jid.substringBefore("@").filter { it.isDigit() } + ".jpg", "image")
                        notify(if (ok) "photo saved to $APP_NAME folder" else "save failed")
                    }
                }
            },
            onClose = { dpView = null })
    }

    val chatName = openChat?.let { c -> chatTitle(messages.filter { it.chat == c }) }
    val title = when {
        !status.registered -> "Link device"
        openChat != null -> chatName ?: "Chat"
        screen == "settings" -> if (settingsPage == "home") "Settings" else settingsTitle(settingsPage)
        screen == "newchat" -> "New chat"
        screen == "profile" -> "Profile"
        else -> APP_NAME
    }

    if (showNewChat) {
        var num by remember { mutableStateOf("91") }
        var cts by remember { mutableStateOf(listOf<GatewayClient.Contact>()) }
        var csearch by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { cts = GatewayClient.getContacts() }
        Dialog(onDismissRequest = { showNewChat = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = dialogBg()) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New chat", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(num, { num = it.filter(Char::isDigit) }, label = { Text("Number with country code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (num.length in 8..15) { openChat = num + "@s.whatsapp.net"; showNewChat = false } },
                        enabled = num.length in 8..15, modifier = Modifier.fillMaxWidth()) { Text("Start with number") }
                    HorizontalDivider()
                    OutlinedTextField(csearch, { csearch = it }, label = { Text("Search contacts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val filtered = cts.filter { it.name.contains(csearch, true) || it.number.contains(csearch) }
                    if (cts.isEmpty()) Text("No contacts synced yet (they sync from WhatsApp over time).", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        filtered.take(80).forEach { c ->
                            Row(Modifier.fillMaxWidth().clickable { openChat = c.jid; showNewChat = false }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(c.name.ifBlank { "+" + c.number }, style = MaterialTheme.typography.bodyLarge)
                                    if (c.name.isNotBlank()) Text("+" + c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showNewChat = false }, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (status.registered && openChat == null && screen == "chats") {
                if (chatsPage == 1) Box {
                    FloatingActionButton(onClick = { statusFabMenu = true }) { Icon(Icons.Filled.PhotoCamera, "add status") }
                    DropdownMenu(expanded = statusFabMenu, onDismissRequest = { statusFabMenu = false }) {
                        DropdownMenuItem(text = { Text("Photo / Video") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                            onClick = { statusFabMenu = false; ensureContacts(); statusPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                        DropdownMenuItem(text = { Text("Text status") }, leadingIcon = { Icon(Icons.Filled.TextFields, null) },
                            onClick = { statusFabMenu = false; ensureContacts(); textStatusDlg = true })
                    }
                }
                else FloatingActionButton(onClick = { screen = "newchat"; ensureContacts() }) { Icon(Icons.Filled.Add, "new chat") }
            }
        },
        bottomBar = {},   // Home/Status nav is a FLOATING rounded glass bar overlaid on the content (see below)
        topBar = {
            // Glass header: translucent so the list scrolls behind it. Chat screen has its own header.
            if (openChat == null) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f), shadowElevation = 6.dp, tonalElevation = 0.dp,
                shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp), modifier = Modifier.fillMaxWidth()) {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                title = {
                    if (searchMode && openChat == null && screen == "chats") {
                        OutlinedTextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search chats") },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = IOS_BLUE) }, singleLine = true, shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth())
                    } else if (openChat != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(openChat!!, chatName ?: "?", dpCache, 38.dp, CircleShape)
                            Spacer(Modifier.width(9.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(title, maxLines = 1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(max = 190.dp).basicMarquee())
                                val sub = chatPresence?.let { pr -> if (pr.online) "online" else if (pr.lastSeen > 0) "last seen " + fmt(pr.lastSeen * 1000) else "" } ?: ""
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = {},
                        // Long-press the WhatsAyX title to reveal hidden items — Status tab reveals hidden statuses, Home reveals hidden chats.
                        onLongClick = {
                            if (chatsPage == 1) {
                                if (StatusFlags.reveal) StatusFlags.reveal = false
                                else {
                                    val km = ctx.getSystemService(KeyguardManager::class.java)
                                    if (km != null && km.isKeyguardSecure) statusRevealUnlock.launch(km.createConfirmDeviceCredentialIntent("Show hidden statuses", "Verify to reveal"))
                                    else StatusFlags.reveal = true
                                }
                            } else {
                                if (ChatFlags.reveal) ChatFlags.reveal = false
                                else {
                                    val km = ctx.getSystemService(KeyguardManager::class.java)
                                    if (km != null && km.isKeyguardSecure) revealUnlock.launch(km.createConfirmDeviceCredentialIntent("Show hidden chats", "Verify to reveal"))
                                    else ChatFlags.reveal = true
                                }
                            }
                        }))
                },
                navigationIcon = {
                    if (openChat != null || screen == "settings" || screen == "newchat" || screen == "profile")
                        IconButton(onClick = { if (openChat != null) openChat = null else if (screen == "settings" && settingsPage != "home") settingsPage = settingsParent(settingsPage) else screen = "chats" },
                            modifier = Modifier.padding(start = 6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
                        }
                    else if (status.registered && myJid != null)
                        Box(Modifier.padding(start = 10.dp).clip(CircleShape).clickable { screen = "profile" }) { Avatar(myJid!!, "Me", dpCache, 34.dp) }
                },
                actions = {
                    if (status.registered && openChat == null && screen == "chats") {
                        if (searchMode) {
                            IconButton(onClick = { searchMode = false; searchQuery = "" }) { Icon(Icons.Filled.Close, "close") }
                        } else {
                            Row(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
                                IconButton(onClick = { searchMode = true }) { Icon(Icons.Filled.Search, "search") }
                                IconButton(onClick = { settingsPage = "home"; screen = "settings" }) { Icon(Icons.Filled.Settings, "settings") }
                            }
                        }
                    }
                    if (openChat != null) {
                        var menu by remember { mutableStateOf(false) }
                        val ocb = openChat
                        val isBlocked = ocb != null && ocb in blockedJids
                        Row(Modifier.padding(end = 4.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
                            IconButton(onClick = { showSetName = true }) { Icon(Icons.Filled.Edit, "set name") }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "menu") }
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Change wallpaper") }, onClick = { menu = false; chatWallpaperPicker.launch("image/*") })
                            if (!isBlocked) DropdownMenuItem(text = { Text("Block contact") }, onClick = {
                                menu = false
                                val c = openChat
                                if (c != null) scope.launch { runCatching { GatewayClient.blockChat(c, true) }.onSuccess { notify("blocked"); blockedJids = blockedJids + c; blkPrefs.edit().putStringSet("blocked", blockedJids).apply() }.onFailure { notify("failed: ${it.message}") } }
                            })
                            if (isBlocked) DropdownMenuItem(text = { Text("Unblock contact") }, onClick = {
                                menu = false
                                val c = openChat
                                if (c != null) scope.launch { runCatching { GatewayClient.blockChat(c, false) }.onSuccess { notify("unblocked"); blockedJids = blockedJids - c; blkPrefs.edit().putStringSet("blocked", blockedJids).apply() }.onFailure { notify("failed: ${it.message}") } }
                            })
                        }
                    }
                }
            )
            }
            }
        }
    ) { pad ->
        val homeBlur by animateDpAsState(if (statusFabMenu || textStatusDlg || showSetName) 16.dp else 0.dp, label = "homeblur")
        Box(Modifier.fillMaxSize().blur(homeBlur)) {
        if (openChat != null && status.registered) {
            // ===== Edge-to-edge chat: wallpaper + messages full-bleed, floating glass header on top =====
            val ocChat = openChat!!
            ChatDetail(
                chat = ocChat,
                messages = messages.filter { it.chat == ocChat },
                optimistic = optimistic.filter { it.chat == ocChat },
                canSend = status.connection == "open",
                onSend = { text ->
                    optimistic.add(OptMsg(ocChat, text, System.currentTimeMillis()))
                    scope.launch { try { GatewayClient.sendToJid(ocChat, text) } catch (e: Exception) { notify("send error: ${e.message}") } }
                },
                onMedia = { openMedia(it) },
                onShare = { shareMedia(scope, ctx, it) { s -> notify(s) } },
                onDownload = { downloadMedia(scope, ctx, it) { s -> notify(s) } },
                onReact = { m, e -> scope.launch { m.id?.let { GatewayClient.react(m.chat, it, e, m.fromMe) } } },
                onDeleteMsg = { m, everyone -> scope.launch { GatewayClient.deleteMessage(m.chat, m.id, m.text, m.ts, everyone, m.fromMe); messages = GatewayClient.getMessages() } },
                onAttach = { picker.launch("*/*") },
                onCamera = { openCamera() },
                onReplySend = { text, qid, qtext ->
                    optimistic.add(OptMsg(ocChat, text, System.currentTimeMillis(), qtext))
                    scope.launch { try { GatewayClient.sendReply(ocChat, text, qid) } catch (e: Exception) { notify("send error: ${e.message}") } }
                },
                onEditMsg = { m, txt ->
                    val mid = m.id
                    if (mid == null) notify("can't edit this message")
                    else scope.launch {
                        runCatching { GatewayClient.editMessage(m.chat, mid, txt) }
                            .onSuccess { r -> if (r.optBoolean("ok", false)) { notify("edited"); messages = GatewayClient.getMessages() } else notify("edit failed: " + r.optString("error", "too old?")) }
                            .onFailure { notify("edit error: ${it.message}") }
                    }
                },
                onForward = { m -> if (m.id == null) notify("can't forward this") else forwardMsg = m },
                onAvatarClick = { jid -> dpView = jid },
                previewCache = previewCache,
                dpCache = dpCache,
                wallpaper = chatWp
            )
            val ocBlocked = ocChat in blockedJids
            ChatGlassHeader(
                jid = ocChat,
                name = title,
                presence = chatPresence,
                dpCache = dpCache,
                isBlocked = ocBlocked,
                onBack = { openChat = null },
                onEdit = { showSetName = true },
                onWallpaper = { chatWallpaperPicker.launch("image/*") },
                onBlock = { scope.launch { runCatching { GatewayClient.blockChat(ocChat, true) }.onSuccess { notify("blocked"); blockedJids = blockedJids + ocChat; blkPrefs.edit().putStringSet("blocked", blockedJids).apply() }.onFailure { notify("failed: ${it.message}") } } },
                onUnblock = { scope.launch { runCatching { GatewayClient.blockChat(ocChat, false) }.onSuccess { notify("unblocked"); blockedJids = blockedJids - ocChat; blkPrefs.edit().putStringSet("blocked", blockedJids).apply() }.onFailure { notify("failed: ${it.message}") } } },
                onAvatarClick = { dpView = ocChat }
            )
        } else {
        Box(Modifier.fillMaxSize().consumeWindowInsets(pad)) {
            when {
                !status.registered -> Box(Modifier.padding(pad)) { LinkScreen(qr, status.pairingCode,
                    onPair = { n -> scope.launch { try { notify("code: " + GatewayClient.pair(n)) } catch (e: Exception) { notify("pair error: ${e.message}") } } },
                    onReset = { scope.launch { GatewayClient.logout(); SessionBackup.delete(ctx); notify("reset") } }) }
                screen == "settings" -> Box(Modifier.padding(pad)) { SettingsScreen(status, settings, page = settingsPage, onPage = { settingsPage = it }, wallpaperVersion = wallpaperVersion,
                    onToggle = { patch -> scope.launch { settings = GatewayClient.patchSettings(patch) } },
                    onRules = { r -> scope.launch { settings = GatewayClient.setRules(r) } },
                    onLogout = { scope.launch { GatewayClient.logout(); SessionBackup.delete(ctx); notify("logged out") } }, ctx = ctx,
                    onPickWallpaper = { wallpaperPicker.launch("image/*") },
                    onRemoveWallpaper = { File(ctx.filesDir, "wallpaper.jpg").delete(); loadWallpaper(); wallpaperVersion++; notify("wallpaper removed") },
                    onPickPhoto = { profilePicPicker.launch("image/*") },
                    onSaveName = { n -> scope.launch { runCatching { GatewayClient.setProfileName(n) }.onSuccess { notify("name updated") }.onFailure { notify("name: ${it.message}") } } },
                    dpCache = dpCache,
                    messages = messages) }
                screen == "newchat" -> Box(Modifier.padding(pad)) { NewChatScreen(deviceContacts, contactsLoading, dpCache,
                    onPickNumber = { num -> openChat = num + "@s.whatsapp.net"; screen = "chats" }) }
                screen == "profile" -> Box(Modifier.padding(pad)) { ProfileScreen(myJid, dpCache,
                    onPickPhoto = { profilePicPicker.launch("image/*") },
                    onSaveName = { n -> scope.launch { runCatching { GatewayClient.setProfileName(n) }.onSuccess { notify("name updated") }.onFailure { notify("name: ${it.message}") } } }) }
                else -> ChatsWithStatus(messages, statuses, dpCache, searchQuery, chatsPage, topInset = pad.calculateTopPadding(),
                    onPageChange = { chatsPage = it },
                    onLoadStatuses = { scope.launch { val fresh = GatewayClient.getStatuses(); statuses = if (fresh.isNotEmpty()) StatusData.merge(fresh) else StatusData.load() } },
                    onOpenStatus = { st ->
                        if (StatusFlags.isLocked(st.sender)) {
                            val km = ctx.getSystemService(KeyguardManager::class.java)
                            if (km != null && km.isKeyguardSecure) { pendingStatusOpen = st.sender; statusUnlockLauncher.launch(km.createConfirmDeviceCredentialIntent("Unlock status", "Verify to view this status")) }
                            else storyView = st.sender
                        } else storyView = st.sender
                    },
                    onToggleStatusReveal = {
                        if (StatusFlags.reveal) StatusFlags.reveal = false
                        else {
                            val km = ctx.getSystemService(KeyguardManager::class.java)
                            if (km != null && km.isKeyguardSecure) statusRevealUnlock.launch(km.createConfirmDeviceCredentialIntent("Show hidden statuses", "Verify to reveal"))
                            else StatusFlags.reveal = true
                        }
                    },
                    onToggleChatReveal = {
                        if (ChatFlags.reveal) ChatFlags.reveal = false
                        else {
                            val km = ctx.getSystemService(KeyguardManager::class.java)
                            if (km != null && km.isKeyguardSecure) revealUnlock.launch(km.createConfirmDeviceCredentialIntent("Show hidden chats", "Verify to reveal"))
                            else ChatFlags.reveal = true
                        }
                    },
                    onDelete = { jid -> scope.launch { GatewayClient.deleteChat(jid); messages = GatewayClient.getMessages() } },
                    onOpen = { jid ->
                        if (ChatFlags.locked[jid] == true) {
                            val km = ctx.getSystemService(KeyguardManager::class.java)
                            if (km != null && km.isKeyguardSecure) {
                                pendingLockOpen = jid
                                unlockLauncher.launch(km.createConfirmDeviceCredentialIntent("Unlock chat", "Verify to open this chat"))
                            } else openChat = jid
                        } else openChat = jid
                    })
            }
        }
        }
            // Floating rounded glass Home/Status navigation — overlaid so the list scrolls behind it.
            if (status.registered && openChat == null && screen == "chats") {
                val selC = MaterialTheme.colorScheme.primary; val dimC = MaterialTheme.colorScheme.onSurfaceVariant
                Surface(shape = RoundedCornerShape(30.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 0.dp, shadowElevation = 14.dp, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)) {
                    Row(Modifier.padding(horizontal = 6.dp).height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(104.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { chatsPage = 0 }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.Home, "Home", tint = if (chatsPage == 0) selC else dimC, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp)); Text("Home", color = if (chatsPage == 0) selC else dimC, style = MaterialTheme.typography.labelSmall, fontWeight = if (chatsPage == 0) FontWeight.SemiBold else FontWeight.Normal)
                        }
                        Column(Modifier.width(104.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { chatsPage = 1 }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(Icons.Filled.DonutLarge, "Status", tint = if (chatsPage == 1) selC else dimC, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(2.dp)); Text("Status", color = if (chatsPage == 1) selC else dimC, style = MaterialTheme.typography.labelSmall, fontWeight = if (chatsPage == 1) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
            toast?.let {
                Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)) {
                    Text(it, Modifier.padding(12.dp, 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
            if (showSetName && openChat != null) {
                val jidForName = openChat!!
                val saved = SetName.get(jidForName) ?: ""
                var nameInput by remember(jidForName) { mutableStateOf(saved) }
                AlertDialog(
                    onDismissRequest = { showSetName = false },
                    containerColor = dialogBg(),
                    shape = RoundedCornerShape(24.dp),
                    icon = { Icon(Icons.Filled.Edit, null, tint = AYX_GREEN) },
                    title = { Text("Set name") },
                    text = {
                        Column {
                            Text("Shows only in AyX (chats, status, groups). Real WhatsApp contact name stays unchanged.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(nameInput, { nameInput = it }, singleLine = true,
                                placeholder = { Text("Custom name") }, shape = RoundedCornerShape(14.dp),
                                trailingIcon = { if (nameInput.isNotEmpty()) IconButton(onClick = { nameInput = "" }) { Icon(Icons.Filled.Clear, "clear") } },
                                modifier = Modifier.fillMaxWidth())
                        }
                    },
                    confirmButton = { TextButton(onClick = { SetName.set(jidForName, nameInput); showSetName = false; notify("name saved") }, enabled = nameInput.isNotBlank() && nameInput != saved) { Text("Save", color = AYX_GREEN) } },
                    dismissButton = {
                        Row {
                            if (saved.isNotEmpty()) TextButton(onClick = { SetName.clear(jidForName); showSetName = false; notify("name removed") }) { Text("Remove", color = AYX_RED) }
                            TextButton(onClick = { showSetName = false }) { Text("Cancel") }
                        }
                    }
                )
            }
            forwardMsg?.let { fm ->
                ForwardPicker(
                    messages = messages,
                    dpCache = dpCache,
                    onDismiss = { forwardMsg = null },
                    onForward = { targets ->
                        val mid = fm.id
                        forwardMsg = null
                        if (mid != null && targets.isNotEmpty()) {
                            notify("forwarding…")
                            scope.launch {
                                runCatching { GatewayClient.forward(mid, targets) }
                                    .onSuccess { r ->
                                        notify(if (r.optBoolean("ok", false)) "forwarded to ${r.optInt("sent", targets.size)}" else "forward failed: " + r.optString("error", ""))
                                        messages = GatewayClient.getMessages()
                                    }
                                    .onFailure { notify("forward error: ${it.message}") }
                            }
                        }
                    }
                )
            }
        }
    }
}

// ===== In-app forward picker: search + multi-select recent chats, then Forward =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForwardPicker(messages: List<GatewayClient.Msg>, dpCache: MutableMap<String, ImageBitmap?>, onDismiss: () -> Unit, onForward: (List<String>) -> Unit, title: String = "Forward to", action: String = "Forward to") {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // recent chats derived from message log (exclude status broadcast)
    val chats = remember(messages) {
        messages.filter { it.chat != "status@broadcast" }
            .groupBy { it.chat }.entries
            .map { it.key to it.value.maxOf { m -> m.ts } }
            .sortedByDescending { it.second }
            .map { it.first }
    }
    val filtered = chats.filter { query.isBlank() || chatTitleOf(messages, it).contains(query, true) || it.contains(query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), color = dialogBg(),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.8f)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = AYX_GREEN)
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "close") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search chats") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(filtered, key = { _, jid -> jid }) { _, jid ->
                        val name = chatTitleOf(messages, jid)
                        val isSel = jid in selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { if (isSel) selected.remove(jid) else selected.add(jid) }
                            .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(jid, name, dpCache, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isSel) Box(Modifier.size(24.dp).clip(CircleShape).background(AYX_GREEN), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Done, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            } else Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        }
                    }
                }
                Button(onClick = { onForward(selected.toList()) }, enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AYX_GREEN)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (selected.isEmpty()) "Select chats" else "$action ${selected.size}")
                }
            }
        }
    }
}

// resolve a chat's display title from its message log
private fun chatTitleOf(messages: List<GatewayClient.Msg>, jid: String): String {
    val msgs = messages.filter { it.chat == jid }
    return if (msgs.isNotEmpty()) chatTitle(msgs) else (ContactStore.nameFor(jid) ?: jid.substringBefore("@"))
}

@Composable
internal fun Avatar(jid: String, name: String, cache: MutableMap<String, ImageBitmap?>, size: androidx.compose.ui.unit.Dp, shape: Shape = CircleShape) {
    LaunchedEffect(jid) {
        if (!cache.containsKey(jid)) {
            val b = GatewayClient.dpBytes(jid)
            cache[jid] = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
    }
    val dp = cache[jid]
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, shape), contentAlignment = Alignment.Center) {
        if (dp != null) Image(dp, "dp", Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
    }
}

// full-screen profile-photo preview with a download button (opened by tapping a chat avatar)
@Composable
private fun ProfilePhotoViewer(jid: String, name: String, onDownload: () -> Unit, onClose: () -> Unit) {
    var full by remember(jid) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(jid) { mutableStateOf(true) }
    LaunchedEffect(jid) {
        loading = true
        val bytes = GatewayClient.dpBytes(jid)
        full = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        loading = false
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }, contentAlignment = Alignment.Center) {
                when {
                    full != null -> Image(full!!, "profile photo", Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
                    loading -> CircularProgressIndicator(color = Color.White)
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Person, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(10.dp)); Text("No profile photo", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            // top bar: name + close
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "close", tint = Color.White) }
                Spacer(Modifier.width(4.dp))
                Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // download FAB
            if (full != null) FloatingActionButton(onClick = onDownload, containerColor = AYX_GREEN,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
                Icon(Icons.Filled.Download, "download", tint = Color.White)
            }
        }
    }
}

// Discoverable "show hidden" row — so hidden chats/statuses can always be brought back.
@Composable
private fun HiddenReveal(label: String, revealed: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(messages: List<GatewayClient.Msg>, dpCache: MutableMap<String, ImageBitmap?>, query: String, contentPad: androidx.compose.foundation.layout.PaddingValues, onToggleReveal: () -> Unit, onDelete: (String) -> Unit, onOpen: (String) -> Unit) {
    val allKeys = messages.map { it.chat }.toSet()
    val hiddenCount = allKeys.count { ChatFlags.hidden[it] == true }
    val groups = messages.groupBy { it.chat }.entries
        .filter { ChatFlags.reveal || ChatFlags.hidden[it.key] != true }
        .filter { query.isBlank() || chatTitle(it.value).contains(query, true) || it.key.contains(query) }
        .sortedByDescending { it.value.maxOf { m -> m.ts } }
    if (groups.isEmpty() && hiddenCount == 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No chats yet.\nIncoming messages will appear here.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // long-press a chat → blur the list behind + a floating action sheet (iOS/WhatsApp style)
    var menuChat by remember { mutableStateOf<String?>(null) }
    var mutePick by remember { mutableStateOf<String?>(null) }
    val blurDp by animateDpAsState(if (menuChat != null || mutePick != null) 18.dp else 0.dp, label = "homeblur")

    LazyColumn(Modifier.fillMaxSize().blur(blurDp), contentPadding = contentPad) {
        itemsIndexed(groups, key = { _, e -> e.key }) { _, entry ->
            val msgs = entry.value
            val last = msgs.maxByOrNull { it.ts }!!
            val name = chatTitle(msgs)
            Row(Modifier.fillMaxWidth().combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onOpen(entry.key) }, onLongClick = { menuChat = entry.key }).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(entry.key, name, dpCache, 50.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            if (ChatFlags.isMuted(entry.key)) { Spacer(Modifier.width(5.dp)); Icon(Icons.Filled.NotificationsOff, "muted", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(fmt(last.ts), style = MaterialTheme.typography.labelSmall)
                    }
                    val preview = when { last.deleted -> "deleted"; last.text.isNotBlank() -> last.text; last.mediaType != null -> "[${last.mediaType}]"; else -> "" }
                    Text((if (last.fromMe) "You: " else "") + preview, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }

    menuChat?.let { jid ->
        val cmsgs = messages.filter { it.chat == jid }
        val cname = chatTitle(cmsgs)
        val clast = cmsgs.maxByOrNull { it.ts }
        val cprev = clast?.let { when { it.deleted -> "deleted"; it.text.isNotBlank() -> it.text; it.mediaType != null -> "[${it.mediaType}]"; else -> "" } } ?: ""
        ChatActionSheet(
            name = cname, jid = jid, preview = (if (clast?.fromMe == true) "You: " else "") + cprev, dpCache = dpCache,
            muted = ChatFlags.isMuted(jid),
            hidden = ChatFlags.hidden[jid] == true,
            locked = ChatFlags.locked[jid] == true,
            onDismiss = { menuChat = null },
            onMute = { menuChat = null; mutePick = jid },
            onUnmute = { ChatFlags.unmute(jid); menuChat = null },
            onHide = { ChatFlags.toggleHidden(jid); menuChat = null },
            onLock = { ChatFlags.toggleLocked(jid); menuChat = null },
            onDelete = { menuChat = null; onDelete(jid) },
        )
    }
    mutePick?.let { jid ->
        MuteTimerDialog(onDismiss = { mutePick = null }, onPick = { ms -> ChatFlags.muteFor(jid, ms); mutePick = null })
    }
}

// Chat long-press action sheet (home screen) — mute (timed), hide, lock, delete
@Composable
private fun ChatActionSheet(name: String, jid: String, preview: String, dpCache: MutableMap<String, ImageBitmap?>,
    muted: Boolean, hidden: Boolean, locked: Boolean, onDismiss: () -> Unit,
    onMute: () -> Unit, onUnmute: () -> Unit, onHide: () -> Unit, onLock: () -> Unit, onDelete: () -> Unit) {
    val onSurf = MaterialTheme.colorScheme.onSurface
    // Full-screen blurred-backdrop menu: the chat itself floats above its action box.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }, contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = dialogBg().copy(alpha = 0.98f), shadowElevation = 10.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, onSurf.copy(alpha = 0.08f)), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(jid, name, dpCache, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), color = dialogBg().copy(alpha = 0.98f), shadowElevation = 12.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, onSurf.copy(alpha = 0.08f)), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        if (muted) ActionSheetItem(Icons.Filled.Notifications, "Unmute chat", AYX_GREEN, onUnmute)
                        else ActionSheetItem(Icons.Filled.NotificationsOff, "Mute chat", AYX_GREEN, onMute)
                        ActionSheetItem(if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (hidden) "Unhide chat" else "Hide chat", AYX_GREEN, onHide)
                        ActionSheetItem(if (locked) Icons.Filled.LockOpen else Icons.Filled.Lock, if (locked) "Unlock chat" else "Lock chat", AYX_GREEN, onLock)
                        HorizontalDivider(color = onSurf.copy(alpha = 0.08f))
                        ActionSheetItem(Icons.Filled.Delete, "Delete chat", AYX_RED, onDelete, destructive = true)
                    }
                }
            }
        }
    }
}

// Mute duration picker — 5m / 10m / 30m / 1h / 2h / 3h / permanently
@Composable
private fun MuteTimerDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val onSurf = MaterialTheme.colorScheme.onSurface
    val opts = listOf(
        "5 minutes" to 5 * 60_000L, "10 minutes" to 10 * 60_000L, "30 minutes" to 30 * 60_000L,
        "1 hour" to 60 * 60_000L, "2 hours" to 2 * 60 * 60_000L, "3 hours" to 3 * 60 * 60_000L,
        "Permanently" to ChatFlags.MUTE_FOREVER,
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = dialogBg().copy(alpha = 0.97f),
            tonalElevation = 6.dp, shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().border(1.dp, onSurf.copy(alpha = 0.10f), RoundedCornerShape(24.dp))) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Mute for…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                HorizontalDivider(color = onSurf.copy(alpha = 0.08f))
                opts.forEach { (label, ms) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(ms) }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ms >= ChatFlags.MUTE_FOREVER) Icons.Filled.NotificationsOff else Icons.Filled.Schedule, null, tint = AYX_GREEN, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatDetail(
    chat: String,
    messages: List<GatewayClient.Msg>,
    optimistic: List<OptMsg>,
    canSend: Boolean,
    onSend: (String) -> Unit,
    onMedia: (GatewayClient.Msg) -> Unit,
    onShare: (GatewayClient.Msg) -> Unit,
    onDownload: (GatewayClient.Msg) -> Unit,
    onReact: (GatewayClient.Msg, String) -> Unit,
    onDeleteMsg: (GatewayClient.Msg, Boolean) -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onReplySend: (String, String, String) -> Unit,
    onEditMsg: (GatewayClient.Msg, String) -> Unit,
    onForward: (GatewayClient.Msg) -> Unit,
    onAvatarClick: (String) -> Unit,
    previewCache: MutableMap<String, ImageBitmap?>,
    dpCache: MutableMap<String, ImageBitmap?>,
    wallpaper: ImageBitmap?,
) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    var reactMsg by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    var editMsg by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    var infoMsg by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    // multi-select state: long-press → "Select" enters a mode where tapping bubbles toggles them
    var selMode by remember { mutableStateOf(false) }
    val selIds = remember { mutableStateListOf<String>() }
    fun exitSel() { selMode = false; selIds.clear() }
    // blur the chat behind the long-press action sheet (iOS/WhatsApp context-menu style)
    val menuBlur by animateDpAsState(if (reactMsg != null) 18.dp else 0.dp, label = "msgblur")
    // message translation (double-tap). Ask the target language once, then translate directly.
    val transScope = rememberCoroutineScope()
    var askTransFor by remember { mutableStateOf<GatewayClient.Msg?>(null) }
    fun runTranslate(m: GatewayClient.Msg) {
        val id = m.id ?: return
        if (m.text.isBlank()) return
        transScope.launch {
            val romanize = TranslateStore.lang.contains("roman", true)
            val t = GatewayClient.translate(m.text, TranslateStore.lang, romanize)
            if (t != null) TranslateStore.cache[id] = t
            else Toast.makeText(ctx, "Translate failed — check your Groq key", Toast.LENGTH_SHORT).show()
        }
    }
    fun onTranslateMsg(m: GatewayClient.Msg) {
        val id = m.id ?: return
        if (TranslateStore.mode == "off" || m.text.isBlank()) return
        if (TranslateStore.cache.containsKey(id)) { TranslateStore.cache.remove(id); return }   // toggle off
        if (!TranslateStore.configured) { askTransFor = m; return }                               // ask once
        runTranslate(m)
    }
    askTransFor?.let { m ->
        TranslateLangDialog(onDismiss = { askTransFor = null }, onPick = { lang ->
            TranslateStore.chooseLang(lang); askTransFor = null; runTranslate(m)
        })
    }
    reactMsg?.let { rm ->
        MessageActionSheet(
            m = rm,
            onDismiss = { reactMsg = null },
            onReact = { e -> onReact(rm, e); reactMsg = null },
            onEdit = { editMsg = rm; reactMsg = null },
            onInfo = { infoMsg = rm; reactMsg = null },
            onReply = { replyTo = rm; reactMsg = null },
            onForward = { onForward(rm); reactMsg = null },
            onCopy = { clipboard.setText(AnnotatedString(rm.text)); reactMsg = null },
            onSelect = { selMode = true; rm.id?.let { if (it !in selIds) selIds.add(it) }; reactMsg = null },
            onDeleteEveryone = { onDeleteMsg(rm, true); reactMsg = null },
            onDeleteMe = { onDeleteMsg(rm, false); reactMsg = null },
        )
    }
    editMsg?.let { em -> EditMessageDialog(em, onDismiss = { editMsg = null }, onSave = { txt -> onEditMsg(em, txt); editMsg = null }) }
    infoMsg?.let { im -> MessageInfoDialog(im) { infoMsg = null } }
    // newest first (reverseLayout shows newest at bottom, opens there, no jump)
    val rows = remember(messages, optimistic) {
        (messages + optimistic.map { GatewayClient.Msg(chat, "", true, it.text, it.ts, quotedText = it.quotedText) }).sortedByDescending { it.ts }
    }
    val listState = rememberLazyListState()
    // clear the floating header: status bar + header card height, so the top message is never hidden
    val topClear = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 78.dp

    // auto-translate: when mode is Auto, translate incoming messages automatically
    LaunchedEffect(rows.size, TranslateStore.mode, TranslateStore.lang) {
        if (TranslateStore.mode == "auto") {
            val romanize = TranslateStore.lang.contains("roman", true)
            rows.filter { !it.fromMe && it.id != null && it.text.isNotBlank() && !TranslateStore.cache.containsKey(it.id) }
                .take(30).forEach { m ->
                    val t = GatewayClient.translate(m.text, TranslateStore.lang, romanize)
                    if (t != null) TranslateStore.cache[m.id!!] = t
                }
        }
    }

    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().blur(menuBlur)) {
        wallpaper?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // full-bleed column; only bottom (nav bar + keyboard) is inset, top stays under the floating header
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            contentPadding = PaddingValues(top = topClear, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item { Spacer(Modifier.height(6.dp)) }
            itemsIndexed(rows, key = { i, m -> "${m.ts}-$i" }) { _, m ->
                Box(Modifier.fillMaxWidth().animateItem()) {
                    MessageBubble(m, previewCache, dpCache, onMedia, onShare, onDownload,
                        onReply = { replyTo = it }, onAvatarClick = onAvatarClick,
                        selMode = selMode, selected = m.id != null && m.id in selIds,
                        onToggleSelect = { mm -> mm.id?.let { if (it in selIds) selIds.remove(it) else selIds.add(it) } },
                        onTranslate = { onTranslateMsg(it) },
                        onLongClick = { reactMsg = it })
                }
            }
        }
        replyTo?.let { rt ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(34.dp).background(IOS_BLUE, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (rt.fromMe) "You" else rt.name.ifBlank { "Reply" }, style = MaterialTheme.typography.labelMedium, color = IOS_BLUE, maxLines = 1)
                    Text(rt.text.ifBlank { "\uD83D\uDCCE media" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, "cancel reply") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onAttach) { Icon(Icons.Filled.AttachFile, "attach") }
            IconButton(onClick = onCamera) { Icon(Icons.Filled.PhotoCamera, "camera") }
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message") },
                shape = RoundedCornerShape(24.dp), maxLines = 4, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = {
                if (input.isNotBlank()) {
                    val rt = replyTo
                    if (rt?.id != null) onReplySend(input.trim(), rt.id!!, rt.text) else onSend(input.trim())
                    input = ""; replyTo = null
                }
            }, enabled = input.isNotBlank() && canSend) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
        }
        }
      }
      // multi-select top bar — appears while selecting messages (tap bubbles to pick), then delete
      if (selMode) {
          val selMsgs = rows.filter { it.id != null && it.id in selIds }
          Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shadowElevation = 6.dp,
              modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
              Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                  IconButton(onClick = { exitSel() }) { Icon(Icons.Filled.Close, "cancel") }
                  Text("${selIds.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                  IconButton(onClick = { selIds.clear(); rows.forEach { it.id?.let { id -> selIds.add(id) } } }) { Icon(Icons.Filled.Checklist, "select all") }
                  IconButton(onClick = { selMsgs.forEach { onDeleteMsg(it, it.fromMe) }; exitSel() }, enabled = selIds.isNotEmpty()) { Icon(Icons.Filled.Delete, "delete selected", tint = AYX_RED) }
              }
          }
      }
    }
}

// First-time language picker for message translation — saved as the default
@Composable
private fun TranslateLangDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val langs = listOf("English", "Hindi", "Roman Hindi (Hinglish)", "Bangla", "Roman Bangla", "Spanish", "French", "Arabic", "Urdu", "Tamil", "Telugu")
    var custom by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = dialogBg(), tonalElevation = 6.dp, shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("Translate messages to…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp))
                Text("Saved as your default — change it later in Settings → General.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                LazyColumn(Modifier.weight(1f)) {
                    items(langs) { l ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(l) }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Translate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(l, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(custom, { custom = it }, label = { Text("Other language") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { if (custom.isNotBlank()) onPick(custom.trim()) }, enabled = custom.isNotBlank()) { Text("Use") }
                }
            }
        }
    }
}

// App font picker — each name rendered in its own font; tap to apply app-wide
@Composable
private fun FontPickerDialog(ctx: Context, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = dialogBg(), tonalElevation = 6.dp, shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text("App font", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                LazyColumn(Modifier.weight(1f)) {
                    items(FontStore.fonts.keys.toList()) { name ->
                        val fam = FontStore.family(ctx, name)
                        Row(Modifier.fillMaxWidth().clickable { onPick(name) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), fontFamily = fam, style = MaterialTheme.typography.bodyLarge)
                            if (name == current) Icon(Icons.Filled.CheckCircle, "selected", tint = AYX_GREEN)
                        }
                    }
                }
            }
        }
    }
}

// ===== Message long-press action sheet: dark glass, reactions + actions with icons =====
@Composable
private fun MessageActionSheet(
    m: GatewayClient.Msg,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onEdit: () -> Unit,
    onInfo: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onSelect: () -> Unit,
    onDeleteEveryone: () -> Unit,
    onDeleteMe: () -> Unit,
) {
    val onSurf = MaterialTheme.colorScheme.onSurface
    val dark = isSystemInDarkTheme()
    val spec = bubbleSpec(m.fromMe, dark)
    val side = if (m.fromMe) Alignment.End else Alignment.Start
    // Full-screen blurred-backdrop context menu (iOS / Telegram style): reaction pill + the floating
    // message + the action box, all hugging the message's own side (sent → right, received → left).
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }, contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = side) {
                // reaction pill
                Surface(shape = RoundedCornerShape(28.dp), color = dialogBg().copy(alpha = 0.98f), shadowElevation = 10.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, onSurf.copy(alpha = 0.08f))) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { e ->
                            Box(Modifier.size(42.dp).clip(CircleShape).clickable { onReact(e) }, contentAlignment = Alignment.Center) {
                                Text(e, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // floating copy of the long-pressed message
                Surface(color = spec.first, shape = spec.third, shadowElevation = 8.dp, modifier = Modifier.widthIn(max = 300.dp)) {
                    val preview = when { m.deleted -> "This message was deleted"; m.text.isNotBlank() -> m.text; m.mediaType != null -> "[${m.mediaType}]"; else -> "" }
                    Text(preview, color = spec.second, style = MaterialTheme.typography.bodyLarge, maxLines = 10, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                // action box — same side as the message
                Surface(shape = RoundedCornerShape(20.dp), color = dialogBg().copy(alpha = 0.98f), shadowElevation = 12.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, onSurf.copy(alpha = 0.08f)),
                    modifier = Modifier.widthIn(min = 230.dp, max = 300.dp)) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        val canEdit = m.fromMe && m.text.isNotBlank() && !m.deleted
                        val canCopy = m.text.isNotBlank() && !m.deleted
                        if (canEdit) ActionSheetItem(Icons.Filled.Edit, "Edit message", AYX_GREEN, onEdit)
                        ActionSheetItem(Icons.Filled.Info, "Message info", AYX_GREEN, onInfo)
                        if (!m.deleted) ActionSheetItem(Icons.AutoMirrored.Filled.Reply, "Reply", AYX_GREEN, onReply)
                        if (!m.deleted) ActionSheetItem(Icons.AutoMirrored.Filled.ArrowForward, "Forward", AYX_GREEN, onForward)
                        if (canCopy) ActionSheetItem(Icons.Filled.ContentCopy, "Copy", AYX_GREEN, onCopy)
                        ActionSheetItem(Icons.Filled.Checklist, "Select", AYX_GREEN, onSelect)
                        HorizontalDivider(color = onSurf.copy(alpha = 0.08f))
                        if (m.fromMe && !m.deleted) ActionSheetItem(Icons.Filled.Delete, "Delete for everyone", AYX_RED, onDeleteEveryone, destructive = true)
                        ActionSheetItem(Icons.Filled.DeleteOutline, "Delete for me", AYX_RED, onDeleteMe, destructive = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit, destructive: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (destructive) tint else MaterialTheme.colorScheme.onSurface)
    }
}

// ===== Polished edit dialog: icon, prefilled, char counter, clear, disabled-when-unchanged =====
@Composable
private fun EditMessageDialog(m: GatewayClient.Msg, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var newText by remember(m.id) { mutableStateOf(m.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogBg(),
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Filled.Edit, null, tint = AYX_GREEN) },
        title = { Text("Edit message") },
        text = {
            Column {
                OutlinedTextField(
                    newText, { newText = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message") }, shape = RoundedCornerShape(14.dp), maxLines = 6,
                    trailingIcon = { if (newText.isNotEmpty()) IconButton(onClick = { newText = "" }) { Icon(Icons.Filled.Clear, "clear") } }
                )
                Text("${newText.length} chars", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(newText.trim()) }, enabled = newText.isNotBlank() && newText.trim() != m.text) { Text("Save changes", color = AYX_GREEN) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ===== Message info: timestamp + delivery/read state =====
@Composable
private fun MessageInfoDialog(m: GatewayClient.Msg, onDismiss: () -> Unit) {
    val statusText = when {
        !m.fromMe -> "Received"
        m.status >= 4 -> "Read"
        m.status == 3 -> "Delivered"
        m.status == 2 -> "Sent"
        else -> "Pending"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = dialogBg(),
        shape = RoundedCornerShape(24.dp),
        icon = { Icon(Icons.Filled.Info, null, tint = AYX_GREEN) },
        title = { Text("Message info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("Direction", if (m.fromMe) "Sent by you" else "Received")
                InfoRow("Status", statusText)
                InfoRow("Time", fmt(m.ts))
                if (m.edited) InfoRow("Edited", "Yes")
                if (m.forwarded) InfoRow("Forwarded", "Yes")
                m.mediaType?.let { InfoRow("Type", it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * Floating, rounded, frosted chat header (Gemini / Telegram / ChatGPT style).
 * Drawn OVER the edge-to-edge chat, so the wallpaper and messages show behind the
 * translucent glass. The "Chat header blur" setting drives how see-through it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatGlassHeader(
    jid: String,
    name: String,
    presence: GatewayClient.Presence?,
    dpCache: MutableMap<String, ImageBitmap?>,
    isBlocked: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onWallpaper: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val frost = ChatStyle.headerAlpha()                       // 1f = solid, lower = more see-through
    val pill = MaterialTheme.colorScheme.surface.copy(alpha = frost)
    val onPill = MaterialTheme.colorScheme.onSurface
    val borderCol = onPill.copy(alpha = 0.12f)
    val pillShape = RoundedCornerShape(24.dp)
    var menu by remember { mutableStateOf(false) }

    // ONE floating frosted card: back + avatar + name/presence + edit + menu (original WhatsApp layout, no separate arrow chip)
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().clip(pillShape).background(pill).border(1.dp, borderCol, pillShape)) {
            Row(Modifier.padding(start = 2.dp, end = 2.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back", tint = onPill) }
                Box(Modifier.clip(CircleShape).clickable { onAvatarClick() }) { Avatar(jid, name, dpCache, 38.dp, CircleShape) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, fontWeight = FontWeight.Bold, color = onPill,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.basicMarquee())
                    val sub = presence?.let { pr -> if (pr.online) "online" else if (pr.lastSeen > 0) "last seen " + fmt(pr.lastSeen * 1000) else "" } ?: ""
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = onPill.copy(alpha = 0.7f), maxLines = 1)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "menu", tint = onPill) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Set name") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Change wallpaper") }, onClick = { menu = false; onWallpaper() })
                        if (!isBlocked) DropdownMenuItem(text = { Text("Block contact") }, onClick = { menu = false; onBlock() })
                        else DropdownMenuItem(text = { Text("Unblock contact") }, onClick = { menu = false; onUnblock() })
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlayer(url: String, tint: Color) {
    var playing by remember(url) { mutableStateOf(false) }
    var progress by remember(url) { mutableStateOf(0f) }
    var ready by remember(url) { mutableStateOf(false) }
    var wantPlay by remember(url) { mutableStateOf(false) }   // tapped before prepare finished → auto-start
    var retried by remember(url) { mutableStateOf(false) }
    val player = remember(url) { MediaPlayer() }
    DisposableEffect(url) {
        runCatching {
            player.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            player.setDataSource(url)
            player.setOnPreparedListener { ready = true; if (wantPlay) { runCatching { player.start(); playing = true } } }
            player.setOnCompletionListener { playing = false; progress = 0f }
            player.setOnErrorListener { mp, _, _ ->
                ready = false; playing = false
                if (!retried) { retried = true; runCatching { mp.reset(); mp.setDataSource(url); mp.prepareAsync() } }
                true
            }
            player.prepareAsync()
        }
        onDispose { runCatching { if (player.isPlaying) player.stop() }; runCatching { player.release() } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            runCatching { if (player.duration > 0) progress = player.currentPosition.toFloat() / player.duration }
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(min = 200.dp)) {
        Box(Modifier.size(38.dp).background(tint.copy(alpha = 0.22f), CircleShape).clickable {
            if (playing) { runCatching { player.pause() }; playing = false }
            else if (ready) { runCatching { player.start(); playing = true } }
            else { wantPlay = true }   // not prepared yet → start automatically once ready
        }, contentAlignment = Alignment.Center) {
            if (!ready && wantPlay) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "play", tint = tint, modifier = Modifier.size(24.dp))
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(4.dp), color = tint, trackColor = tint.copy(alpha = 0.3f))
    }
}

// delivery ticks for sent messages: clock -> single -> double -> blue double (read)
@Composable
private fun MsgTicks(status: Int, base: Color) {
    val c = base.copy(alpha = 0.75f)
    when {
        status <= 1 -> Icon(Icons.Filled.Schedule, "sending", modifier = Modifier.size(12.dp), tint = base.copy(alpha = 0.55f))
        status == 2 -> Icon(Icons.Filled.Done, "sent", modifier = Modifier.size(15.dp), tint = c)
        status == 3 -> Icon(Icons.Filled.DoneAll, "delivered", modifier = Modifier.size(15.dp), tint = c)
        else -> Icon(Icons.Filled.DoneAll, "read", modifier = Modifier.size(15.dp), tint = Color(0xFF34B7F1))
    }
}

// bubble color/text/shape for the selected chat bubble style (reads ChatStyle live)
private fun bubbleSpec(fromMe: Boolean, dark: Boolean): Triple<Color, Color, Shape> {
    val recvGrey = if (dark) Color(0xFF2C2C2E) else Color(0xFFE9E9EB)
    val recvText = if (dark) Color.White else Color.Black
    val base = styleSpec(fromMe, dark, recvGrey, recvText)
    // custom color override (keeps the style's shape); 0 = follow the style default
    val custom = if (fromMe) ChatStyle.sentColor.value else ChatStyle.recvColor.value
    if (custom != 0) return Triple(Color(custom), ChatStyle.textOn(custom), base.third)
    return base
}

private fun styleSpec(fromMe: Boolean, dark: Boolean, recvGrey: Color, recvText: Color): Triple<Color, Color, Shape> {
    return when (ChatStyle.bubbleStyle.value) {
        "whatsapp" ->
            if (fromMe) Triple(if (dark) Color(0xFF005C4B) else Color(0xFFD9FDD3), if (dark) Color.White else Color.Black, RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp))
            else Triple(if (dark) Color(0xFF202C33) else Color.White, recvText, RoundedCornerShape(8.dp, 8.dp, 8.dp, 2.dp))
        "material" ->
            if (fromMe) Triple(ThemeStore.accentColor(), Color.Black, RoundedCornerShape(14.dp))
            else Triple(if (dark) Color(0xFF2A2A2E) else Color(0xFFE7E0EC), recvText, RoundedCornerShape(14.dp))
        "rounded" ->
            if (fromMe) Triple(IOS_BLUE, Color.White, RoundedCornerShape(22.dp))
            else Triple(recvGrey, recvText, RoundedCornerShape(22.dp))
        "compact" ->
            if (fromMe) Triple(IOS_BLUE, Color.White, RoundedCornerShape(9.dp))
            else Triple(recvGrey, recvText, RoundedCornerShape(9.dp))
        "gb" ->
            if (fromMe) Triple(Color(0xFF128C7E), Color.White, RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
            else Triple(if (dark) Color(0xFF1F2C34) else Color(0xFFEAEAEA), recvText, RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp))
        else ->
            if (fromMe) Triple(IOS_BLUE, Color.White, RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
            else Triple(recvGrey, recvText, RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(m: GatewayClient.Msg, previewCache: MutableMap<String, ImageBitmap?>, dpCache: MutableMap<String, ImageBitmap?>, onMedia: (GatewayClient.Msg) -> Unit, onShare: (GatewayClient.Msg) -> Unit, onDownload: (GatewayClient.Msg) -> Unit, onReply: (GatewayClient.Msg) -> Unit, onAvatarClick: (String) -> Unit, selMode: Boolean = false, selected: Boolean = false, onToggleSelect: (GatewayClient.Msg) -> Unit = {}, onTranslate: (GatewayClient.Msg) -> Unit = {}, onLongClick: (GatewayClient.Msg) -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val spec = bubbleSpec(m.fromMe, dark)
    val bubbleColor = spec.first
    val textColor = spec.second
    val shape = spec.third
    val isVideo = m.mediaType == "video"
    val hasMedia = m.mediaType != null

    val name = m.mediaName
    LaunchedEffect(name) { if (name != null && !previewCache.containsKey(name)) previewCache[name] = loadPreview(ctx, m) }
    val preview = (name?.let { previewCache[it] }) ?: remember(m.thumb) { decodeThumb(m.thumb) }

    var swipeX by remember(m.id) { mutableStateOf(0f) }
    Row(Modifier.fillMaxWidth()
        .background(if (selMode && selected) AYX_GREEN.copy(alpha = 0.14f) else Color.Transparent)
        .then(if (selMode) Modifier.clickable { onToggleSelect(m) } else Modifier)
        .padding(vertical = 1.dp)
        .pointerInput(m.id, selMode) {
            if (!selMode) detectHorizontalDragGestures(
                onDragEnd = { if (swipeX > 55f) onReply(m); swipeX = 0f },
                onDragCancel = { swipeX = 0f },
                onHorizontalDrag = { _, amt -> swipeX = (swipeX + amt).coerceIn(0f, 130f) }
            )
        }
        .offset { IntOffset(swipeX.roundToInt(), 0) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
        if (selMode) {
            Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (selected) AYX_GREEN else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, end = 6.dp).size(22.dp))
        }
        if (!m.fromMe && m.chat.endsWith("@g.us") && !m.sender.isNullOrBlank()) {
            Box(Modifier.clip(CircleShape).clickable { onAvatarClick(m.sender!!) }) { Avatar(m.sender!!, m.name.ifBlank { "?" }, dpCache, 30.dp) }
            Spacer(Modifier.width(6.dp))
        }
        Surface(color = bubbleColor, shape = shape,
            modifier = Modifier.widthIn(max = 290.dp).combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (selMode) onToggleSelect(m) }, onDoubleClick = { if (!selMode) onTranslate(m) }, onLongClick = { if (!selMode) onLongClick(m) })) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (m.forwarded && !m.deleted && ChatStyle.showForwardTag.value) {
                    Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(13.dp),
                            tint = if (m.fromMe) Color.White.copy(alpha = 0.7f) else textColor.copy(alpha = 0.55f))
                        Spacer(Modifier.width(4.dp))
                        Text("Forwarded", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
                            color = if (m.fromMe) Color.White.copy(alpha = 0.7f) else textColor.copy(alpha = 0.55f))
                    }
                }
                if (!m.quotedText.isNullOrBlank()) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Box(Modifier.width(3.dp).height(30.dp).background(if (m.fromMe) Color.White.copy(alpha = 0.7f) else IOS_BLUE, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(6.dp))
                        Text(m.quotedText!!, style = MaterialTheme.typography.bodySmall,
                            color = if (m.fromMe) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!m.fromMe && m.chat.endsWith("@g.us") && !m.name.isNullOrBlank()) {
                    Text(SetName.get(m.sender ?: "") ?: m.name, style = MaterialTheme.typography.labelMedium, color = IOS_BLUE,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (hasMedia && preview != null) {
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onMedia(m) }, contentAlignment = Alignment.Center) {
                        Image(preview, "media", Modifier.widthIn(min = 220.dp, max = 280.dp).heightIn(max = 340.dp), contentScale = ContentScale.Crop)
                        if (isVideo) Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, "play", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (m.mediaType == "audio") {
                        if (m.mediaName != null) AudioPlayer(GatewayClient.mediaUrl(m.mediaName!!), textColor)
                        else Text("Voice message (enable Save media)", color = textColor, style = MaterialTheme.typography.bodySmall)
                    }
                    if (m.text.isNotBlank()) LinkText(m.text, textColor)
                    else if (hasMedia && preview == null && m.mediaType != "audio") Text("[${m.mediaType}]", color = textColor, style = MaterialTheme.typography.bodySmall)
                    // translated text (double-tap) shown under the original with a translate icon
                    val tr = m.id?.let { TranslateStore.cache[it] }
                    if (!tr.isNullOrBlank()) {
                        Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(1.dp).background((if (m.fromMe) Color.White else textColor).copy(alpha = 0.2f)))
                        Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.Translate, "translated", modifier = Modifier.size(13.dp).padding(top = 2.dp), tint = if (m.fromMe) Color.White.copy(alpha = 0.8f) else IOS_BLUE)
                            Spacer(Modifier.width(4.dp))
                            Text(tr, color = textColor.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                        }
                    }
                    if (!m.reaction.isNullOrBlank()) {
                        Surface(shape = CircleShape, color = if (m.fromMe) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.15f)) {
                            Text(m.reaction, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(m.ts), style = MaterialTheme.typography.labelSmall,
                            color = if (m.fromMe) Color.White.copy(alpha = 0.7f) else textColor.copy(alpha = 0.6f))
                        if (m.fromMe && !m.deleted) MsgTicks(m.status, textColor)
                        if (m.edited && !m.deleted) Text("edited", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
                            color = if (m.fromMe) Color.White.copy(alpha = 0.6f) else textColor.copy(alpha = 0.55f))
                        if (m.deleted) Text("deleted", color = Color(0xFFFF4D4D), style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
                        if (m.mediaName != null) {
                            IconButton(onClick = { onDownload(m) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.Download, "download", modifier = Modifier.size(15.dp), tint = if (m.fromMe) Color.White else textColor)
                            }
                            IconButton(onClick = { onShare(m) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Filled.Share, "share", modifier = Modifier.size(15.dp), tint = if (m.fromMe) Color.White else textColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

// sub-page title shown in the top app bar (single source of the back arrow)
private fun settingsTitle(page: String): String = when (page) {
    "general" -> "General"; "autoreply" -> "Auto-reply"; "ai" -> "AI Assistant"; "chat" -> "Chat Settings"
    "imageai" -> "Image AI"; "aimemory" -> "AI Memory"; "voice" -> "AI Voice"; "font" -> "App Font"; "emoji" -> "Emoji"
    "wallpaper" -> "Chat Wallpaper"; "appearance" -> "Appearance"; "about" -> "About"; "support" -> "Support Development"; else -> "Settings"
}
// parent page for nested back (Wallpaper under Chat Settings; Image AI / AI Memory / Voice under AI Assistant; Font under General)
private fun settingsParent(page: String): String = when (page) { "wallpaper" -> "chat"; "imageai" -> "ai"; "aimemory" -> "ai"; "font" -> "general"; "emoji" -> "general"; else -> "home" }

@Composable
private fun SettingsScreen(status: GatewayClient.Status, settings: GatewayClient.Settings, page: String, onPage: (String) -> Unit, wallpaperVersion: Int,
    onToggle: (JSONObject) -> Unit, onRules: (List<GatewayClient.Rule>) -> Unit, onLogout: () -> Unit, ctx: Context,
    onPickWallpaper: () -> Unit, onRemoveWallpaper: () -> Unit, onPickPhoto: () -> Unit,
    onSaveName: (String) -> Unit,
    dpCache: MutableMap<String, ImageBitmap?>, messages: List<GatewayClient.Msg>) {
    // back arrow + title live in the top app bar; sub-pages have no second arrow
    AnimatedContent(targetState = page, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) }, label = "setpage") { p ->
        when (p) {
            "general" -> SettingsSubPage { GeneralSettings(settings, onToggle, onOpenFont = { onPage("font") }, onOpenEmoji = { onPage("emoji") }) }
            "font" -> SettingsSubPage { FontSettings(ctx) }
            "emoji" -> SettingsSubPage { EmojiSettings(ctx) }
            "autoreply" -> SettingsSubPage { AutoReplySection(settings, onToggle, onRules) }
            "ai" -> SettingsSubPage { AiSettings(settings, onToggle, messages, dpCache, onOpenImageAi = { onPage("imageai") }, onOpenMemory = { onPage("aimemory") }) }
            "imageai" -> SettingsSubPage { ImageAiSettings(settings, onToggle, ctx) }
            "aimemory" -> AiMemoryScreen()   // has its OWN scroll — must NOT be wrapped in SettingsSubPage
            "voice" -> VoicePickerScreen(settings, onToggle, ctx)
            "chat" -> SettingsSubPage { ChatSettings(onOpenWallpaper = { onPage("wallpaper") }) }
            "wallpaper" -> SettingsSubPage { WallpaperSettings(ctx, wallpaperVersion, onPickWallpaper, onRemoveWallpaper) }
            "appearance" -> SettingsSubPage { AppearanceSettings() }
            "about" -> SettingsSubPage { AboutSettings(ctx, onLogout) }
            "support" -> SettingsSubPage { SupportSettings(ctx) }
            else -> SettingsHome(status) { onPage(it) }
        }
    }
}

// category accent colors (subtle, per-category)
private val CAT_GENERAL = Color(0xFFB69DF8)
private val CAT_AUTOREPLY = Color(0xFFFFB26B)
private val CAT_AI = Color(0xFF82AAFF)
private val CAT_WALLPAPER = Color(0xFF4DD0C4)
private val CAT_APPEARANCE = Color(0xFFFF7EB6)
private val CAT_ABOUT = Color(0xFF6BA8FF)
private val CAT_SUPPORT = Color(0xFF4DD07A)
private val OK_GREEN = Color(0xFF4DD07A)
private val WARN_AMBER = Color(0xFFFFC24D)
private val ERR_RED = Color(0xFFFF5A5A)

@Composable
private fun SettingsHome(status: GatewayClient.Status, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        StatusCard(status)
        Spacer(Modifier.height(2.dp))
        CategoryCard(Icons.Filled.Tune, CAT_GENERAL, "General", "Online, privacy and messages") { onOpen("general") }
        CategoryCard(Icons.Filled.QuestionAnswer, CAT_AUTOREPLY, "Auto-reply", "Keyword rules and automatic replies") { onOpen("autoreply") }
        CategoryCard(Icons.Filled.AutoAwesome, CAT_AI, "AI Assistant", "AI replies, groups and language") { onOpen("ai") }
        CategoryCard(Icons.Filled.GraphicEq, Color(0xFF4DD0C4), "AI Voice", "Voice reply, voice notes & voice artists") { onOpen("voice") }
        CategoryCard(Icons.Filled.Chat, CAT_WALLPAPER, "Chat", "Wallpaper, bubble style and header") { onOpen("chat") }
        CategoryCard(Icons.Filled.Palette, CAT_APPEARANCE, "Appearance", "Theme and accent color") { onOpen("appearance") }
        CategoryCard(Icons.Filled.Info, CAT_ABOUT, "About", "App information and reset") { onOpen("about") }
        CategoryCard(Icons.Filled.Favorite, CAT_SUPPORT, "Support Development", "Donate via UPI or crypto to support AyX") { onOpen("support") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(status: GatewayClient.Status) {
    val connected = status.registered && status.connection == "open"
    val dot = when { connected -> OK_GREEN; status.connection == "connecting" -> WARN_AMBER; else -> ERR_RED }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PulseDot(dot)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (connected) "Connected" else if (status.registered) "Reconnecting…" else "Not linked", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text("Engine " + (if (status.reachable) "up" else "down") + " · " + status.connection + " · linked " + (if (status.registered) "yes" else "no"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(950), repeatMode = RepeatMode.Reverse), label = "a")
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(color.copy(alpha = a * 0.22f)))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = a)))
    }
}

@Composable
private fun CategoryCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, desc: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun SettingsSubPage(content: @Composable ColumnScope.() -> Unit) {
    // title + the single back arrow are provided by the top app bar
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp, horizontal = 12.dp), content = content)
        }
    }
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconChip(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// Dedicated Font & Emoji page (opened from General) — tap a font to apply it app-wide; upload custom fonts; pick an emoji style.
@Composable
private fun FontSettings(ctx: Context) {
    var current by remember { mutableStateOf(FontStore.appFont) }
    val scope = rememberCoroutineScope()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = FontStore.addCustomFont(ctx, uri)
            if (name != null) { FontStore.chooseFont(name); current = name; Toast.makeText(ctx, "Font added: $name", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(ctx, "Couldn't load that font", Toast.LENGTH_SHORT).show()
        }
    }
    SettingsGroup("Preview") {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("The quick brown fox 123 😀🎉❤️", fontFamily = FontStore.family(ctx, current), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Tap any font below — it applies across the whole app instantly.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SettingsGroup("App font") {
        FontStore.fonts.keys.forEach { name ->
            val fam = FontStore.family(ctx, name)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { FontStore.chooseFont(name); current = name }.padding(horizontal = 6.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), fontFamily = fam, style = MaterialTheme.typography.bodyLarge)
                if (name == current) Icon(Icons.Filled.CheckCircle, "selected", tint = AYX_GREEN)
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { fontPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Upload custom font (.ttf / .otf)")
        }
    }
}

// Dedicated Emoji page — download an emoji style and apply it app-wide (instantly).
@Composable
private fun EmojiSettings(ctx: Context) {
    val scope = rememberCoroutineScope()
    SettingsGroup("Preview") {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("😀 😂 ❤️ 👍 🙏 🎉 🔥 ✨ 😎 🥳", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("Pick a style — it downloads once, then applies across the whole app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SettingsGroup("Emoji style") {
        EmojiStylePicker(ctx, scope)
    }
}

@Composable
private fun EmojiStylePicker(ctx: Context, scope: CoroutineScope) {
    var selected by remember { mutableStateOf(EmojiStore.selected) }
    var downloading by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        EmojiStyleRow("System default", "Your phone's built-in emoji", selected == EmojiStore.SYSTEM, true, false) {
            EmojiStore.choose(EmojiStore.SYSTEM); selected = EmojiStore.SYSTEM
            Toast.makeText(ctx, "System emoji restored", Toast.LENGTH_SHORT).show()
        }
        EmojiStore.styles.forEach { st ->
            val have = EmojiStore.isDownloaded(ctx, st.key)
            EmojiStyleRow(st.name, if (have) "Downloaded · tap to apply" else "Tap to download & apply", selected == st.name, have, downloading == st.name) {
                if (downloading != null) return@EmojiStyleRow
                if (have) {
                    EmojiStore.choose(st.name); selected = st.name
                    if (EmojiStore.activeFont(ctx) == null) Toast.makeText(ctx, "Couldn't load this emoji font — re-download it", Toast.LENGTH_LONG).show() else
                    Toast.makeText(ctx, "${st.name} emoji applied", Toast.LENGTH_SHORT).show()
                } else {
                    downloading = st.name
                    scope.launch {
                        val err = EmojiStore.download(ctx, st)
                        downloading = null
                        if (err == null) { EmojiStore.choose(st.name); selected = st.name; Toast.makeText(ctx, "${st.name} downloaded & applied", Toast.LENGTH_LONG).show() }
                        else Toast.makeText(ctx, "Download failed: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Text("Saved to Download/WhatsAyX/fonts/emoji · applies instantly (Android 10+).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

@Composable
private fun EmojiStyleRow(name: String, sub: String, selectedNow: Boolean, downloaded: Boolean, loading: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !loading) { onClick() }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            loading -> CircularProgressIndicator(Modifier.size(22.dp), color = AYX_GREEN, strokeWidth = 2.5.dp)
            selectedNow -> Icon(Icons.Filled.CheckCircle, "selected", tint = AYX_GREEN)
            downloaded -> Icon(Icons.Filled.CheckCircle, "downloaded", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            else -> Icon(Icons.Filled.Download, "download", tint = IOS_BLUE)
        }
    }
}

@Composable
private fun GeneralSettings(settings: GatewayClient.Settings, onToggle: (JSONObject) -> Unit, onOpenFont: () -> Unit, onOpenEmoji: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var confirmClearData by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val mediaSize = remember(refreshTick) { AppStorage.mediaSize(ctx) }
    val cacheSize = remember(refreshTick) { AppStorage.cacheSize(ctx) }

    SettingsGroup("Online & Privacy") {
        SettingRow(Icons.Filled.Bolt, CAT_GENERAL, "Always online", "Keep showing online", settings.alwaysOnline) { onToggle(JSONObject().put("alwaysOnline", it)) }
        SettingRow(Icons.Filled.CloudOff, Color(0xFFFF7EB6), "Freeze last seen", "Never broadcast online (overrides Always online)", settings.stayOffline) { onToggle(JSONObject().put("stayOffline", it)) }
        SettingRow(Icons.Filled.Bolt, Color(0xFF4DD0C4), "Human-like presence", "For each AI reply: read it, come online + type, reply, then go back offline", settings.aiPresenceFlow) { onToggle(JSONObject().put("aiPresenceFlow", it)) }
        SettingRow(Icons.Filled.RemoveRedEye, Color(0xFF82AAFF), "Hide status view", "Don't show senders you saw their status", settings.hideStatusRead) { onToggle(JSONObject().put("hideStatusRead", it)) }
    }
    SettingsGroup("Messages & Media") {
        SettingRow(Icons.Filled.DoneAll, CAT_WALLPAPER, "Auto-read messages", "Mark incoming chats as read", settings.autoRead) { onToggle(JSONObject().put("autoRead", it)) }
        SettingRow(Icons.Filled.PermMedia, CAT_AUTOREPLY, "Save media", "Download incoming photos/videos (needed for view, deleted media)", settings.saveMedia) { onToggle(JSONObject().put("saveMedia", it)) }
        SettingRow(Icons.AutoMirrored.Filled.ArrowForward, CAT_AI, "Forwarded tag", "Show the \"Forwarded\" label on forwarded messages", ChatStyle.showForwardTag.value) { ChatStyle.setShowForwardTag(it) }
    }
    SettingsGroup("Appearance") {
        ActionRow(Icons.Filled.TextFields, CAT_GENERAL, "App font", "Current: ${FontStore.appFont}  ·  preview, pick or upload .ttf/.otf") { onOpenFont() }
        ActionRow(Icons.Filled.EmojiEmotions, CAT_GENERAL, "Emoji", "Current: ${EmojiStore.selected}  ·  download & apply emoji style") { onOpenEmoji() }
    }

    SettingsGroup("Message translation") {
        Text("Double-tap any message in a chat to translate it. Pick how it works:",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off" to "Off", "manual" to "Double-tap", "auto" to "Auto (all)").forEach { (k, lbl) ->
                FilterChip(selected = TranslateStore.mode == k, onClick = { TranslateStore.chooseMode(k) }, label = { Text(lbl) })
            }
        }
        var tlang by remember { mutableStateOf(TranslateStore.lang) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(tlang, { tlang = it }, label = { Text("Translate to") }, singleLine = true,
            placeholder = { Text("e.g. English, Hindi, Roman Hindi, Bangla") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = { TranslateStore.chooseLang(tlang.trim().ifBlank { "English" }); Toast.makeText(ctx, "Translate language saved", Toast.LENGTH_SHORT).show() }) { Text("Save") }
        }
    }

    SettingsGroup("Background & battery") {
        SettingRow(Icons.Filled.CloudOff, CAT_AI, "Run in background", "Keeps a silent notification so messages arrive when the app is closed. Turn OFF to remove the notification (messages then arrive only while the app is open).", ChatStyle.runBackground.value) { ChatStyle.setRunBackground(it) }
        ActionRow(Icons.Filled.Bolt, WARN_AMBER, "Allow battery (no optimization)", "Keep the gateway alive in the background") {
            runCatching {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
        ActionRow(Icons.Filled.Settings, CAT_GENERAL, "Allow app to open / autostart", "Open app settings to enable autostart & background") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    SettingsGroup("Storage & data") {
        StorageRow(Icons.Filled.PermMedia, CAT_AUTOREPLY, "Downloaded media", AppStorage.fmtSize(mediaSize), enabled = !busy) {
            busy = true
            scope.launch {
                runCatching { GatewayClient.clearCache() }
                AppStorage.clearCacheLocal(ctx); refreshTick++; busy = false
                Toast.makeText(ctx, "Media cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
        StorageRow(Icons.Filled.Storage, CAT_AI, "Cache", AppStorage.fmtSize(cacheSize), enabled = !busy) {
            AppStorage.clearCacheLocal(ctx); refreshTick++
            Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
        }
        ActionRow(Icons.Filled.DeleteSweep, OK_GREEN, "Clear cache", "Free space — keeps chats, settings and your login") {
            busy = true
            scope.launch {
                runCatching { GatewayClient.clearCache() }
                AppStorage.clearCacheLocal(ctx); refreshTick++; busy = false
                Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
            }
        }
        ActionRow(Icons.Filled.Delete, ERR_RED, "Clear app data", "Removes everything local — you'll need to link again") { confirmClearData = true }
    }

    if (confirmClearData) {
        AlertDialog(
            onDismissRequest = { confirmClearData = false },
            containerColor = dialogBg(),
            shape = RoundedCornerShape(24.dp),
            icon = { Icon(Icons.Filled.Delete, null, tint = ERR_RED) },
            title = { Text("Clear app data?") },
            text = {
                Text("This removes WhatsAyX local data — chats, statuses, cached media, names, wallpaper, settings and the login/session. Your account is not deleted, but you'll need to link this device again with QR or pairing code. The app will restart.",
                    style = MaterialTheme.typography.bodyMedium)
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearData = false
                    scope.launch {
                        runCatching { GatewayClient.clearData() }
                        AppStorage.clearDataLocal(ctx)
                        Toast.makeText(ctx, "Data cleared — restarting", Toast.LENGTH_SHORT).show()
                        delay(700)
                        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        if (i != null) ctx.startActivity(i)
                        Runtime.getRuntime().exit(0)
                    }
                }) { Text("Clear Data", color = ERR_RED) }
            },
            dismissButton = { TextButton(onClick = { confirmClearData = false }) { Text("Cancel") } }
        )
    }
}

// storage category row: icon, title, size on the right + a trash button
@Composable
private fun StorageRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, size: String, enabled: Boolean, onClean: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconChip(icon, tint)
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(size, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onClean, enabled = enabled) { Icon(Icons.Filled.DeleteOutline, "clean", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// tappable settings row (opens something) — same look as SettingRow but with a chevron
@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, desc: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconChip(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AutoReplySection(settings: GatewayClient.Settings, onToggle: (JSONObject) -> Unit, onRules: (List<GatewayClient.Rule>) -> Unit) {
    var match by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("contains") }
    SettingsGroup("Automatic replies") {
        SettingRow(Icons.Filled.QuestionAnswer, CAT_AUTOREPLY, "Auto-reply enabled", "Reply automatically to incoming messages", settings.autoReplyEnabled) { onToggle(JSONObject().put("autoReplyEnabled", it)) }
    }
    Text("RULES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    if (settings.rules.isEmpty()) {
        EmptyState(Icons.Filled.QuestionAnswer, "No auto-reply rules", "Create a keyword rule to automatically respond to messages.")
    } else {
        settings.rules.forEachIndexed { i, r ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.match, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = CAT_AUTOREPLY.copy(alpha = 0.18f)) {
                                Text(r.mode, style = MaterialTheme.typography.labelSmall, color = CAT_AUTOREPLY, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        Text("→ " + r.reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onRules(settings.rules.toMutableList().also { it.removeAt(i) }) }) { Icon(Icons.Filled.Delete, "delete", tint = ERR_RED) }
                }
            }
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("New rule", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(match, { match = it }, label = { Text("If message contains…") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reply, { reply = it }, label = { Text("Reply with…") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("contains", "exact", "starts").forEach { md ->
                    FilterChip(selected = mode == md, onClick = { mode = md }, label = { Text(md) })
                }
            }
            Button(onClick = { if (match.isNotBlank() && reply.isNotBlank()) { onRules(settings.rules + GatewayClient.Rule(match.trim(), reply.trim(), mode)); match = ""; reply = "" } },
                enabled = match.isNotBlank() && reply.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add rule") }
        }
    }
}

@Composable
private fun AiSettings(settings: GatewayClient.Settings, onToggle: (JSONObject) -> Unit, messages: List<GatewayClient.Msg>, dpCache: MutableMap<String, ImageBitmap?>, onOpenImageAi: () -> Unit, onOpenMemory: () -> Unit) {
    val ctx = LocalContext.current
    var showExcludePicker by remember { mutableStateOf(false) }
    fun setExcludes(list: List<String>) { onToggle(JSONObject().put("aiExcludeJids", org.json.JSONArray(list.distinct()))) }

    Text("Powered by Groq AI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))

    // MAIN box — the three reply types + group
    SettingsGroup("Replies") {
        SettingRow(Icons.Filled.AutoAwesome, CAT_AI, "AI reply (master)", "Master switch — OFF stops ALL AI replies (text, image, voice, groups). Keyword rules still work", settings.aiReplyEnabled) { onToggle(JSONObject().put("aiReplyEnabled", it)) }
        SettingRow(Icons.Filled.Image, Color(0xFFFFB26B), "Image reply", "AI looks at incoming images (vision) then swipe-replies", settings.aiReplyImage) { onToggle(JSONObject().put("aiReplyImage", it)) }
        SettingRow(Icons.Filled.QuestionAnswer, Color(0xFFB69DF8), "Group AI reply", "Answer greetings/questions in groups (max 10/day); /ai works anytime", settings.groupAiEnabled) { onToggle(JSONObject().put("groupAiEnabled", it)) }
        Text("Image replies come as a swipe-left quote on the exact message. Voice note reply is now in \"AI Voice\" settings.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 6.dp))
    }

    // Image AI + AI Memory — each opens its own page
    SettingsGroup("AI tools") {
        ActionRow(Icons.Filled.Image, Color(0xFFFFB26B), "Image AI", "Vision provider, auto-fallback, OCR & API keys") { onOpenImageAi() }
        ActionRow(Icons.Filled.Memory, CAT_AI, "AI Memory", "Har contact ki chat history, naam & language") { onOpenMemory() }
    }

    // Chat Forward — send real conversation context to the AI (cf.kt)
    ChatForwardSettings(settings, onToggle)

    // Custom commands
    SettingsGroup("Commands") {
        SettingRow(Icons.Filled.Terminal, Color(0xFFB69DF8), "Enable /create, /prompt & /voice", "/create <text> → image · /prompt on an image → its prompt · /voice <text> → voice note", settings.aiCommandsEnabled) { onToggle(JSONObject().put("aiCommandsEnabled", it)) }
    }

    // Reply language — pick ONE language so the AI stops mixing Hindi/Bangla
    SettingsGroup("Reply language") {
        Text("Pick one language — the AI replies only in it (stops Hindi/Bangla mixing). \"Auto\" mirrors the sender's language.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        LanguageSelector(settings.aiLangMode) {
            onToggle(JSONObject().put("aiLangMode", it))
            Toast.makeText(ctx, "Reply language: " + it, Toast.LENGTH_SHORT).show()
        }
        if (settings.aiLangMode == "custom") {
            var lang by remember(settings.aiReplyLang) { mutableStateOf(settings.aiReplyLang) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(lang, { lang = it }, placeholder = { Text("e.g. Reply only in Roman Bangla, English letters") },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { onToggle(JSONObject().put("aiReplyLang", lang.trim())); Toast.makeText(ctx, "Language saved", Toast.LENGTH_SHORT).show() }) { Text("Save") }
            }
        }
    }

    // Exclude list — chats where NO auto-reply / AI reply is sent ("reply nothing to this person")
    SettingsGroup("Don't reply to these chats") {
        if (settings.aiExcludeJids.isEmpty()) {
            Text("Add chats here to stop AI (and keyword auto-reply) from replying to them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            settings.aiExcludeJids.forEach { jid ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(jid, chatTitleOf(messages, jid), dpCache, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(chatTitleOf(messages, jid), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { setExcludes(settings.aiExcludeJids - jid) }) { Icon(Icons.Filled.Close, "remove", tint = AYX_RED) }
                }
            }
        }
        ActionRow(Icons.Filled.Add, CAT_AI, "Add chat to exclude", "Pick chats AI must not reply to") { showExcludePicker = true }
    }

    if (showExcludePicker) {
        ForwardPicker(messages, dpCache, onDismiss = { showExcludePicker = false },
            onForward = { sel -> setExcludes(settings.aiExcludeJids + sel); showExcludePicker = false },
            title = "Exclude chats", action = "Exclude")
    }

    var url by remember { mutableStateOf(settings.aiApiUrl) }
    var key by remember { mutableStateOf(settings.aiApiKey) }
    var model by remember { mutableStateOf(settings.aiModel) }
    var sys by remember { mutableStateOf(settings.aiSystemPrompt) }
    var showKey by remember { mutableStateOf(false) }
    fun saveApi() {
        onToggle(JSONObject().put("aiApiUrl", url.trim()).put("aiApiKey", key.trim()).put("aiModel", model.trim())
            .put("aiSystemPrompt", sys))
        Toast.makeText(ctx, "AI settings saved", Toast.LENGTH_SHORT).show()
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // header row: title left, Save button on the right
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("API configuration (text/voice)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { saveApi() }) { Text("Save") }
            }
            LinkRow(Icons.Filled.Language, Color(0xFF4DD07A), "Get Groq key", "console.groq.com → tap to create key") { openUrl(ctx, "https://console.groq.com/keys") }
            OutlinedTextField(url, { url = it }, label = { Text("API URL (text/voice)") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "toggle key") } },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model (text)") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sys, { sys = it }, label = { Text("System prompt (optional)") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        }
    }
}

// format the /visiontest result into a readable message for the dialog
private fun fmtVisionTest(r: JSONObject): String {
    val sb = StringBuilder()
    if (r.optBoolean("ok", false)) {
        sb.append("✅ Working!\nVia: ").append(r.optString("via")).append("\n\nAI ne image me dekha:\n\"").append(r.optString("text")).append("\"")
    } else {
        sb.append("❌ ").append(r.optString("error", "Image AI test failed"))
    }
    val rep = r.optJSONArray("report")
    if (rep != null && rep.length() > 0) {
        sb.append("\n\nDetails:")
        for (i in 0 until rep.length()) {
            val o = rep.optJSONObject(i) ?: continue
            sb.append("\n• ").append(o.optString("host")).append(" · ").append(o.optString("model")).append(" → ")
            if (o.optBoolean("ok")) sb.append("OK")
            else sb.append("HTTP ").append(o.optInt("status")).append(" ").append(o.optString("err", "").take(90))
        }
    }
    return sb.toString()
}

// IMAGE AI — its own page: dedicated vision provider + automatic fallback + on-device OCR + key links
@Composable
private fun ImageAiSettings(settings: GatewayClient.Settings, onToggle: (JSONObject) -> Unit, ctx: Context) {
    val scope = rememberCoroutineScope()
    var vision by remember { mutableStateOf(settings.aiVisionModel) }
    var visionUrl by remember { mutableStateOf(settings.aiVisionApiUrl) }
    var visionKey by remember { mutableStateOf(settings.aiVisionApiKey) }
    var vision2 by remember { mutableStateOf(settings.aiVisionModel2) }
    var vision2Url by remember { mutableStateOf(settings.aiVisionApiUrl2) }
    var vision2Key by remember { mutableStateOf(settings.aiVisionApiKey2) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    fun saveVision() {
        onToggle(JSONObject()
            .put("aiVisionModel", vision.trim()).put("aiVisionApiUrl", visionUrl.trim()).put("aiVisionApiKey", visionKey.trim())
            .put("aiVisionModel2", vision2.trim()).put("aiVisionApiUrl2", vision2Url.trim()).put("aiVisionApiKey2", vision2Key.trim()))
        Toast.makeText(ctx, "Image AI saved", Toast.LENGTH_SHORT).show()
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Image AI", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { saveVision() }) { Text("Save") }
            }
            Text("Images now work without any API key (free). Add your Gemini/OpenRouter key below to use it first; otherwise the free one is used automatically.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SettingRow(Icons.Filled.Image, Color(0xFF4DD07A), "Free image AI (no key)", "Understands images without an API key — default ON", settings.aiImageFree) { onToggle(JSONObject().put("aiImageFree", it)) }

            // Self-test: tap to see if the AI can actually SEE an image (and the exact error if not)
            Button(onClick = {
                testing = true
                scope.launch { val r = GatewayClient.visionTest(); testResult = fmtVisionTest(r); testing = false }
            }, enabled = !testing, modifier = Modifier.fillMaxWidth()) {
                Text(if (testing) "Testing… (thoda ruk)" else "Test Image AI")
            }
            Text("Tap Save first, then Test. This shows whether the AI can see the image (and what the error is).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("PROVIDER 1", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            LinkRow(Icons.Filled.Language, Color(0xFF4DD07A), "Get Gemini key", "aistudio.google.com → tap to create key") { openUrl(ctx, "https://aistudio.google.com/apikey") }
            FilledTonalButton(onClick = {
                visionUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
                vision = "gemini-flash-latest, gemini-3-flash, gemini-2.5-flash, gemini-2.0-flash"
                Toast.makeText(ctx, "Gemini filled — paste your key and Save", Toast.LENGTH_LONG).show()
            }) { Text("Use Gemini (free)") }
            OutlinedTextField(vision, { vision = it }, label = { Text("Vision model(s) — comma se multiple") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(visionUrl, { visionUrl = it }, label = { Text("Vision API URL") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(visionKey, { visionKey = it }, label = { Text("Vision API key") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "toggle key") } },
                modifier = Modifier.fillMaxWidth())

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Text("PROVIDER 2 — fallback (jab pehla down ho)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            LinkRow(Icons.Filled.Language, Color(0xFFFFB26B), "Get OpenRouter key", "openrouter.ai → tap to create key") { openUrl(ctx, "https://openrouter.ai/keys") }
            FilledTonalButton(onClick = {
                vision2Url = "https://openrouter.ai/api/v1/chat/completions"
                vision2 = "google/gemini-2.0-flash-exp:free, qwen/qwen2.5-vl-72b-instruct:free, meta-llama/llama-3.2-11b-vision-instruct:free"
                Toast.makeText(ctx, "OpenRouter filled — paste your key and Save", Toast.LENGTH_LONG).show()
            }) { Text("Use OpenRouter (free)") }
            OutlinedTextField(vision2, { vision2 = it }, label = { Text("Fallback model(s) — comma se multiple") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vision2Url, { vision2Url = it }, label = { Text("Fallback API URL") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vision2Key, { vision2Key = it }, label = { Text("Fallback API key") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            SettingRow(Icons.Filled.TextFields, Color(0xFF80DEEA), "Read image text on phone", "Offline & unlimited. Screenshot/text image ka reply tab bhi jab vision API down ho.", settings.aiImageOcr) { onToggle(JSONObject().put("aiImageOcr", it)) }

            FilledTonalButton(onClick = { saveVision() }, modifier = Modifier.align(Alignment.End)) { Text("Save") }
        }
    }

    if (testResult != null) {
        AlertDialog(
            onDismissRequest = { testResult = null },
            containerColor = dialogBg(),
            title = { Text("Image AI test") },
            text = { Text(testResult ?: "", style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun WallpaperSettings(ctx: Context, version: Int, onPickWallpaper: () -> Unit, onRemoveWallpaper: () -> Unit) {
    // decode keyed on `version` so the preview refreshes instantly after set/remove (no leave & reopen)
    val wp = remember(version) { runCatching { val f = File(ctx.filesDir, "wallpaper.jpg"); if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null }.getOrNull() }
    Text("PREVIEW", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)).background(if (wp == null) Color(0xFF0E1621) else Color.Black)) {
        if (wp != null) Image(wp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF2C2C2E)) {
                Text("Hey! 👋", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(shape = RoundedCornerShape(14.dp), color = IOS_BLUE) {
                    Text("This is your wallpaper", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
    Button(onClick = onPickWallpaper, modifier = Modifier.fillMaxWidth()) { Text("Set wallpaper from gallery") }
    OutlinedButton(onClick = onRemoveWallpaper, modifier = Modifier.fillMaxWidth()) { Text("Remove wallpaper") }
    Text("Wallpaper shows behind all chats and applies immediately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChatSettings(onOpenWallpaper: () -> Unit) {
    val dark = isSystemInDarkTheme()

    SettingsGroup("Wallpaper") {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpenWallpaper() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Filled.Wallpaper, CAT_WALLPAPER)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Chat Wallpaper", style = MaterialTheme.typography.bodyLarge)
                Text("Set or remove chat background", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SettingsGroup("Bubble style") {
        // live preview
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val inSpec = bubbleSpec(false, dark)
            val outSpec = bubbleSpec(true, dark)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Surface(color = inSpec.first, shape = inSpec.third) { Text("Hey! 👋", color = inSpec.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(color = outSpec.first, shape = outSpec.third) { Text("Looks great 🎉", color = outSpec.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) }
            }
        }
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatStyle.bubbleStyles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (k, lbl) ->
                        FilterChip(selected = ChatStyle.bubbleStyle.value == k, onClick = { ChatStyle.setBubble(k) }, label = { Text(lbl) }, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    SettingsGroup("Bubble color") {
        Text("Sent (right side)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, start = 2.dp))
        BubbleColorRow(ChatStyle.sentColor.value) { ChatStyle.setSentColor(it) }
        Text("Received (left side)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
        BubbleColorRow(ChatStyle.recvColor.value) { ChatStyle.setRecvColor(it) }
        Spacer(Modifier.height(4.dp))
    }

    SettingsGroup("Chat header blur") {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatStyle.blurSteps.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { dp ->
                        FilterChip(selected = ChatStyle.headerBlurDp.value == dp, onClick = { ChatStyle.setHeaderBlur(dp) },
                            label = { Text(if (dp == 0) "Off" else dp.toString() + "dp") }, modifier = Modifier.weight(1f))
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text("Higher = more see-through frosted header over your wallpaper. Applies instantly.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// horizontal swatch picker; first swatch (0) = follow the bubble-style default
@Composable
private fun BubbleColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChatStyle.bubblePalette.forEach { argb ->
            val isSel = selected == argb
            val ring = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(if (argb == 0) MaterialTheme.colorScheme.surfaceVariant else Color(argb))
                    .border(if (isSel) 3.dp else 1.dp, ring, CircleShape)
                    .clickable { onPick(argb) },
                contentAlignment = Alignment.Center
            ) {
                if (argb == 0) Text("A", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (isSel) Icon(Icons.Filled.Done, "selected", tint = ChatStyle.textOn(argb), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AppearanceSettings() {
    val mode = ThemeStore.mode.value
    val accent = ThemeStore.accent.value
    SettingsGroup("Theme") {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED").forEach { (k, lbl) ->
                FilterChip(selected = mode == k, onClick = { ThemeStore.setMode(k) }, label = { Text(lbl) })
            }
        }
    }
    SettingsGroup("Accent color") {
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ThemeStore.accents.forEach { (key, color) ->
                Box(Modifier.size(38.dp).clip(CircleShape).background(color).clickable { ThemeStore.setAccent(key) },
                    contentAlignment = Alignment.Center) {
                    if (accent == key) Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
    }
    Text("Theme and accent apply instantly across the whole app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// Light string hiding for developer identity + links: decoded at runtime, so a decompile/`strings`
// pass on the APK does not reveal them in plaintext. Not encryption — just keeps casual re-mods away.
// ===== Support Development: UPI (India) + crypto (other countries). All addresses/links are AES-encrypted
// (see AyxHere) and QR codes are generated at runtime, so nothing is plainly visible in a decompile/re-mod.
// and QR codes are generated at runtime, so nothing is plainly visible in a decompile/re-mod. =====
@Composable
private fun SupportSettings(ctx: Context) {
    val clip = LocalClipboardManager.current
    fun copy(v: String) { clip.setText(AnnotatedString(v)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }

    val upiUrl = AyxHere.upiUrl
    val upiId  = AyxHere.upiId
    val bep20  = AyxHere.bep20
    val trc20  = AyxHere.trc20
    val erc20  = AyxHere.erc20
    val binUrl = AyxHere.binanceUrl

    Text("Your support keeps AyX free and updated ❤️", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))

    Text("SUPPORT · INDIA (UPI)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    PayCard(title = "UPI", subtitle = upiId, qrContent = upiUrl, address = upiId,
        actionLabel = "Pay via UPI", onAction = { openUrl(ctx, upiUrl) }, onCopy = { copy(upiId) })

    Text("SUPPORT · OTHER COUNTRIES (USDT)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    PayCard("USDT · BEP20", "BNB Smart Chain (BSC)", bep20, bep20, onCopy = { copy(bep20) })
    PayCard("USDT · TRC20", "Tron network", trc20, trc20, onCopy = { copy(trc20) })
    PayCard("USDT · ERC20", "Ethereum network", erc20, erc20, onCopy = { copy(erc20) })

    Surface(onClick = { openUrl(ctx, binUrl) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconChip(Icons.Filled.Bolt, WARN_AMBER)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Binance — Pay directly", style = MaterialTheme.typography.bodyLarge)
                Text("Opens the Binance app to pay instantly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(AyxHere.builtBy,
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

@Composable
private fun PayCard(title: String, subtitle: String, qrContent: String, address: String,
                    actionLabel: String? = null, onAction: (() -> Unit)? = null, onCopy: () -> Unit) {
    val qr = remember(qrContent) { QrGen.make(qrContent) }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp)) {
                if (qr != null) Image(qr, "qr", Modifier.size(200.dp), filterQuality = FilterQuality.None)
                else Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(address, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 3, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "copy", tint = MaterialTheme.colorScheme.primary) }
                }
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AYX_GREEN)) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, sub: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconChip(icon, tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Like LinkRow, but the leading icon is any composable (used for the real brand glyphs below).
@Composable
private fun LinkRowSlot(leading: @Composable () -> Unit, title: String, sub: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// The real Instagram mark: rounded-square + lens + corner dot, in the brand gradient.
@Composable
private fun InstagramGlyph(size: androidx.compose.ui.unit.Dp) {
    val grad = Brush.linearGradient(listOf(Color(0xFFFEDA77), Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF), Color(0xFF515BD4)))
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.095f
        val pad = sw / 2f + s * 0.05f
        val rr = s * 0.30f
        drawRoundRect(brush = grad, topLeft = Offset(pad, pad), size = Size(s - 2f * pad, s - 2f * pad), cornerRadius = CornerRadius(rr, rr), style = Stroke(sw))
        drawCircle(brush = grad, radius = s * 0.19f, center = Offset(s / 2f, s / 2f), style = Stroke(sw))
        drawCircle(brush = grad, radius = s * 0.05f, center = Offset(s * 0.71f, s * 0.29f))
    }
}

// The real Telegram mark: blue disc with a white paper plane.
@Composable
private fun TelegramGlyph(size: androidx.compose.ui.unit.Dp) {
    val tg = Brush.linearGradient(listOf(Color(0xFF2AABEE), Color(0xFF229ED9)))
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawCircle(brush = tg, radius = s / 2f, center = Offset(s / 2f, s / 2f))
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.22f, s * 0.49f)
            lineTo(s * 0.79f, s * 0.27f)
            lineTo(s * 0.645f, s * 0.75f)
            lineTo(s * 0.47f, s * 0.585f)
            close()
        }
        drawPath(body, Color.White)
        val fold = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.47f, s * 0.585f)
            lineTo(s * 0.645f, s * 0.75f)
            lineTo(s * 0.44f, s * 0.67f)
            close()
        }
        drawPath(fold, Color(0xFFC8E6F7))
    }
}

@Composable
private fun AboutSettings(ctx: Context, onLogout: () -> Unit) {
    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var confirm by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    val artId = remember { ctx.resources.getIdentifier("about_art", "drawable", ctx.packageName) }
    val art = remember(artId) { if (artId != 0) runCatching { BitmapFactory.decodeResource(ctx.resources, artId)?.asImageBitmap() }.getOrNull() else null }

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(APP_NAME, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Version " + ver, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Runs locally on your device. No data is collected. AI reply (if enabled) sends message text only to the API you configure.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (art != null) Image(art, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.FillWidth)

    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(AyxHere.aboutHey, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("If you love my project, please give me a ⭐ on my GitHub project.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { openUrl(ctx, AyxHere.githubRepo) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Star, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Star on GitHub")
            }
        }
    }

    Text("CONNECT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    LinkRowSlot({ InstagramGlyph(21.dp) }, AyxHere.igLabel, "Instagram") { openUrl(ctx, AyxHere.igUrl) }
    LinkRowSlot({ TelegramGlyph(21.dp) }, AyxHere.tgLabel, "Telegram") { openUrl(ctx, AyxHere.tgUrl) }

    Text("If I'm unavailable everywhere, kindly contact me here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    LinkRow(Icons.Filled.Language, CAT_WALLPAPER, "Website", "Personal site") { openUrl(ctx, AyxHere.webUrl) }

    Text("LEGAL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    LinkRow(Icons.Filled.Shield, AYX_GREEN, "Privacy Policy", "How your data is handled") { showPrivacy = true }

    Text("ACCOUNT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
    OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ERR_RED),
        border = androidx.compose.foundation.BorderStroke(1.dp, ERR_RED.copy(alpha = 0.6f))) {
        Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Unlink / reset")
    }
    if (showPrivacy) {
        AlertDialog(onDismissRequest = { showPrivacy = false },
            containerColor = dialogBg(),
            shape = RoundedCornerShape(22.dp),
            icon = { Icon(Icons.Filled.Shield, null, tint = AYX_GREEN) },
            title = { Text("Privacy Policy") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Close", color = AYX_GREEN) } })
    }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false },
            containerColor = dialogBg(),
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = ERR_RED) },
            title = { Text("Unlink this device?") },
            text = { Text("This logs out the WhatsApp session and clears local data (chats, statuses, media cache). You'll need to link again with QR or pairing code.") },
            confirmButton = { TextButton(onClick = { confirm = false; onLogout() }) { Text("Unlink", color = ERR_RED) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LinkScreen(qr: ImageBitmap?, pairingCode: String?, onPair: (String) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(44.dp))
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(AYX_GREEN, IOS_BLUE))), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.QrCode2, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(APP_NAME, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Link your WhatsApp to get started", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        // QR card (scan this from WhatsApp on your phone)
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    if (qr != null) Image(qr, "QR", Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit)
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AYX_GREEN, strokeWidth = 3.dp)
                        Spacer(Modifier.height(12.dp)); Text("Generating QR…", color = Color(0xFF555555), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinkStep("1", "Open WhatsApp on your phone")
                LinkStep("2", "Tap Settings → Linked devices")
                LinkStep("3", "Tap Link a device, then scan this QR")
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onReset) { Text("QR not working? Get a fresh one") }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun LinkStep(n: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(AYX_GREEN.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Text(n, style = MaterialTheme.typography.labelMedium, color = AYX_GREEN, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}


@Composable
private fun NewChatScreen(contacts: List<DeviceContact>, loading: Boolean, dpCache: MutableMap<String, ImageBitmap?>, onPickNumber: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(contacts, q) {
        if (q.isBlank()) contacts else contacts.filter { it.name.contains(q, true) || it.number.contains(q) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search name or number") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = IOS_BLUE) },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("Finding your WhatsApp contacts…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            contacts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No WhatsApp contacts found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Text("${filtered.size} contacts on WhatsApp", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(filtered) { _, c ->
                        Row(Modifier.fillMaxWidth().clickable { onPickNumber(c.number) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(c.number + "@s.whatsapp.net", c.name, dpCache, 46.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("+" + c.number, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatsWithStatus(messages: List<GatewayClient.Msg>, statuses: List<GatewayClient.StatusItem>, dpCache: MutableMap<String, ImageBitmap?>, query: String, page: Int, topInset: androidx.compose.ui.unit.Dp, onPageChange: (Int) -> Unit, onLoadStatuses: () -> Unit, onOpenStatus: (GatewayClient.StatusItem) -> Unit, onToggleStatusReveal: () -> Unit, onToggleChatReveal: () -> Unit, onDelete: (String) -> Unit, onOpen: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = page) { 2 }
    // content scrolls BEHIND the glass header (topInset) and the floating bottom nav (bottom)
    val contentPad = androidx.compose.foundation.layout.PaddingValues(top = topInset + 6.dp, bottom = 118.dp)
    // bottom-nav tap (page) ↔ swipe (pager) stay in sync
    LaunchedEffect(page) { if (pager.currentPage != page) pager.animateScrollToPage(page) }
    LaunchedEffect(pager.currentPage) {
        onPageChange(pager.currentPage)
        while (pager.currentPage == 1) { onLoadStatuses(); delay(5000) }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { pg ->
        if (pg == 0) ChatList(messages, dpCache, query, contentPad, onToggleChatReveal, onDelete, onOpen)
        else StatusScreen(statuses, contentPad, onOpenStatus, dpCache, onToggleStatusReveal)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusScreen(statuses: List<GatewayClient.StatusItem>, contentPad: androidx.compose.foundation.layout.PaddingValues, onOpen: (GatewayClient.StatusItem) -> Unit, dpCache: MutableMap<String, ImageBitmap?>, onToggleReveal: () -> Unit) {
    val version = StatusData.seenVersion.value            // recompose + reorder the moment a status is viewed
    val mine = statuses.filter { it.mine }
    val others = statuses.filter { !it.mine }
    // group by sender, drop hidden (unless revealed), then bucket: unread on top, viewed below, muted at the bottom
    val groups = others.groupBy { it.sender }.entries.filter { StatusFlags.reveal || !StatusFlags.isHidden(it.key) }
    val active = groups.filter { !StatusFlags.isMuted(it.key) }
    val unread = active.filter { !StatusData.groupSeen(it.value) }.sortedByDescending { e -> e.value.maxOf { it.ts } }
    val read = active.filter { StatusData.groupSeen(it.value) }.sortedByDescending { e -> e.value.maxOf { it.ts } }
    val muted = groups.filter { StatusFlags.isMuted(it.key) }.sortedByDescending { e -> e.value.maxOf { it.ts } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPad) {
        item {
            Text(if (StatusFlags.reveal) "My Status · showing hidden" else "My Status",
                style = MaterialTheme.typography.labelMedium, color = IOS_BLUE,
                modifier = Modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = {}, onLongClick = onToggleReveal).padding(start = 14.dp, top = 12.dp, bottom = 2.dp))
            if (mine.isEmpty()) {
                Text("Tap the camera button to add a status update", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 10.dp))
            } else {
                val st = mine.first()
                Row(Modifier.fillMaxWidth().clickable { onOpen(st) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(st.sender, "Me", dpCache, 50.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("My Status", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text(mine.size.toString() + " update(s) · " + fmt(st.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
        }
        if (unread.isNotEmpty()) item { StatusSectionHeader("Recent updates") }
        itemsIndexed(unread.toList(), key = { _, e -> "u:" + e.key }) { _, entry -> StatusRow(entry.key, entry.value, seen = false, dpCache = dpCache, onOpen = onOpen) }
        if (read.isNotEmpty()) item { StatusSectionHeader("Viewed updates") }
        itemsIndexed(read.toList(), key = { _, e -> "v:" + e.key }) { _, entry -> StatusRow(entry.key, entry.value, seen = true, dpCache = dpCache, onOpen = onOpen) }
        if (muted.isNotEmpty()) item { StatusSectionHeader("Muted updates") }
        itemsIndexed(muted.toList(), key = { _, e -> "m:" + e.key }) { _, entry -> StatusRow(entry.key, entry.value, seen = StatusData.groupSeen(entry.value), dpCache = dpCache, onOpen = onOpen) }
        if (unread.isEmpty() && read.isEmpty() && muted.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text("No recent updates", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
private fun StatusSectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp))
}

// status list row with a WhatsApp-style seen/unseen ring + long-press mute/hide/lock (mirrors chat long-press)
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusRow(sender: String, list: List<GatewayClient.StatusItem>, seen: Boolean, dpCache: MutableMap<String, ImageBitmap?>, onOpen: (GatewayClient.StatusItem) -> Unit) {
    val latest = list.maxByOrNull { it.ts } ?: list.first()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(latest) }, onLongClick = { menu = true }).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val ring = if (seen) MaterialTheme.colorScheme.outline.copy(alpha = 0.45f) else AYX_GREEN
            Box(Modifier.size(54.dp).border(2.dp, ring, CircleShape).padding(3.dp), contentAlignment = Alignment.Center) {
                Avatar(sender, latest.name, dpCache, 46.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ContactStore.nameFor(sender) ?: latest.name.ifBlank { "Status" }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(list.size.toString() + " update(s) · " + fmt(latest.ts), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (StatusFlags.isMuted(sender)) Icon(Icons.Filled.VisibilityOff, "muted", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            if (StatusFlags.isLocked(sender)) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Lock, "locked", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (StatusFlags.isMuted(sender)) "Unmute status" else "Mute status") }, onClick = { menu = false; StatusFlags.toggleMuted(sender) })
            DropdownMenuItem(text = { Text(if (StatusFlags.isHidden(sender)) "Unhide status" else "Hide status") }, onClick = { menu = false; StatusFlags.toggleHidden(sender) })
            DropdownMenuItem(text = { Text(if (StatusFlags.isLocked(sender)) "Unlock status" else "Lock status") }, onClick = { menu = false; StatusFlags.toggleLocked(sender) })
        }
    }
    HorizontalDivider()
}


@Composable
private fun LinkText(text: String, color: Color) {
    val annotated = remember(text) {
        buildAnnotatedString {
            val regex = Regex("(https?://\\S+|www\\.\\S+)")
            var last = 0
            for (mt in regex.findAll(text)) {
                if (mt.range.first > last) append(text.substring(last, mt.range.first))
                val raw = mt.value
                val url = if (raw.startsWith("http")) raw else "https://" + raw
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Color(0xFF4EA1FF), textDecoration = TextDecoration.Underline)))) {
                    append(raw)
                }
                last = mt.range.last + 1
            }
            if (last < text.length) append(text.substring(last))
        }
    }
    Text(annotated, color = color)
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusEditor(uri: Uri, type: String, contacts: List<DeviceContact>, onUpload: (String, String, List<String>, GatewayClient.Song?, Long, Long, List<Pair<Long, String>>, Float, Float, Int, Boolean) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var caption by remember { mutableStateOf("") }
    var audience by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pickAudience by remember { mutableStateOf(false) }
    var song by remember { mutableStateOf<GatewayClient.Song?>(null) }
    var trimStart by remember { mutableStateOf(0L) }
    var trimEnd by remember { mutableStateOf(15000L) }
    var songToTrim by remember { mutableStateOf<GatewayClient.Song?>(null) }
    var songLyrics by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var lyricY by remember { mutableStateOf(0.18f) }      // Instagram-style text sits near the top
    var lyricScale by remember { mutableStateOf(1f) }
    var animStyle by remember { mutableStateOf(1) }       // 0=None,1=Reveal(Instagram),2=Pop,3=Slide,4=Type,5=Fade
    var romanize by remember { mutableStateOf(false) }    // Hindi/Punjabi lyrics → English letters
    var previewPos by remember { mutableStateOf(0L) }
    // what the preview + video actually show: romanized when the toggle is on (shown live in the preview)
    var displayLyrics by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var lyricBusy by remember { mutableStateOf(false) }
    LaunchedEffect(songLyrics, romanize) {
        if (!(romanize && songLyrics.isNotEmpty())) { displayLyrics = songLyrics; lyricBusy = false; return@LaunchedEffect }
        lyricBusy = true
        val src = songLyrics.map { it.second }
        val native = Regex("[\\u0900-\\u0DFF]")   // Devanagari / Bengali / Gurmukhi / Tamil … native scripts
        var rom = runCatching { GatewayClient.translateLines(src, true) }.getOrNull()
        // retry once if it failed or came back unchanged while native-script text is still present
        if ((rom == null || rom == src) && src.any { native.containsMatchIn(it) }) {
            rom = runCatching { GatewayClient.translateLines(src, true) }.getOrNull()
        }
        displayLyrics = if (rom != null && rom.size == songLyrics.size)
            songLyrics.mapIndexed { i, pr -> pr.first to rom!![i].ifBlank { pr.second } }
        else songLyrics
        lyricBusy = false
    }
    var musicOpen by remember { mutableStateOf(false) }
    val editorBlur by animateDpAsState(if (musicOpen) 18.dp else 0.dp, label = "editorblur")   // blur editor behind the music sheet
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(playing) { while (playing) { previewPos = runCatching { player.currentPosition.toLong() }.getOrDefault(previewPos); if (trimEnd > 0 && previewPos >= trimEnd) { runCatching { player.seekTo(trimStart.toInt()) }; previewPos = trimStart }; delay(90) } }
    // when music isn't playing, gently scrub through the lyric window so the (romanized) lines are visible live in the preview
    LaunchedEffect(displayLyrics, playing, trimStart, trimEnd) {
        if (playing || displayLyrics.isEmpty()) return@LaunchedEffect
        val from = trimStart
        val to = if (trimEnd > trimStart) trimEnd else (from + 15000L)
        previewPos = from
        while (!playing) { delay(140); previewPos += 240; if (previewPos > to) previewPos = from }
    }
    val audLabel = when (audience) { "except" -> "Except " + selected.size; "only" -> "Only " + selected.size; else -> "My contacts" }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().blur(editorBlur).statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "close", tint = Color.White) }
                    Text("New status", color = Color.White, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { pickAudience = true }) { Text(audLabel, color = IOS_BLUE) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                    if (type == "image") {
                        val bmp = remember(uri) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Text("Preview unavailable", color = Color.White)
                    } else Text("Video selected", color = Color.White)
                    if (displayLyrics.isNotEmpty()) {
                        val active = displayLyrics.lastOrNull { it.first <= previewPos + 200L }
                        val line = active?.second ?: displayLyrics.firstOrNull { it.first in trimStart..trimEnd }?.second ?: ""
                        Box(Modifier.fillMaxSize().pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                lyricY = (lyricY + pan.y / size.height.toFloat()).coerceIn(0.06f, 0.9f)
                                lyricScale = (lyricScale * zoom).coerceIn(0.5f, 2.5f)
                            }
                        }) {
                            if (line.isNotBlank()) {
                                if (animStyle == 1) {
                                    // Instagram-style: words build left→right, newest word grey, UPPERCASE bold
                                    val lineStart = active?.first ?: 0L
                                    val nextStart = displayLyrics.firstOrNull { it.first > lineStart }?.first ?: (lineStart + 3000L)
                                    val dur = (nextStart - lineStart).coerceIn(700L, 6000L)
                                    val ph = ((previewPos - lineStart).toFloat() / (dur * 0.82f)).coerceIn(0f, 1f)
                                    val words = line.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                                    val shown = kotlin.math.ceil(ph * words.size).toInt().coerceIn(1, words.size)
                                    FlowRow(Modifier.align(BiasAlignment(-1f, lyricY * 2f - 1f)).fillMaxWidth(0.92f).padding(start = 16.dp, end = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        for (i in 0 until shown) {
                                            Text(words[i], color = if (i == shown - 1) Color(0xFFAAAAAA) else Color.White,
                                                fontSize = (27f * lyricScale).sp, fontWeight = FontWeight.Black, lineHeight = (31f * lyricScale).sp)
                                        }
                                    }
                                } else {
                                    Text(line, color = Color.White, fontSize = (22f * lyricScale).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                        modifier = Modifier.align(BiasAlignment(0f, lyricY * 2f - 1f)).padding(horizontal = 16.dp)
                                            .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
                                }
                            }
                            Text("drag • pinch to resize lyrics", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sg = song
                    if (sg == null) {
                        TextButton(onClick = { musicOpen = true }) { Text("♪  Add music", color = IOS_BLUE) }
                    } else {
                        TextButton(onClick = {
                            if (playing) { runCatching { player.pause() }; playing = false }
                            else runCatching {
                                player.reset(); player.setDataSource(sg.url)
                                player.setOnPreparedListener { it.seekTo(trimStart.toInt()); it.start(); playing = true }
                                player.setOnCompletionListener { playing = false }
                                player.setOnErrorListener { _, _, _ -> playing = false; true }
                                player.prepareAsync()
                            }
                        }) { Text(if (playing) "⏸ Pause" else "▶ Play", color = IOS_BLUE) }
                        Text("♪ " + sg.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (songLyrics.isNotEmpty()) Text("  © lyrics", color = IOS_BLUE, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { runCatching { player.reset() }; playing = false; song = null }) { Icon(Icons.Filled.Close, "remove", tint = Color.White) }
                    }
                }
                if (songLyrics.isNotEmpty()) {
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("LYRICS STYLE", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf("None", "Reveal", "Pop", "Slide", "Type", "Fade").forEachIndexed { i, lbl ->
                                    FilterChip(selected = animStyle == i, onClick = { animStyle = i }, label = { Text(lbl) })
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Hindi / Punjabi → English letters", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (lyricBusy) "Romanizing lyrics…" else if (romanize) "Romanized — showing live in preview" else "Shows live in the preview",
                                        color = if (lyricBusy) IOS_BLUE else Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                                }
                                if (lyricBusy) CircularProgressIndicator(Modifier.size(18.dp), color = IOS_BLUE, strokeWidth = 2.dp)
                                else Switch(checked = romanize, onCheckedChange = { romanize = it })
                            }
                        }
                    }
                }
                // Caption — rounded frosted (light-blur) box, sitting ABOVE the send box
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(24.dp))) {
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.07f)))
                    // very soft sheen → subtle frosted-glass feel
                    Box(Modifier.matchParentSize().blur(6.dp).background(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f)))))
                    OutlinedTextField(caption, { caption = it }, placeholder = { Text("Add a caption…", color = Color.White.copy(alpha = 0.6f)) }, maxLines = 3, shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White.copy(alpha = 0.22f), unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White),
                        modifier = Modifier.fillMaxWidth())
                }
                // Send box — its own row, right-aligned, below the caption
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { onUpload(caption.trim(), audience, selected.toList(), song, trimStart, trimEnd, songLyrics, lyricY, lyricScale, animStyle, romanize) },
                        modifier = Modifier.size(54.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = AYX_GREEN)) { Icon(Icons.AutoMirrored.Filled.Send, "upload", tint = Color.White) }
                }
            }
        }
    }
    if (pickAudience) AudienceSheet(contacts, audience, selected) { a, sset -> audience = a; selected = sset; pickAudience = false }
    if (musicOpen) MusicSearchSheet(onSelect = { songToTrim = it; musicOpen = false }, onClose = { musicOpen = false })
    songToTrim?.let { sng ->
        AudioTrimmer(sng, onDone = { st, en, ly -> song = sng; trimStart = st; trimEnd = en; songLyrics = ly; songToTrim = null }, onCancel = { songToTrim = null })
    }
}

@Composable
private fun AudienceSheet(contacts: List<DeviceContact>, audienceIn: String, selectedIn: Set<String>, onDone: (String, Set<String>) -> Unit) {
    var aud by remember { mutableStateOf(audienceIn) }
    var sel by remember { mutableStateOf(selectedIn) }
    var q by remember { mutableStateOf("") }
    val filtered = remember(contacts, q) { if (q.isBlank()) contacts else contacts.filter { it.name.contains(q, true) || it.number.contains(q) } }
    Dialog(onDismissRequest = { onDone(aud, sel) }) {
        Surface(shape = RoundedCornerShape(16.dp), color = dialogBg()) {
            Column(Modifier.padding(14.dp).heightIn(max = 560.dp)) {
                Text("Status privacy", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                AudRadio("My contacts", aud == "all") { aud = "all" }
                AudRadio("My contacts except…", aud == "except") { aud = "except" }
                AudRadio("Only share with…", aud == "only") { aud = "only" }
                if (aud != "all") {
                    OutlinedTextField(q, { q = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(filtered) { _, c ->
                            val jid = c.number + "@s.whatsapp.net"
                            Row(Modifier.fillMaxWidth().clickable { sel = if (jid in sel) sel - jid else sel + jid }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(jid in sel, { checked -> sel = if (checked) sel + jid else sel - jid })
                                Spacer(Modifier.width(6.dp))
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Button(onClick = { onDone(aud, sel) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun AudRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}


@Composable
private fun StatusViewer(statuses: List<GatewayClient.StatusItem>, startSender: String, dpCache: MutableMap<String, ImageBitmap?>, onDownload: (GatewayClient.StatusItem) -> Unit, onDeleteStatus: (String) -> Unit, onReply: (String, String) -> Unit, onSeen: (GatewayClient.StatusItem) -> Unit, onClose: () -> Unit) {
    val groups = remember(statuses) {
        statuses.groupBy { it.sender }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<GatewayClient.StatusItem>>> { e -> e.value.any { it.mine } }.thenByDescending { e -> e.value.maxOf { it.ts } })
            .map { it.key to it.value.sortedBy { s -> s.ts } }
    }
    if (groups.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val target = startSender.substringBefore("@").substringBefore(":").filter { it.isDigit() }
    var si by remember { mutableStateOf(groups.indexOfFirst { g -> g.first.substringBefore("@").substringBefore(":").filter { it.isDigit() } == target }.coerceAtLeast(0)) }
    var ii by remember { mutableStateOf(0) }
    val group = groups.getOrNull(si) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val items = group.second
    val st = items.getOrNull(ii) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    fun goNext() { if (ii < items.size - 1) ii++ else if (si < groups.size - 1) { si++; ii = 0 } else onClose() }
    fun goPrev() { if (ii > 0) ii-- else if (si > 0) { si--; ii = 0 } }
    // send a "seen" receipt for each status actually viewed (gated by the Hide-status-view setting upstream)
    LaunchedEffect(st.id, si, ii) { if (!st.mine && !st.id.isNullOrBlank()) onSeen(st) }
    var replyText by remember { mutableStateOf("") }
    var progress by remember(si, ii) { mutableStateOf(0f) }
    var videoReady by remember(st.mediaName, si, ii) { mutableStateOf(false) }   // video prepared → drive bar from real position
    var videoView by remember(st.mediaName, si, ii) { mutableStateOf<VideoView?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var showViewers by remember { mutableStateOf(false) }
    val myMine = items.first().mine
    // viewers sheet open → blur the status behind it (like the rest of the UI) and freeze playback
    val statusBlur by animateDpAsState(if (showViewers && st.mediaType != "video") 18.dp else 0.dp, label = "statusblur")

    var bmp by remember(st.mediaName, si, ii) { mutableStateOf<ImageBitmap?>(null) }
    var imgLoading by remember(st.mediaName, si, ii) { mutableStateOf(st.mediaName != null && st.mediaType != "video") }
    var reloadKey by remember(st.mediaName, si, ii) { mutableStateOf(0) }
    var fullLoaded by remember(st.mediaName, si, ii) { mutableStateOf(false) }
    LaunchedEffect(st.mediaName, si, ii, reloadKey) {
        val name = st.mediaName
        if (name != null && st.mediaType != "video") {
            // show thumbnail instantly so the viewer is never blank/"…"
            if (bmp == null) bmp = decodeThumb(st.thumb)
            imgLoading = true
            // full media may still be downloading on the gateway (it re-downloads from the raw msg) — retry a few times
            var full: ImageBitmap? = null
            var tries = 0
            while (full == null && tries < 5) {
                val bytes = GatewayClient.mediaBytes(name)
                full = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
                if (full == null) delay(700)
                tries++
            }
            if (full != null) { bmp = full; fullLoaded = true }
            imgLoading = false
        } else { bmp = null; imgLoading = false }
    }
    LaunchedEffect(si, ii, replyText.isBlank(), imgLoading) {
        if (replyText.isNotBlank()) return@LaunchedEffect
        if (st.mediaType == "video" || st.mediaType == "audio") return@LaunchedEffect   // voice/video: let it play, don't auto-advance
        if (imgLoading && bmp == null) return@LaunchedEffect   // wait for media before counting down
        progress = 0f
        val dur = 5000L; val step = 40L; var elapsed = 0L
        while (elapsed < dur) {
            delay(step)
            if (showViewers) continue   // paused while the viewers sheet is open
            elapsed += step; progress = (elapsed.toFloat() / dur).coerceIn(0f, 1f)
            if (replyText.isNotBlank()) return@LaunchedEffect
        }
        goNext()
    }
    // video: drive the top bar from REAL playback position so it never looks stuck
    LaunchedEffect(videoView, videoReady, si, ii) {
        val vv = videoView ?: return@LaunchedEffect
        while (true) {
            val d = runCatching { vv.duration }.getOrDefault(0)
            if (d > 0) progress = (runCatching { vv.currentPosition }.getOrDefault(0).toFloat() / d).coerceIn(0f, 1f)
            delay(80)
        }
    }

    // video: pause while the viewers sheet is open, resume when it closes
    LaunchedEffect(showViewers, videoView, videoReady) {
        val vv = videoView ?: return@LaunchedEffect
        if (showViewers) runCatching { vv.pause() } else if (videoReady) runCatching { vv.start() }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().blur(statusBlur).pointerInput(myMine) {
                if (myMine) { var dyAcc = 0f; detectVerticalDragGestures(onDragEnd = { if (dyAcc < -80f) showViewers = true; dyAcc = 0f }) { _, dy -> dyAcc += dy } }
            }) {
                if (st.mediaType == "audio" && st.mediaName != null) {
                    // voice status → play inline like a voice note
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                            Icon(Icons.Filled.GraphicEq, null, tint = Color.White, modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(18.dp))
                            AudioPlayer(GatewayClient.mediaUrl(st.mediaName!!), Color.White)
                        }
                    }
                } else if (st.mediaType == "video" && st.mediaName != null) {
                    key(si, ii, st.mediaName) {
                        AndroidView(factory = { c -> VideoView(c).apply {
                            setVideoURI(Uri.parse(GatewayClient.mediaUrl(st.mediaName!!)))
                            setOnPreparedListener { it.start(); videoReady = true }
                            setOnCompletionListener { goNext() }
                            setOnErrorListener { _, _, _ -> goNext(); true }   // failed video -> skip instead of black screen
                            videoView = this
                        } }, modifier = Modifier.fillMaxSize())
                    }
                } else if (bmp != null) {
                    Image(bmp!!, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (imgLoading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White.copy(alpha = 0.7f))
                    // only a blurry thumbnail loaded (full media was deleted) → tap to re-download, like chat media
                    else if (!fullLoaded && st.mediaName != null) {
                        Box(Modifier.align(Alignment.Center).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                            .clickable { reloadKey++ }.padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Download, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("Tap to load", color = Color.White)
                            }
                        }
                    }
                } else if (imgLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
                } else if (st.mediaName != null && st.mediaType != "video") {
                    // media missing (deleted / not downloaded yet) → tap to download instead of a blank screen
                    Box(Modifier.fillMaxSize().clickable { reloadKey++ }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Download, null, tint = Color.White, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(8.dp)); Text("Tap to download", color = Color.White)
                        }
                    }
                } else {
                    // text status → centred on its chosen background colour (WhatsApp-style)
                    val tbg = st.bg?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color(0xFF128C7E)
                    Box(Modifier.fillMaxSize().background(tbg), contentAlignment = Alignment.Center) {
                        Text(st.text.ifBlank { "…" }, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(28.dp))
                    }
                }
                if (st.text.isNotBlank() && st.mediaType != null) {
                    Text(st.text, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp))
                }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { goPrev() })
                    Box(Modifier.weight(1.6f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { goNext() })
                }
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp)) {
                    // active segment is "loading" (→ material snake) while media is still coming in or a voice note plays
                    val activeLoading = when (st.mediaType) {
                        "audio" -> true                       // voice status → indeterminate snake while it plays
                        "video" -> !videoReady                 // buffering the clip
                        else -> imgLoading && bmp == null      // image still downloading (no thumb yet)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        items.indices.forEach { idx ->
                            val segMod = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                            when {
                                idx < ii -> LinearProgressIndicator(progress = { 1f }, modifier = segMod, color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))
                                idx == ii && activeLoading -> LinearProgressIndicator(modifier = segMod, color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))   // material snake (indeterminate)
                                idx == ii -> LinearProgressIndicator(progress = { progress }, modifier = segMod, color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))
                                else -> LinearProgressIndicator(progress = { 0f }, modifier = segMod, color = Color.White, trackColor = Color.White.copy(alpha = 0.35f))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(group.first, group.first.substringBefore("@"), dpCache, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(if (items.first().mine) "My Status" else (ContactStore.nameFor(group.first) ?: items.first().name.ifBlank { "Status" }), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                        if (myMine && st.id != null) IconButton(onClick = { confirmDelete = st.id }) { Icon(Icons.Filled.Delete, "delete", tint = Color.White) }
                        if (st.mediaName != null) IconButton(onClick = { onDownload(st) }) { Icon(Icons.Filled.Download, "download", tint = Color.White) }
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "close", tint = Color.White) }
                    }
                }
                if (myMine) {
                    Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showViewers = true },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.KeyboardArrowUp, null, tint = Color.White)
                        Text("Viewed by", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (!myMine) {
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(replyText, { replyText = it }, placeholder = { Text("Reply to status…", color = Color.White.copy(alpha = 0.6f)) }, singleLine = true, shape = RoundedCornerShape(26.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White.copy(alpha = 0.4f), unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
                                focusedContainerColor = Color.Black.copy(alpha = 0.4f), unfocusedContainerColor = Color.Black.copy(alpha = 0.4f),
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White),
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = { if (replyText.isNotBlank()) { onReply(group.first, replyText.trim()); replyText = "" } }, enabled = replyText.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
                    }
                }
                confirmDelete?.let { id ->
                    AlertDialog(onDismissRequest = { confirmDelete = null },
                        containerColor = dialogBg(),
                        title = { Text("Delete status?") },
                        text = { Text("This will delete it from your WhatsApp and from this app.") },
                        confirmButton = { TextButton(onClick = { confirmDelete = null; onDeleteStatus(id); onClose() }) { Text("Delete", color = Color(0xFFFF3B30)) } },
                        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
                }
                if (showViewers) StatusViewersSheet(items.filter { it.mine }.mapNotNull { it.id }, dpCache, onClose = { showViewers = false })
            }
        }
    }
}


@Composable
private fun ProfileScreen(myJid: String?, dpCache: MutableMap<String, ImageBitmap?>, onPickPhoto: () -> Unit, onSaveName: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val number = myJid?.substringBefore("@")?.let { if (it.isNotEmpty() && it.all(Char::isDigit)) "+$it" else it } ?: ""
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.size(128.dp).clip(CircleShape).clickable { onPickPhoto() }) {
                if (myJid != null) Avatar(myJid, "Me", dpCache, 128.dp)
                else Box(Modifier.size(128.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Box(Modifier.size(38.dp).clip(CircleShape).background(AYX_GREEN).border(3.dp, MaterialTheme.colorScheme.surface, CircleShape).clickable { onPickPhoto() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PhotoCamera, "change photo", tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (number.isNotBlank()) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)) {
                Text(number, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(26.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("DISPLAY NAME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(name, { name = it }, placeholder = { Text("Your name") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AYX_GREEN, unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                Text("Shows on your WhatsApp profile.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { if (name.isNotBlank()) onSaveName(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save name")
        }
    }
}


@Composable
private fun MusicSearchSheet(onSelect: (GatewayClient.Song) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GatewayClient.Song>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    val lyricsAvail = remember { mutableStateMapOf<String, Boolean>() }
    // a reliable popular artist so the suggested list is never empty on open ("default songs")
    val defaultQ = remember { listOf("arijit singh", "pritam", "atif aslam", "neha kakkar", "honey singh", "shreya ghoshal").random() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    LaunchedEffect(q) {
        val query = q.trim()
        if (query.length >= 2) { loading = true; delay(450); val r = GatewayClient.searchMusic(query); results = r.first; error = r.second; loading = false }
        else { loading = true; val r = GatewayClient.searchMusic(defaultQ); results = r.first; error = r.second; loading = false }   // default/suggested songs
    }
    // check lyrics availability for the visible songs (cached, throttled)
    LaunchedEffect(results) {
        for (sg in results.take(14)) {
            val key = sg.title + "|" + sg.artist
            if (!lyricsAvail.containsKey(key)) {
                lyricsAvail[key] = runCatching { GatewayClient.getLyrics(sg.title, sg.artist).isNotEmpty() }.getOrDefault(false)
                delay(60)
            }
        }
    }
    // Full-screen bottom sheet. Back dismisses (Dialog). Swipe DOWN on the handle dismisses → reveals the editor.
    Dialog(onDismissRequest = { runCatching { player.stop() }; onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), color = dialogBg(),
                tonalElevation = 3.dp, shadowElevation = 16.dp,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
                Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 14.dp)) {
                    // drag handle + title — swipe DOWN here to close
                    Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                        var acc = 0f
                        detectVerticalDragGestures(onDragEnd = { if (acc > 120f) { runCatching { player.stop() }; onClose() }; acc = 0f }) { _, dy -> acc += dy }
                    }) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = 42.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Add music", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("tap ▶ to preview, tap song to use", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { runCatching { player.stop() }; onClose() }) { Icon(Icons.Filled.Close, "close") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(q, { q = it }, placeholder = { Text("Search songs…") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        if (!loading && results.isEmpty() && q.trim().length >= 2) {
                            Text(if (error.isNotBlank()) "No songs (" + error.take(120) + ")" else "No results", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        if (q.trim().length < 2) Text("Suggested", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(results) { _, sg ->
                        Row(Modifier.fillMaxWidth().clickable { runCatching { player.stop() }; onSelect(sg) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (previewUrl == sg.url) { runCatching { player.pause() }; previewUrl = null }
                                else runCatching {
                                    player.reset(); player.setDataSource(sg.url)
                                    player.setOnPreparedListener { it.start() }
                                    player.setOnErrorListener { _, _, _ -> previewUrl = null; true }
                                    player.prepareAsync(); previewUrl = sg.url
                                }
                            }) { Icon(if (previewUrl == sg.url) Icons.Filled.Close else Icons.Filled.PlayArrow, "preview", tint = IOS_BLUE) }
                            Column(Modifier.weight(1f)) {
                                Text(sg.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(sg.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            when (lyricsAvail[sg.title + "|" + sg.artist]) {
                                true -> Text("© lyrics", color = IOS_BLUE, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
                                null -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> {}
                            }
                            Text("Use", color = IOS_BLUE, modifier = Modifier.padding(horizontal = 6.dp))
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun AudioTrimmer(song: GatewayClient.Song, onDone: (Long, Long, List<Pair<Long, String>>) -> Unit, onCancel: () -> Unit) {
    val player = remember { MediaPlayer() }
    var durationMs by remember { mutableStateOf(0L) }
    var ready by remember { mutableStateOf(false) }
    var startMs by remember { mutableStateOf(0L) }
    var endMs by remember { mutableStateOf(15000L) }
    var posMs by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var lyrics by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var lyricsLoaded by remember { mutableStateOf(false) }
    val bars = remember(song.url) { val r = java.util.Random(song.url.hashCode().toLong()); FloatArray(64) { 0.22f + r.nextFloat() * 0.78f } }
    DisposableEffect(Unit) {
        runCatching {
            player.setDataSource(song.url)
            player.setOnPreparedListener { durationMs = it.duration.toLong().coerceAtLeast(1000L); endMs = minOf(15000L, durationMs); ready = true }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, _, _ -> true }
            player.prepareAsync()
        }
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(Unit) { lyrics = GatewayClient.getLyrics(song.title, song.artist); lyricsLoaded = true }
    LaunchedEffect(playing) {
        while (playing) {
            posMs = runCatching { player.currentPosition.toLong() }.getOrDefault(posMs)
            if (posMs >= endMs) { runCatching { player.seekTo(startMs.toInt()) }; posMs = startMs }
            delay(80)
        }
    }
    val curLyric = lyrics.indexOfLast { it.first <= posMs }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "close", tint = Color.White) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(song.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { runCatching { player.stop() }; onDone(startMs, endMs, lyrics) }, enabled = ready) { Text("Done", color = IOS_BLUE, fontWeight = FontWeight.Bold) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (!lyricsLoaded) CircularProgressIndicator(color = Color.White)
                    else if (lyrics.isEmpty()) Text("Lyrics unavailable", color = Color.White.copy(alpha = 0.5f))
                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        for (off in -2..2) {
                            val idx = curLyric + off
                            if (idx in lyrics.indices) Text(lyrics[idx].second,
                                color = if (off == 0) Color.White else Color.White.copy(alpha = 0.3f),
                                fontWeight = if (off == 0) FontWeight.Bold else FontWeight.Normal,
                                style = if (off == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (playing) { runCatching { player.pause() }; playing = false } else runCatching { player.seekTo(startMs.toInt()); player.start(); playing = true } }, enabled = ready) {
                        Icon(if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow, "play", tint = IOS_BLUE)
                    }
                    Text(fmtMs(posMs - startMs) + " / " + fmtMs(endMs - startMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                WaveformTrimmer(bars, durationMs, startMs, endMs, posMs) { ns, ne -> startMs = ns; endMs = ne }
                Spacer(Modifier.height(12.dp))
                if (durationMs > 6000L) {
                    val selLen = (endMs - startMs).coerceIn(1000L, durationMs)
                    val maxLen = minOf(60000L, durationMs)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Length", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        Slider(value = selLen.toFloat().coerceIn(5000f, maxLen.toFloat()), onValueChange = { nl ->
                            val newLen = nl.toLong().coerceIn(5000L, maxLen)
                            endMs = (startMs + newLen).coerceAtMost(durationMs)
                            if (endMs - startMs < newLen) startMs = (endMs - newLen).coerceAtLeast(0L)
                        }, valueRange = 5000f..maxLen.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        colors = SliderDefaults.colors(thumbColor = IOS_BLUE, activeTrackColor = IOS_BLUE))
                        Text(fmtMs(selLen), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(fmtMs(startMs) + "  →  " + fmtMs(endMs) + "   •   swipe the waveform to move", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun WaveformTrimmer(bars: FloatArray, durationMs: Long, startMs: Long, endMs: Long, posMs: Long, onChange: (Long, Long) -> Unit) {
    val selLen = (endMs - startMs).coerceAtLeast(1000L)
    val curStart by rememberUpdatedState(startMs)
    val curLen by rememberUpdatedState(selLen)
    Canvas(Modifier.fillMaxWidth().height(88.dp).pointerInput(durationMs) {
        val w = size.width.toFloat().coerceAtLeast(1f)
        var acc = 0L
        detectHorizontalDragGestures(
            onDragStart = { acc = curStart },
            onHorizontalDrag = { _, dragAmount ->
                if (durationMs > 0) {
                    val deltaMs = (dragAmount / w * durationMs).toLong()
                    acc = (acc + deltaMs).coerceIn(0L, (durationMs - curLen).coerceAtLeast(0L))
                    onChange(acc, acc + curLen)
                }
            }
        )
    }) {
        val n = bars.size
        val barW = size.width / n
        val selStartX = if (durationMs > 0) startMs.toFloat() / durationMs * size.width else 0f
        val selEndX = if (durationMs > 0) endMs.toFloat() / durationMs * size.width else size.width
        for (i in 0 until n) {
            val bx = i * barW
            val h = bars[i] * size.height
            val inSel = bx + barW / 2 in selStartX..selEndX
            drawRect(if (inSel) Color(0xFF4EA1FF) else Color.White.copy(alpha = 0.18f),
                topLeft = androidx.compose.ui.geometry.Offset(bx + 1f, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size((barW - 2f).coerceAtLeast(1f), h))
        }
        // dim outside selection + bright frame
        drawRect(Color.Black.copy(alpha = 0.45f), topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(selStartX.coerceAtLeast(0f), size.height))
        drawRect(Color.Black.copy(alpha = 0.45f), topLeft = androidx.compose.ui.geometry.Offset(selEndX, 0f), size = androidx.compose.ui.geometry.Size((size.width - selEndX).coerceAtLeast(0f), size.height))
        drawRect(Color(0xFF4EA1FF), topLeft = androidx.compose.ui.geometry.Offset(selStartX, 0f),
            size = androidx.compose.ui.geometry.Size((selEndX - selStartX).coerceAtLeast(2f), size.height), style = Stroke(width = 5f))
        if (posMs in startMs..endMs && durationMs > 0) {
            val px = posMs.toFloat() / durationMs * size.width
            drawRect(Color.Yellow, topLeft = androidx.compose.ui.geometry.Offset(px - 1.5f, 0f), size = androidx.compose.ui.geometry.Size(3f, size.height))
        }
    }
}

private fun fmtMs(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0') }
