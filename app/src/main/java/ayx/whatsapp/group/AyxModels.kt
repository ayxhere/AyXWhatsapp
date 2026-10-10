package ayx.whatsapp.group

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Monotonic sync cursor from the server; -1 = never synced. */
typealias SyncCursor = Long

const val NO_CURSOR: SyncCursor = -1L

/** A published announcement from the AyX Group backend. Client only ever sees published items. */
data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val publishedAt: Long, // epoch millis
    val createdAt: Long,   // epoch millis
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("published_at", publishedAt)
        .put("created_at", createdAt)

    companion object {
        fun fromJson(o: JSONObject): Announcement = Announcement(
            id = o.optString("id"),
            title = o.optString("title"),
            body = o.optString("body"),
            publishedAt = parseIso8601(o.optString("published_at")),
            createdAt = parseIso8601(o.optString("created_at")),
        )
    }
}

/** An AyX status (story). kind is "text" | "image" | "video". */
data class AyxStatus(
    val id: String,
    val kind: String,
    val text: String?,
    val mediaUrl: String?,
    val expiresAt: Long, // epoch millis
    val createdAt: Long, // epoch millis
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAt

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("text", text)
        .put("media_url", mediaUrl)
        .put("expires_at", expiresAt)
        .put("created_at", createdAt)

    companion object {
        fun fromJson(o: JSONObject): AyxStatus = AyxStatus(
            id = o.optString("id"),
            kind = o.optString("kind", "text"),
            text = o.optString("text").ifEmpty { null },
            mediaUrl = o.optString("media_url").ifEmpty { null },
            expiresAt = parseIso8601(o.optString("expires_at")),
            createdAt = parseIso8601(o.optString("created_at")),
        )
    }
}

internal fun JSONArray.toAnnouncementList(): List<Announcement> =
    (0 until length()).mapNotNull { runCatching { Announcement.fromJson(getJSONObject(it)) }.getOrNull() }

internal fun JSONArray.toStatusList(): List<AyxStatus> =
    (0 until length()).mapNotNull { runCatching { AyxStatus.fromJson(getJSONObject(it)) }.getOrNull() }

internal fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).ifEmpty { null } }

/**
 * Parse an ISO-8601 UTC timestamp ("2026-10-10T12:00:00.000Z" or without millis).
 * Uses SimpleDateFormat instead of java.time so it works on minSdk 24 (no desugaring).
 */
fun parseIso8601(s: String): Long {
    if (s.isBlank()) return 0L
    val utc = TimeZone.getTimeZone("UTC")
    val formats = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    for (f in formats) {
        try {
            return SimpleDateFormat(f, Locale.US).apply { timeZone = utc }.parse(s)?.time ?: 0L
        } catch (_: Exception) { /* try next */ }
    }
    return 0L
}

/** "just now" / "5m ago" / "2h ago" / "Yesterday" / "3d ago" / "12 Oct 2026". */
fun relativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0L) return ""
    val d = now - ts
    return when {
        d < 60_000L -> "just now"
        d < 3_600_000L -> "${d / 60_000L}m ago"
        d < 86_400_000L -> "${d / 3_600_000L}h ago"
        d < 2 * 86_400_000L -> "Yesterday"
        d < 7 * 86_400_000L -> "${d / 86_400_000L}d ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}
