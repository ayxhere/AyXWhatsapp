package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Per-group "read up to here" markers (epoch ms), keyed by FULL group JID.
 *
 * Why this exists (group unread badge fix): the chat-list badge used to be
 * a verbatim pass-through of the gateway's Baileys chat `unreadCount`.
 * For 1:1 chats that value is a real server-maintained message count, but
 * for GROUPS it behaves as a mere "any unread" flag — it sits at 1 no
 * matter how many messages are actually pending (WhatsApp's own clients
 * count group unreads from the local message store instead). So the app
 * now counts a group's unread itself: incoming messages newer than this
 * marker. The marker is written every time the group is marked read
 * (opened / backed out of) and persisted, so the count survives restarts.
 */
object GroupReadStore {
    // full group jid -> epoch ms up to which the user has read
    val map = mutableStateMapOf<String, Long>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("groupread", Context.MODE_PRIVATE)
        prefs!!.all.forEach { (k, v) -> if (v is Long && v > 0L) map[k] = v }
    }

    fun marker(jid: String): Long = map[jid] ?: 0L

    fun markRead(jid: String, ts: Long) {
        if (jid.isBlank() || ts <= 0L) return
        if ((map[jid] ?: 0L) >= ts) return
        map[jid] = ts
        prefs?.edit()?.putLong(jid, ts)?.apply()
    }
}
