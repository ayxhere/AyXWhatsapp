package ayx.whatsapp

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * Local persistent store of number/jid -> display name.
 * Populated after login from: device phonebook + WhatsApp synced contacts.
 * Used on the home screen to resolve chat names (by full jid OR by number).
 *
 * TWO layers (v161):
 * - [deviceMap] — names from the phone's own address book. A name the
 *   user saved on THIS device always wins: WhatsApp push names must
 *   never overwrite it.
 * - [map] — WhatsApp-side names (push/notify/synced contact names).
 *   Only consulted when the number is NOT saved on the device.
 * The layers live in separate prefs files so neither can clobber the
 * other; [nameFor] resolves SetName alias -> device -> WhatsApp.
 */
object ContactStore {
    // key can be a full jid, a @lid jid, or a pure digit-number. value = display name.
    val map = mutableStateMapOf<String, String>()
    // Same keying as [map], but ONLY device-phonebook names (plus the
    // number->LID associations learned from the phonebook, so a device
    // name still wins when WhatsApp addresses the chat by LID).
    val deviceMap = mutableStateMapOf<String, String>()
    private var prefs: android.content.SharedPreferences? = null
    private var devicePrefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("contactstore", Context.MODE_PRIVATE)
        prefs!!.all.forEach { (k, v) -> if (v is String && v.isNotBlank()) map[k] = v }
        devicePrefs = ctx.getSharedPreferences("contactstore_device", Context.MODE_PRIVATE)
        devicePrefs!!.all.forEach { (k, v) -> if (v is String && v.isNotBlank()) deviceMap[k] = v }
    }

    /** Save under both the raw key and its digit-number form. */
    fun put(key: String, name: String) {
        if (key.isBlank() || name.isBlank()) return
        val e = prefs?.edit()
        if (map[key] != name) { map[key] = name; e?.putString(key, name) }
        val digits = key.substringBefore("@").substringBefore(":").filter { it.isDigit() }
        if (digits.isNotBlank() && map[digits] != name) { map[digits] = name; e?.putString(digits, name) }
        e?.apply()
    }

    /** Device-phonebook name — stored in the device layer only, so a
     *  later WhatsApp sync ([put]) can never overwrite it. */
    fun putDevice(key: String, name: String) {
        if (key.isBlank() || name.isBlank()) return
        val e = devicePrefs?.edit()
        if (deviceMap[key] != name) { deviceMap[key] = name; e?.putString(key, name) }
        val digits = key.substringBefore("@").substringBefore(":").filter { it.isDigit() }
        if (digits.isNotBlank() && deviceMap[digits] != name) { deviceMap[digits] = name; e?.putString(digits, name) }
        e?.apply()
    }

    fun putAll(pairs: List<Pair<String, String>>) { pairs.forEach { put(it.first, it.second) } }

    /** Device-layer lookup. Same safety rules as the main map: groups
     *  are never device-matched, and a LID only ever hits an EXACT key
     *  (the number->LID association learned from the phonebook) — a LID
     *  is never fuzzy-matched by digits to a device contact. */
    private fun deviceNameFor(jid: String): String? {
        if (jid.endsWith("@g.us")) return null
        deviceMap[jid]?.let { if (it.isNotBlank()) return it }
        if (jid.endsWith("@lid")) return null
        val num = jid.substringBefore("@").substringBefore(":").filter { it.isDigit() }
        if (num.isBlank()) return null
        deviceMap[num]?.let { if (it.isNotBlank()) return it }
        if (num.length >= 10) {
            val l10 = num.takeLast(10)
            deviceMap.entries.firstOrNull { it.key.filter { c -> c.isDigit() }.takeLast(10) == l10 && it.value.isNotBlank() }?.let { return it.value }
        }
        return null
    }

    /** Look up a name for a chat jid: exact jid, then by number, then by last-10 digits. */
    fun nameFor(jid: String): String? {
        if (jid.endsWith("@g.us")) {
            // GROUP JIDs are not people: never run SetName / digit / fuzzy
            // matching on them — a group id's digits could match a random
            // person's alias and the group row would show that person's
            // name. Group names live in GroupNameStore (exact full-JID key).
            return map[jid]?.takeIf { it.isNotBlank() }
        }
        SetName.get(jid)?.let { return it }   // user-set alias always wins
        // v161: a name saved on THIS device beats the WhatsApp-side name.
        deviceNameFor(jid)?.let { return it }
        map[jid]?.let { if (it.isNotBlank()) return it }
        if (jid.endsWith("@lid")) return null   // a LID is not a phone number — never fuzzy-match it to a device contact
        val num = jid.substringBefore("@").substringBefore(":").filter { it.isDigit() }
        if (num.isBlank()) return null
        map[num]?.let { if (it.isNotBlank()) return it }
        if (num.length >= 10) {
            val l10 = num.takeLast(10)
            map.entries.firstOrNull { it.key.filter { c -> c.isDigit() }.takeLast(10) == l10 && it.value.isNotBlank() }?.let { return it.value }
        }
        return null
    }
}
