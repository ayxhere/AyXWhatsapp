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
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Top-level alias so MainActivity can use Announcement directly. */
typealias Announcement = AnnouncementStore.Announcement

/**
 * AyX Announcement popup dialog system.
 *
 * Backend contract (nodejs-project/ayx-backend):
 * - POST /v1/installations/register -> { installation_id, access_token }
 * - GET  /v1/announcements?per_page=N  -> { data: [...], meta: { page, per_page, total } }
 *   with Authorization: Bearer <access_token>
 * - Announcement fields: id, title, body, status, published_at, scheduled_at, created_at, updated_at
 * - Only status='published' rows are returned by the server.
 *
 * Flow:
 * 1. ensureRegistered() provisions installation credentials once (EncryptedSharedPreferences).
 * 2. fetchUnseen() tries local backend then public API, parses { data: [...] }.
 * 3. Filters out already-seen IDs (local prefs).
 * 4. Caller shows AnnouncementDialog for the first unseen item.
 */
object AnnouncementStore {
    private const val TAG = "AyxAnnouncement"
    private const val PREFS = "ayx_announcements"
    private const val KEY_SEEN = "seen_ids"
    private const val CRED_PREFS = "ayx_install"
    private const val KEY_INSTALL_ID = "installation_id"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val BACKEND_BASE = "http://127.0.0.1:3101"
    private const val BACKEND_PUBLIC = "https://api.imayx.in"

    data class Announcement(
        val id: String,
        val title: String,
        val body: String,
        val status: String,
        val isUpdate: Boolean,
        val downloadUrl: String?,
        val forceUpdate: Boolean,
        val publishedAt: String?
    )

