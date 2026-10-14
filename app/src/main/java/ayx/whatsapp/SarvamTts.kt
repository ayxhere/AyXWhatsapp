package ayx.whatsapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ".vv <text>" command.
 *  - The recipient gets ONLY a voice note (made by the gateway, see GatewayClient.sendVoice).
 *  - The typed ".vv <text>" line is kept LOCALLY (this device only) so you can see what you sent.
 */
object SarvamTts {
    fun isVvCommand(text: String): Boolean {
        val t = text.trim()
        return t.startsWith(".vv", ignoreCase = true) && t.length > 3 && (t[3].isWhitespace() || t.length > 4)
    }
    fun extractText(command: String): String = command.trim().drop(3).trim()
}

/** Local-only store for the typed ".vv ..." lines (never sent to WhatsApp). */
object VvLocal {
    private const val PREFS = "ayx_vv_local"
    private const val KEY = "items"
    private const val MAX = 300

    fun load(ctx: Context): List<GatewayClient.Msg> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                GatewayClient.Msg(o.getString("chat"), "", true, o.getString("text"), o.getLong("ts"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, chat: String, text: String, ts: Long) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = runCatching { JSONArray(sp.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("chat", chat).put("text", text).put("ts", ts))
        val out = JSONArray()
        for (i in maxOf(0, a.length() - MAX) until a.length()) out.put(a.get(i))
        sp.edit().putString(KEY, out.toString()).apply()
    }
}
