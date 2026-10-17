package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Group-chat names, keyed by the FULL group JID (e.g. "123456-789@g.us").
 *
 * BUG (fixed here): group rows showed the LAST SENDER's name as the title.
 * Root cause chain:
 *  1. Baileys' pushName on a group message is the SENDER's name, not the
 *     group subject — Node stored it as the message's `name`.
 *  2. chatTitle() fell back to that sender name for groups.
 *  3. ContactStore/SetName match by DIGIT number — a group JID's digits
 *     could even fuzzy-match a random person's alias.
 *
 * This store is keyed by the exact full JID — NO digit/fuzzy matching, so a
 * group can never pick up a person's name. Two sources, user alias wins:
 *  - subject: fetched from Baileys group metadata via the gateway
 *    (GatewayClient.getGroupName -> Node /groupname).
 *  - alias: set by the user in the "Set name" dialog for that group.
 * Subjects are never allowed to overwrite a user alias.
 */
object GroupNameStore {
    // full group jid -> display name (subject or user alias)
    val map = mutableStateMapOf<String, String>()
    // full group jids where the user set an alias (subjects must not overwrite)
    private val aliases = mutableSetOf<String>()
    private var prefs: android.content.SharedPreferences? = null
    // last fetch attempt per jid (ms) — throttle so the list never spams /groupname
    private val attemptedAt = mutableMapOf<String, Long>()

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("groupnames", Context.MODE_PRIVATE)
        prefs!!.all.forEach { (k, v) ->
            if (k.startsWith("n:") && v is String && v.isNotBlank()) map[k.removePrefix("n:")] = v
        }
        prefs!!.getStringSet("aliases", emptySet())?.let { aliases.addAll(it) }
    }

    private fun persist() {
        val e = prefs?.edit() ?: return
        e.putStringSet("aliases", aliases.toSet())
        e.apply()
    }

    private fun persistName(jid: String, name: String) {
        prefs?.edit()?.putString("n:$jid", name)?.apply()
    }

    /** Exact full-JID match only — never fuzzy, never a person's name. */
    fun nameFor(jid: String): String? {
        if (jid.isBlank()) return null
        return map[jid]?.takeIf { it.isNotBlank() }
    }

    fun isAlias(jid: String): Boolean = aliases.contains(jid)

    /** Store a Baileys group subject. Never overwrites a user alias. */
    fun putSubject(jid: String, name: String) {
        if (jid.isBlank() || name.isBlank()) return
        if (aliases.contains(jid)) return
        if (map[jid] != name) {
            map[jid] = name
            persistName(jid, name)
        }
    }

    fun setAlias(jid: String, name: String) {
        if (jid.isBlank()) return
        val n = name.trim()
        if (n.isBlank()) {
            clearAlias(jid)
            return
        }
        aliases.add(jid)
        map[jid] = n
        persistName(jid, n)
        persist()
    }

    fun clearAlias(jid: String) {
        if (jid.isBlank()) return
        aliases.remove(jid)
        map.remove(jid)
        prefs?.edit()?.remove("n:$jid")?.apply()
        persist()
    }

    /** True when a /groupname fetch is allowed now (at most 1 attempt / 60s). */
    fun shouldFetch(jid: String): Boolean {
        if (jid.isBlank()) return false
        val last = attemptedAt[jid] ?: 0L
        return System.currentTimeMillis() - last > 60_000L
    }

    fun markAttempted(jid: String) {
        attemptedAt[jid] = System.currentTimeMillis()
    }
}