    // ---------------------------------------------------------- seen tracking

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
            Log.i(TAG, "Marked announcement seen: id=$id")
        } catch (e: Exception) {
            Log.w(TAG, "markSeen failed: ${e.message}")
        }
    }

    fun isSeen(ctx: Context, id: String): Boolean = seenIds(ctx).contains(id)

    // ---------------------------------------------------------- parsing

    /** Parse raw JSON into an Announcement, detecting update posts. */
    fun parse(id: String, title: String, body: String, status: String, publishedAt: String?): Announcement {
        val lower = "$title $body".lowercase()
        val isUpdate = lower.contains("update") || lower.contains("version") ||
                Regex("""v?\d+\.\d+""").containsMatchIn(lower)
        val urlRegex = Regex("""https?://[^\s<>"']+""")
        val downloadUrl = if (isUpdate) urlRegex.find(body)?.value else null
        val forceUpdate = lower.contains("force update") || lower.contains("must update") ||
                lower.contains("mandatory update")
        return Announcement(id, title, body, status, isUpdate, downloadUrl, forceUpdate, publishedAt)
    }

    /**
     * Extract the announcement array from a backend response.
     * Real schema: { data: [...], meta: {...} }.
     * Tolerates legacy/alternate shapes: { items: [...] }, { announcements: [...] }, or a bare [...].
     */
    fun extractArray(text: String): JSONArray {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            return try { JSONArray(trimmed) } catch (e: Exception) { JSONArray() }
        }
        val obj = JSONObject(trimmed)
        // Real backend schema first
        obj.optJSONArray("data")?.let { return it }
        // Tolerated alternates
        obj.optJSONArray("items")?.let { return it }
        obj.optJSONArray("announcements")?.let { return it }
        // Single object -> wrap
        if (obj.has("id")) {
            return JSONArray().put(obj)
        }
        return JSONArray()
    }

    // ---------------------------------------------------------- credentials

    private fun credPrefs(ctx: Context) = try {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            CRED_PREFS, masterKey, ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences unavailable, using plain prefs: ${e.message}")
        ctx.getSharedPreferences(CRED_PREFS, Context.MODE_PRIVATE)
    }

    private fun getAccessToken(ctx: Context): String? =
        try { credPrefs(ctx).getString(KEY_ACCESS_TOKEN, null) } catch (e: Exception) { null }

    private fun getInstallationId(ctx: Context): String? =
        try { credPrefs(ctx).getString(KEY_INSTALL_ID, null) } catch (e: Exception) { null }

    /**
     * Provision installation credentials if missing.
     * POST /v1/installations/register (no auth) -> { installation_id, access_token }.
     * Tries local backend first, then public API.
     * Returns true if credentials are available afterwards.
     */
    private fun ensureRegistered(ctx: Context): Boolean {
        if (getAccessToken(ctx) != null) return true
        for (base in listOf(BACKEND_BASE, BACKEND_PUBLIC)) {
            try {
                Log.i(TAG, "Registering installation with $base")
                val conn = (URL("$base/v1/installations/register").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().apply {
                    put("app_version", try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                    } catch (e: Exception) { "unknown" })
                    put("platform", "android")
                    put("locale", java.util.Locale.getDefault().toString())
                }.toString()
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
                val code = conn.responseCode
                if (code == 200 || code == 201) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(text)
                    val installationId = obj.optString("installation_id")
                    val accessToken = obj.optString("access_token")
                    if (installationId.isNotBlank() && accessToken.isNotBlank()) {
                        credPrefs(ctx).edit()
                            .putString(KEY_INSTALL_ID, installationId)
                            .putString(KEY_ACCESS_TOKEN, accessToken)
                            .apply()
                        Log.i(TAG, "Installation registered with $base (id=${installationId.take(8)}...)")
                        return true
                    }
                    Log.w(TAG, "Register $base returned HTTP $code but missing fields")
                } else {
                    Log.w(TAG, "Register $base HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Register with $base failed: ${e.message}")
            }
        }
        Log.w(TAG, "Installation registration failed on all backends")
        return false
    }

    // ---------------------------------------------------------- fetch

    /** Fetch unseen announcements, trying local backend then public API. */
    suspend fun fetchUnseen(ctx: Context): List<Announcement> = withContext(Dispatchers.IO) {
        Log.i(TAG, "fetchUnseen: start")
        if (!ensureRegistered(ctx)) {
            Log.w(TAG, "fetchUnseen: no installation credentials, aborting")
            return@withContext emptyList()
        }
        val bases = listOf(BACKEND_BASE, BACKEND_PUBLIC)
        for (base in bases) {
            try {
                val result = fetchFromBase(ctx, base)
                Log.i(TAG, "fetchUnseen: $base returned ${result.size} unseen")
                if (result.isNotEmpty()) return@withContext result
            } catch (e: Exception) {
                Log.w(TAG, "fetchUnseen: $base failed: ${e.message}")
            }
        }
        Log.i(TAG, "fetchUnseen: no unseen announcements")
        emptyList()
    }

    /** Fetch from a single backend base URL. Returns unseen announcements. */
    private fun fetchFromBase(ctx: Context, base: String): List<Announcement> {
        val token = getAccessToken(ctx)
        if (token == null) {
            Log.w(TAG, "fetchFromBase($base): missing access token")
            return emptyList()
        }
        val conn = (URL("$base/v1/announcements?per_page=10").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        Log.i(TAG, "fetchFromBase($base): HTTP $code")
        if (code == 401) {
            Log.w(TAG, "fetchFromBase($base): 401 unauthorized - token may be revoked; clearing to re-register next time")
            // Clear so next fetch re-registers
            try { credPrefs(ctx).edit().remove(KEY_ACCESS_TOKEN).remove(KEY_INSTALL_ID).apply() } catch (e: Exception) {}
            return emptyList()
        }
        if (code != 200) {
            try {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "fetchFromBase($base): HTTP $code body=${err?.take(200)}")
            } catch (e: Exception) { }
            return emptyList()
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        Log.i(TAG, "fetchFromBase($base): response ${text.length} chars")
        val arr: JSONArray = try {
            extractArray(text)
        } catch (e: Exception) {
            Log.w(TAG, "fetchFromBase($base): JSON parse failed: ${e.message}")
            return emptyList()
        }
        Log.i(TAG, "fetchFromBase($base): parsed ${arr.length()} items")
        val seen = seenIds(ctx)
        val result = mutableListOf<Announcement>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) {
                Log.d(TAG, "fetchFromBase: skipping item $i (blank id)")
                continue
            }
            if (seen.contains(id)) {
                Log.d(TAG, "fetchFromBase: skipping already-seen id=$id")
                continue
            }
            val title = o.optString("title", "")
            val body = o.optString("body", "")
            val status = o.optString("status", "")
            if (title.isBlank() && body.isBlank()) {
                Log.d(TAG, "fetchFromBase: skipping id=$id (blank title+body)")
                continue
            }
            val ann = parse(id, title, body, status, o.optString("published_at", null))
            Log.i(TAG, "fetchFromBase: unseen id=$id title='${title.take(40)}' status=$status update=${ann.isUpdate}")
            result.add(ann)
        }
        return result
    }

    // ---------------------------------------------------------- notification

    /** Post a system notification for a new announcement. Tapping opens the app. */
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
            Log.i(TAG, "Notification posted for id=${announcement.id}")
        } catch (e: Exception) {
            Log.w(TAG, "Notification failed: ${e.message}")
        }
    }
}

/**
 * Material dialog showing an announcement.
 * OK button is disabled for 5 seconds to encourage reading.
 * URLs are clickable. Update posts show Download button.
 * Non-dismissible when forceUpdate is true.
 */
@Composable
fun AnnouncementDialog(
    announcement: AnnouncementStore.Announcement,
    onDismiss: () -> Unit
) {
    var secondsLeft by remember { mutableStateOf(5) }
    val context = LocalContext.current

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
