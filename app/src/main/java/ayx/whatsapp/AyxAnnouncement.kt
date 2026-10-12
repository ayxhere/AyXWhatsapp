package ayx.whatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.util.Linkify
import android.util.Log
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Top-level alias so MainActivity can use Announcement directly. */
typealias Announcement = AnnouncementStore.Announcement

/**
 * AyX Announcement popup dialog system.
 * Replaces the old AyX updates Settings screen.
 *
 * Flow:
 * 1. User posts announcement via website admin panel
 * 2. App fetches from backend on startup (tries local then public API)
 * 3. Dialog pops up on home screen with 5-second read delay on OK button
 * 4. URLs become clickable, update posts show Download button
 * 5. Notification is posted for new announcements
 */
object AnnouncementStore {
    private const val TAG = "AyxAnnouncement"
    private const val PREFS = "ayx_announcements"
    private const val KEY_SEEN = "seen_ids"
    private const val BACKEND_BASE = "http://127.0.0.1:3101"
    private const val BACKEND_PUBLIC = "https://api.imayx.in"

    data class Announcement(
        val id: String,
        val title: String,
        val body: String,
        val isUpdate: Boolean,
        val downloadUrl: String?,
        val forceUpdate: Boolean,
        val publishedAt: String?
    )

    fun seenIds(ctx: Context): Set<String> {
        return try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        } catch (e: Exception) { emptySet() }
    }

    fun markSeen(ctx: Context, id: String) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = seenIds(ctx).toMutableSet()
            seen.add(id)
            val trimmed = if (seen.size > 50) seen.sorted().takeLast(50).toSet() else seen
            prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
        } catch (e: Exception) {
            Log.w(TAG, "markSeen failed: ${e.message}")
        }
    }

    fun isSeen(ctx: Context, id: String): Boolean = seenIds(ctx).contains(id)

    /** Parse raw JSON into an Announcement, detecting update posts. */
    fun parse(id: String, title: String, body: String, publishedAt: String?): Announcement {
        val lower = "$title $body".lowercase()
        val isUpdate = lower.contains("update") || lower.contains("version") ||
                Regex("""v?\d+\.\d+""").containsMatchIn(lower)
        // First URL in body becomes download URL for update posts
        val urlRegex = Regex("""https?://[^\s<>"']+""")
        val downloadUrl = if (isUpdate) urlRegex.find(body)?.value else null
        val forceUpdate = lower.contains("force update") || lower.contains("must update") ||
                lower.contains("mandatory update")
        return Announcement(id, title, body, isUpdate, downloadUrl, forceUpdate, publishedAt)
    }

    /** Check if the local backend is reachable. */
    private fun isBackendUp(): Boolean {
        return try {
            val conn = (URL("$BACKEND_BASE/v1/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 5000
            }
            val ok = conn.responseCode == 200
            Log.i(TAG, "Backend health: ${conn.responseCode} up=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Backend health check failed: ${e.message}")
            false
        }
    }

    /** Fetch unseen announcements, trying local backend then public API. */
    suspend fun fetchUnseen(ctx: Context): List<Announcement> = withContext(Dispatchers.IO) {
        val bases = listOf(BACKEND_BASE, BACKEND_PUBLIC)
        for (base in bases) {
            try {
                Log.i(TAG, "Trying $base")
                val result = fetchFromBase(ctx, base)
                if (result.isNotEmpty()) {
                    Log.i(TAG, "Got ${result.size} from $base")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch from $base failed: ${e.message}")
            }
        }
        Log.w(TAG, "No announcements from any backend")
        emptyList()
    }

    /** Fetch from a single backend base URL. Returns unseen announcements. */
    private fun fetchFromBase(ctx: Context, base: String): List<Announcement> {
        val token = getInstallToken(ctx)
        val conn = (URL("$base/v1/announcements?limit=10").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 10000
            if (token != null) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        if (conn.responseCode != 200) {
            Log.w(TAG, "HTTP ${conn.responseCode} from $base")
            try {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Error body: $err")
            } catch (e: Exception) { }
            return emptyList()
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val arr: JSONArray = try {
            val obj = JSONObject(text)
            obj.optJSONArray("items") ?: obj.optJSONArray("announcements") ?: JSONArray()
        } catch (e: Exception) {
            try { JSONArray(text) } catch (e2: Exception) { JSONArray() }
        }
        val result = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            if (isSeen(ctx, id)) continue
            val title = o.optString("title", "")
            val body = o.optString("body", "")
            if (title.isBlank() && body.isBlank()) continue
            result.add(parse(id, title, body, o.optString("published_at", null)))
        }
        return result
    }

    /** Get installation token from known storage locations. */
    private fun getInstallToken(ctx: Context): String? {
        // 1. EncryptedSharedPreferences
        try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs = EncryptedSharedPreferences.create(
                "ayx_install", masterKey, ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            for (key in listOf("installation_token", "access_token", "token")) {
                prefs.getString(key, null)?.let { return it }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Encrypted prefs: ${e.message}")
        }
        // 2. Regular SharedPreferences
        try {
            val prefs = ctx.getSharedPreferences("ayx_install", Context.MODE_PRIVATE)
            for (key in listOf("installation_token", "access_token", "installation_id")) {
                prefs.getString(key, null)?.let { return it }
            }
        } catch (e: Exception) { }
        // 3. Gateway auth token as fallback
        try {
            GatewayAuth.token()?.let { return it }
        } catch (e: Exception) { }
        Log.w(TAG, "No installation token found")
        return null
    }

    /** Post a system notification for a new announcement. */
    fun postNotification(ctx: Context, announcement: Announcement) {
        try {
            val channelId = "ayx_announcements"
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId, "AyX Announcements",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Official announcements from AyX" }
                mgr?.createNotificationChannel(channel)
            }
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                putExtra("show_announcement", announcement.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                ctx, announcement.id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(announcement.title.ifBlank { "AyX Announcement" })
                .setContentText(announcement.body.take(100))
                .setStyle(NotificationCompat.BigTextStyle().bigText(announcement.body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            mgr?.notify(announcement.id.hashCode(), notification)
            Log.i(TAG, "Notification posted for ${announcement.id}")
        } catch (e: Exception) {
            Log.w(TAG, "Notification failed: ${e.message}")
        }
    }
}

/**
 * Material dialog showing an announcement.
 * OK button is disabled for 5 seconds to encourage reading.
 * URLs are clickable. Update posts show version info + Download button.
 */
@Composable
fun AnnouncementDialog(
    announcement: AnnouncementStore.Announcement,
    onDismiss: () -> Unit
) {
    var secondsLeft by remember { mutableStateOf(5) }
    val context = LocalContext.current

    // Countdown timer for OK button
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }

    AlertDialog(
        onDismissRequest = { if (!announcement.forceUpdate) onDismiss() },
        title = { Text(announcement.title.ifBlank { "Announcement" }) },
        text = {
            Column {
                // Body with clickable links
                AndroidView(
                    factory = { ctx ->
                        TextView(ctx).apply {
                            text = announcement.body
                            autoLinkMask = Linkify.WEB_URLS
                            linksClickable = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (announcement.isUpdate) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Update available",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (announcement.isUpdate && announcement.downloadUrl != null) {
                    TextButton(
                        onClick = {
                            try {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse(announcement.downloadUrl)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Log.w("AyxAnnouncement", "Open URL failed: ${e.message}")
                            }
                        }
                    ) {
                        Text("Download")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = secondsLeft <= 0 && !announcement.forceUpdate
                ) {
                    Text(if (secondsLeft > 0) "OK (${secondsLeft}s)" else "OK")
                }
            }
        }
    )
}
