package ayx.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * AyX Announcement popup dialog system.
 *
 * Replaces the old "AyX updates" Settings screen with direct popup dialogs.
 * When the user posts an announcement via the website, all app users see
 * a Material-style dialog with:
 * - Title and body text
 * - Clickable URLs (auto-detected in body)
 * - 5-second countdown before OK is enabled ("read all then tap OK")
 * - For app updates: version name, what's new, Download button → opens website
 * - Optional force-update mode (dialog cannot be dismissed)
 */

data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val publishedAt: String?,
    // App-update specific (parsed from title/body conventions)
    val isUpdate: Boolean = false,
    val versionName: String? = null,
    val downloadUrl: String? = null,
    val forceUpdate: Boolean = false
)

object AnnouncementStore {
    private const val TAG = "AyxAnnouncement"
    private const val PREFS = "ayx_announcements"
    private const val KEY_SEEN = "seen_ids"
    private const val BACKEND_BASE = "http://127.0.0.1:3101"

    private val urlRegex = Regex("""https?://[^\s<>"')\]]+""")

    /** Extract all URLs from text. */
    fun extractUrls(text: String): List<String> =
        urlRegex.findAll(text).map { it.value.trimEnd('.', ',', '!', '?', ';', ':') }.distinct().toList()

    /** Parse a raw announcement JSON into our model, detecting app updates. */
    fun parse(id: String, title: String, body: String, publishedAt: String?): Announcement {
        val urls = extractUrls("$title $body")
        // Update detection: title mentions update/version, or body has version-like pattern
        val titleLower = title.lowercase()
        val isUpdate = titleLower.contains("update") || titleLower.contains("version") ||
                titleLower.contains("new version") || Regex("""v?\d+\.\d+""").containsMatchIn(title)
        var versionName: String? = null
        if (isUpdate) {
            versionName = Regex("""v?(\d+\.\d+(?:\.\d+)?)""").find(title)?.groupValues?.get(1)
        }
        // First URL is treated as download link for updates
        val downloadUrl = if (isUpdate) urls.firstOrNull() else null
        val forceUpdate = titleLower.contains("force") || body.lowercase().contains("force update") ||
                body.lowercase().contains("must update")
        return Announcement(id, title, body, publishedAt, isUpdate, versionName, downloadUrl, forceUpdate)
    }

    private fun seenIds(ctx: Context): MutableSet<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun markSeen(ctx: Context, id: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = seenIds(ctx)
        seen.add(id)
        // Keep only last 50 to avoid unbounded growth
        val trimmed = if (seen.size > 50) seen.sorted().takeLast(50).toSet() else seen
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    fun isSeen(ctx: Context, id: String): Boolean = seenIds(ctx).contains(id)

    /** Fetch published announcements from the local backend. Returns unseen ones. */
    suspend fun fetchUnseen(ctx: Context): List<Announcement> = withContext(Dispatchers.IO) {
        try {
            val installId = getInstallId(ctx) ?: return@withContext emptyList()
            val url = URL("$BACKEND_BASE/v1/announcements?limit=10")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 10000
                setRequestProperty("X-Installation-Id", installId)
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "announcements fetch: HTTP ${conn.responseCode}")
                return@withContext emptyList()
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = try {
                val obj = org.json.JSONObject(text)
                obj.optJSONArray("items") ?: obj.optJSONArray("announcements") ?: JSONArray()
            } catch (e: Exception) {
                JSONArray(text)
            }
            val result = mutableListOf<Announcement>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { continue }
                if (isSeen(ctx, id)) continue
                val title = o.optString("title", "")
                val body = o.optString("body", "")
                if (title.isBlank() && body.isBlank()) continue
                result.add(parse(id, title, body, o.optString("published_at", null)))
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "announcements fetch failed: ${e.message}")
            emptyList()
        }
    }

    /** Get the installation ID for backend auth (same as AyX updates used). */
    private fun getInstallId(ctx: Context): String? {
        return try {
            val prefs = ctx.getSharedPreferences("ayx_install", Context.MODE_PRIVATE)
            prefs.getString("installation_id", null)
        } catch (e: Exception) { null }
    }
}

/** Build annotated string with clickable URLs. */
@Composable
fun announcementTextWithLinks(body: String, linkColor: Color, onUrlClick: (String) -> Unit) =
    remember(body) {
        buildAnnotatedString {
            var lastIndex = 0
            val urls = AnnouncementStore.extractUrls(body)
            // Find each URL position and style it
            var searchFrom = 0
            for (url in urls) {
                val idx = body.indexOf(url, searchFrom)
                if (idx < 0) continue
                if (idx > lastIndex) append(body.substring(lastIndex, idx))
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(url)
                }
                pop()
                lastIndex = idx + url.length
                searchFrom = lastIndex
            }
            if (lastIndex < body.length) append(body.substring(lastIndex))
        }
    }

/**
 * Material-style announcement dialog.
 *
 * - 5-second countdown: OK button disabled until user has had time to read.
 * - URLs in body are clickable (open in browser).
 * - App updates show version, what's new, and a Download button.
 * - Force updates cannot be dismissed.
 */
@Composable
fun AnnouncementDialog(
    announcement: Announcement,
    onDismiss: () -> Unit,
    onDownload: ((String) -> Unit)? = null
) {
    val ctx = LocalContext.current
    var secondsLeft by remember { mutableStateOf(5) }
    val canConfirm = secondsLeft <= 0

    LaunchedEffect(announcement.id) {
        secondsLeft = 5
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
    }

    val linkColor = MaterialTheme.colorScheme.primary

    Dialog(
        onDismissRequest = { if (!announcement.forceUpdate) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !announcement.forceUpdate, dismissOnClickOutside = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                // Title
                Text(
                    text = announcement.title.ifBlank { "Announcement" },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                // Version badge for updates
                if (announcement.isUpdate && announcement.versionName != null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("v${announcement.versionName}") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // Body with clickable links
                val annotated = announcementTextWithLinks(announcement.body, linkColor) { url ->
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
                ClickableText(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    onClick = { offset ->
                        annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                            .firstOrNull()?.let { ann ->
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ann.item)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            }
                    }
                )
                Spacer(Modifier.height(20.dp))
                // Countdown hint
                if (!canConfirm) {
                    Text(
                        text = "Please read… OK enabled in ${secondsLeft}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    // Download button for app updates
                    if (announcement.isUpdate && announcement.downloadUrl != null) {
                        Button(
                            onClick = {
                                if (onDownload != null) onDownload(announcement.downloadUrl)
                                else runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(announcement.downloadUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                            },
                            enabled = canConfirm,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(if (announcement.forceUpdate) "Update Now" else "Download")
                        }
                    }
                    // OK button (disabled during countdown, hidden for force update without download)
                    if (!announcement.forceUpdate || announcement.downloadUrl == null) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = canConfirm
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
}
