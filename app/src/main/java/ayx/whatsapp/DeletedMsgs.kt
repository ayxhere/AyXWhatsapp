package ayx.whatsapp

import android.content.Context

/**
 * Local "deleted for me" tombstones (U3).
 *
 * The gateway REMOVES a delete-for-me message from its store entirely, so the
 * chat would just lose the bubble. WhatsApp instead keeps a
 * "This message was deleted" placeholder — this store remembers which message
 * ids were deleted-for-me per chat (with fromMe + ts for positioning), and
 * ChatDetail re-injects a placeholder bubble for them.
 *
 * Survives app restarts via SharedPreferences. Keyed per chat jid; entries are
 * "<id>|<fromMe 1/0>|<ts>".
 */
object DeletedMsgs {
    private const val PREF = "ayx_deleted_msgs"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun markDeleted(ctx: Context, chat: String, id: String, fromMe: Boolean, ts: Long) {
        val p = prefs(ctx)
        val cur = p.getStringSet(chat, emptySet())?.toMutableSet() ?: mutableSetOf()
        // one tombstone per id — replace any older entry for the same id
        cur.removeAll { it.substringBefore("|") == id }
        cur.add("$id|${if (fromMe) 1 else 0}|$ts")
        p.edit().putStringSet(chat, cur).apply()
    }

    data class Tombstone(val id: String, val fromMe: Boolean, val ts: Long)

    fun getForChat(ctx: Context, chat: String): List<Tombstone> {
        val set = prefs(ctx).getStringSet(chat, emptySet()) ?: return emptyList()
        return set.mapNotNull { e ->
            val parts = e.split("|")
            if (parts.size != 3) null
            else Tombstone(parts[0], parts[1] == "1", parts[2].toLongOrNull() ?: 0L)
        }
    }

    fun clearChat(ctx: Context, chat: String) {
        prefs(ctx).edit().remove(chat).apply()
    }
}
