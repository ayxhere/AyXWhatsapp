package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/**
 * Outgoing-message outbox.
 *
 * BUG (fixed here): sending showed the message as SENT (optimistic bubble)
 * even when the Baileys socket was down / Node was dead — the send call
 * threw, the optimistic bubble stayed, and nothing ever retried. The user
 * then had to open the original WhatsApp before anything moved.
 *
 * Now: when a send cannot be delivered, the message is queued here
 * (in-memory + persisted to SharedPreferences so it survives app restarts)
 * and flushed automatically when the connection opens again. Queued
 * messages are NEVER shown as sent — the UI shows a "Queued — sending
 * when connected" banner until the flush succeeds.
 *
 * Baileys is a linked-device (WhatsApp Web) session with its own auth in
 * the app's auth dir. It does NOT need the original WhatsApp app open.
 * If the session itself is logged out / expired, the queue is NOT retried
 * forever — the caller surfaces "Session expired, scan QR again" instead.
 */
data class QueuedMessage(
    val jid: String,
    val text: String,
    val quotedId: String?,
    val ts: Long,
    /**
     * Stable id for ONE logical send, reused across every retry / flush
     * attempt. The gateway dedupes on it, so a retry after a lost HTTP
     * response (message actually sent, reply lost) can NEVER deliver the
     * message twice. Empty for queue entries saved by older builds —
     * flush falls back to a deterministic "legacy-<jid>-<ts>" id.
     */
    val clientMsgId: String = "",
)

object SendQueue {
    private const val PREFS = "ayx_send_queue"
    private const val KEY = "queue"

    /** Live count for the "Queued" banner. */
    val pendingCount = mutableStateOf(0)

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): List<QueuedMessage> {
        return try {
            val raw = prefs(ctx).getString(KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                QueuedMessage(
                    jid = o.optString("jid"),
                    text = o.optString("text"),
                    quotedId = o.optString("quotedId").ifEmpty { null },
                    ts = o.optLong("ts"),
                    clientMsgId = o.optString("mid"),
                )
            }.filter { it.jid.isNotBlank() && it.text.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }.also { pendingCount.value = it.size }
    }

    @Synchronized
    fun enqueue(ctx: Context, jid: String, text: String, quotedId: String?, clientMsgId: String = ""): Boolean {
        val current = all(ctx).toMutableList()
        // Dedupe: same stable send id, or same chat + same text within 10s,
        // is the same tap — never two queue entries for one send.
        val now = System.currentTimeMillis()
        if (clientMsgId.isNotBlank() && current.any { it.clientMsgId == clientMsgId }) return false
        if (current.any { it.jid == jid && it.text == text && now - it.ts < 10_000 }) return false
        current.add(QueuedMessage(jid, text, quotedId, now, clientMsgId))
        save(ctx, current)
        return true
    }

    @Synchronized
    fun remove(ctx: Context, q: QueuedMessage) {
        val remaining = all(ctx).filterNot {
            it.jid == q.jid && it.text == q.text && it.ts == q.ts
        }
        save(ctx, remaining)
    }

    @Synchronized
    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, items: List<QueuedMessage>) {
        val arr = JSONArray()
        items.takeLast(50).forEach { q ->
            arr.put(
                JSONObject()
                    .put("jid", q.jid)
                    .put("text", q.text)
                    .put("quotedId", q.quotedId ?: "")
                    .put("ts", q.ts)
                    .put("mid", q.clientMsgId),
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        pendingCount.value = items.size.coerceAtMost(50)
    }
}
