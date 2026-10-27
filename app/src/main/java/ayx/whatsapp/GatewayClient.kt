package ayx.whatsapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Tiny REST client for the in-process node server on 127.0.0.1. */
object GatewayClient {
    private val base = "http://127.0.0.1:${NodeRuntime.PORT}"

    data class Status(
        val connection: String = "?",
        val registered: Boolean = false,
        val me: String? = null,
        val lastError: String? = null,
        val hasQr: Boolean = false,
        val pairingCode: String? = null,
        val reachable: Boolean = false,
        /** True when the Baileys session is logged out / expired — user must scan QR again. */
        val needRelogin: Boolean = false,
    )

    data class Rule(val match: String, val reply: String, val mode: String = "contains")
    /** One AI Lyrics translate API config (OpenAI-compatible chat-completions endpoint). List order = failover try order. */
    data class LyricApi(val name: String = "", val url: String = "", val key: String = "", val model: String = "")
    data class Settings(
        val alwaysOnline: Boolean = false,
        val autoRead: Boolean = false,
        val autoReplyEnabled: Boolean = false,
        val rules: List<Rule> = emptyList(),
        val aiReplyEnabled: Boolean = false,
        val groupAiEnabled: Boolean = false,
        val aiApiUrl: String = "",
        val aiApiKey: String = "",
        val aiModel: String = "",
        val hideStatusRead: Boolean = true,
        val aiSystemPrompt: String = "",
        val saveMedia: Boolean = false,
        val stayOffline: Boolean = false,
        val aiPresenceFlow: Boolean = false,
        val aiExcludeJids: List<String> = emptyList(),
        val aiReplyVoice: Boolean = false,
        val aiReplyImage: Boolean = false,
        val aiVisionModel: String = "",
        val aiVisionApiUrl: String = "",
        val aiVisionApiKey: String = "",
        val aiVisionModel2: String = "",
        val aiVisionApiUrl2: String = "",
        val aiVisionApiKey2: String = "",
        val aiImageOcr: Boolean = false,
        val aiImageFree: Boolean = true,
        val aiCommandsEnabled: Boolean = true,
        val aiVoiceNoteReply: Boolean = false,
        val aiTtsVoice: String = "Arista-PlayAI",
        val aiSarvamKey: String = "",
        val aiLangMode: String = "auto",
        val aiFullContext: Boolean = true,
        val aiReplyLang: String = "",
        /** AI Lyrics translate APIs (Settings → AI Assistant → AI Lyrics), tried IN ORDER. */
        val aiLyricsApis: List<LyricApi> = emptyList(),
    )
    data class Contact(val jid: String, val name: String, val number: String)
    data class Msg(val chat: String, val name: String, val fromMe: Boolean, val text: String, val ts: Long,
                   val mediaName: String? = null, val mediaType: String? = null, val thumb: String? = null, val deleted: Boolean = false, val id: String? = null, val reaction: String? = null, val sender: String? = null, val quotedText: String? = null, val quotedId: String? = null, val edited: Boolean = false, val status: Int = 0, val forwarded: Boolean = false,
                   val albumId: String? = null,
                   // Group chats only: the Baileys group subject (group name) from the
                   // gateway. NOTE: `name` on a group message is the SENDER's pushName,
                   // never the group name — use chatName for group titles.
                   val chatName: String? = null)

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        try {
            val o = get("/status")
            Status(
                connection = o.optString("connection", "?"),
                registered = o.optBoolean("registered", false),
                me = o.optString("me").ifEmpty { null },
                lastError = o.optString("lastError").ifEmpty { null },
                hasQr = o.optBoolean("hasQr", false),
                pairingCode = o.optString("pairingCode").ifEmpty { null },
                reachable = true,
                needRelogin = o.optBoolean("needRelogin", false),
            )
        } catch (e: Exception) {
            Status(connection = "starting…", reachable = false)
        }
    }

    suspend fun qrBytes(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$base/qr.png").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 3000; readTimeout = 8000
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    suspend fun pair(number: String): String = withContext(Dispatchers.IO) {
        val o = post("/pair", JSONObject().put("number", number))
        when {
            o.optBoolean("registered", false) -> "registered"
            o.has("code") -> o.getString("code")
            else -> throw RuntimeException(o.optString("error", "unknown error"))
        }
    }

    suspend fun send(to: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val o = post("/send", JSONObject().put("to", to).put("text", text))
        if (o.optBoolean("ok", false)) true else throw RuntimeException(o.optString("error", "send failed"))
    }

    suspend fun sendToJid(jid: String, text: String): Boolean = withContext(Dispatchers.IO) {
        // Smart AI reminders (Reschedule.kt): natural reminder requests in any
        // outgoing message are consumed here at the delivery layer so the chat
        // composer stays untouched (frozen 2026-10-15). No command prefix.
        if (Reschedule.handleOutgoing(jid, text)) return@withContext true
        sendToJidDirect(jid, text)
    }

    /** Raw send without reminder interception — used by the reminder fire worker. */
    suspend fun sendToJidDirect(jid: String, text: String, clientMsgId: String = ""): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jid", jid).put("text", text)
        if (clientMsgId.isNotBlank()) body.put("clientMsgId", clientMsgId)
        val o = post("/sendraw", body)
        if (o.optBoolean("ok", false)) true else throw RuntimeException(o.optString("error", "send failed"))
    }

    /** Serializes queue flushes: two concurrent flushes (3s poll loop +
     * "Retry now" tap) must never send the same queued message twice. The
     * gateway's clientMsgId dedupe is the second guard; this is the first. */
    private val flushMutex = kotlinx.coroutines.sync.Mutex()

    /** One stable id per logical send. Reused across the 3 retry attempts
     * and every later flush, so the gateway can dedupe a retry whose
     * first attempt actually sent (reply lost to a timeout). */
    fun newClientMsgId(): String = java.util.UUID.randomUUID().toString()

    /**
     * Reliable text send.
     *
     * Returns [SendOutcome.Sent] only when the gateway confirmed delivery
     * to the Baileys socket. When the socket / Node is down, the gateway
     * queues server-side and this returns [SendOutcome.Queued] — the
     * caller must NOT show the message as sent. When the session itself is
     * logged out, returns [SendOutcome.NeedRelogin] so the UI can ask for a
     * fresh QR scan instead of queuing forever.
     */
    sealed class SendOutcome {
        data object Sent : SendOutcome()
        data class Queued(val reason: String) : SendOutcome()
        data object NeedRelogin : SendOutcome()
    }

    private fun isLoggedOutError(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("logged out") || m.contains("logged_out") || m.contains("401") ||
            m.contains("session expired") || m.contains("not registered")
    }

    suspend fun sendTextReliable(jid: String, text: String, quotedId: String? = null, clientMsgId: String = newClientMsgId()): SendOutcome =
        withContext(Dispatchers.IO) {
            // 3 attempts: 1s, 2s backoff. Covers Node still starting up and
            // short reconnect windows without making the user wait long.
            // All 3 attempts carry the SAME clientMsgId: if attempt 1
            // actually sent but its HTTP reply was lost, the gateway
            // dedupes attempt 2 instead of delivering twice.
            repeat(3) { attempt ->
                try {
                    val o = if (quotedId != null) {
                        post("/sendreply", JSONObject().put("jid", jid).put("text", text).put("quotedId", quotedId).put("clientMsgId", clientMsgId))
                    } else {
                        post("/sendraw", JSONObject().put("jid", jid).put("text", text).put("clientMsgId", clientMsgId))
                    }
                    // M1: check `queued` BEFORE `ok` — a gateway may return
                    // {ok:true, queued:true} ("not sent, will retry"); treating
                    // that as Sent silently DROPS the message (it was never
                    // sent and never entered the local queue). Queued must
                    // always win so the message lands in SendQueue.
                    if (o.optBoolean("queued", false)) {
                        return@withContext SendOutcome.Queued(o.optString("error", "queued"))
                    }
                    if (o.optBoolean("ok", false)) return@withContext SendOutcome.Sent
                    if (o.optBoolean("needRelogin", false) || isLoggedOutError(o.optString("error", ""))) {
                        return@withContext SendOutcome.NeedRelogin
                    }
                    // Plain failure (e.g. "not connected" without queue flag):
                    // retry once or twice, then hand to the local queue.
                    if (attempt < 2) delay(if (attempt == 0) 1000L else 2000L)
                } catch (e: Exception) {
                    // Node down / connection refused: retry, then queue.
                    if (attempt < 2) delay(if (attempt == 0) 1000L else 2000L)
                    else return@withContext SendOutcome.Queued(e.message ?: "connection unavailable")
                }
            }
            SendOutcome.Queued("connection unavailable — queued")
        }

    /**
     * One flush attempt for every locally queued message. Called when the
     * connection opens again. Returns the queued messages that were
     * delivered, so the caller can drop their optimistic bubbles (the
     * server echo now represents them) instead of showing both.
     * Failures stay queued for the next flush — never silently dropped.
     * Serialized by [flushMutex] so overlapping flushes cannot double-send.
     */
    suspend fun flushSendQueue(ctx: android.content.Context): List<QueuedMessage> = withContext(Dispatchers.IO) {
        flushMutex.lock()
        try {
            val delivered = mutableListOf<QueuedMessage>()
            for (q in SendQueue.all(ctx)) {
                try {
                    // Same stable id on every flush attempt: a flush that
                    // overlaps an in-flight retry dedupes at the gateway.
                    val mid = q.clientMsgId.ifBlank { "legacy-${q.jid}-${q.ts}" }
                    val ok = if (q.quotedId != null) {
                        post("/sendreply", JSONObject().put("jid", q.jid).put("text", q.text).put("quotedId", q.quotedId).put("clientMsgId", mid))
                            .optBoolean("ok", false)
                    } else {
                        sendToJidDirect(q.jid, q.text, mid)
                    }
                    if (ok) {
                        SendQueue.remove(ctx, q)
                        delivered.add(q)
                    }
                } catch (_: Exception) {
                    // Keep queued; a later flush will retry. Never drop
                    // a user message silently.
                }
            }
            delivered
        } finally {
            flushMutex.unlock()
        }
    }

    fun mediaUrl(name: String) = "$base/media/$name"
    fun ttsDemoUrl(voice: String): String =
        "$base/ttsdemo?voice=" + java.net.URLEncoder.encode(voice, "UTF-8") +
            "&text=" + java.net.URLEncoder.encode("Hi! Main aapka assistant hoon, aise reply karunga.", "UTF-8")

    /** Streamed TTS of arbitrary text in the given voice — used to PREVIEW a voice status before posting. */
    fun ttsPreviewUrl(text: String, voice: String): String =
        "$base/ttsdemo?voice=" + java.net.URLEncoder.encode(voice, "UTF-8") +
            "&text=" + java.net.URLEncoder.encode(text.ifBlank { " " }, "UTF-8") +
            "&t=" + System.currentTimeMillis()

    /** Check whether Groq TTS works with the current key (or needs terms acceptance). */
    suspend fun voiceCheck(voice: String): JSONObject = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$base/voicecheck?voice=" + java.net.URLEncoder.encode(voice, "UTF-8")).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 4000; readTimeout = 40000
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            readJson(c)
        } catch (e: Exception) { JSONObject().put("ok", false).put("message", e.message ?: "error") }
    }

    /**
     * C2: media send is now async server-side — /sendmedia accepts immediately
     * (large videos no longer time out the HTTP request) and uploads in the
     * background. This polls /sendmedia/status/:id for completion so the UI
     * reports the REAL outcome instead of faking "sent".
     */
    suspend fun sendMedia(jid: String, dataB64: String, type: String, filename: String, caption: String, albumId: String = "", albumCount: Int = 0): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().put("jid", jid).put("type", type).put("data", dataB64).put("filename", filename).put("caption", caption)
        // Album batch (multi-select send): one shared albumId per batch so
        // the gateway tags every stored item and the chat renders ONE album.
        if (albumId.isNotBlank()) { body.put("albumId", albumId); body.put("albumCount", albumCount) }
        val o = post("/sendmedia", body, readTimeoutMs = 60000)
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "send failed"))
        val uploadId = o.optString("uploadId", "")
        // Fast path: tiny media may already be done.
        // Poll for up to ~5 minutes (large video uploads to WhatsApp servers).
        val deadline = System.currentTimeMillis() + 300_000L
        while (System.currentTimeMillis() < deadline) {
            delay(2000L)
            val st = try { get("/sendmedia/status/$uploadId") } catch (_: Exception) { null } ?: continue
            when (st.optString("state", "")) {
                "done" -> return@withContext true
                "error" -> throw RuntimeException(st.optString("error", "upload failed"))
            }
        }
        throw RuntimeException("upload timed out after 5 min — video may still arrive")
    }

    // .vv: gateway does text→speech and sends it as a voice note (the ".vv" text is never sent)
    suspend fun sendVoice(jid: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val o = post("/sendvoice", JSONObject().put("jid", jid).put("text", text))
        if (o.optBoolean("ok", false)) true else throw RuntimeException(o.optString("error", "voice failed"))
    }

    suspend fun setProfileName(name: String) = withContext(Dispatchers.IO) {
        val o = post("/profile/name", JSONObject().put("name", name))
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "failed"))
    }
    suspend fun setProfileBio(bio: String) = withContext(Dispatchers.IO) {
        val o = post("/profile/bio", JSONObject().put("bio", bio))
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "failed"))
    }
    suspend fun setProfilePicture(dataB64: String): Boolean = withContext(Dispatchers.IO) {
        post("/profile/picture", JSONObject().put("data", dataB64)).optBoolean("ok", false)
    }
    suspend fun deleteChat(jid: String) = withContext(Dispatchers.IO) {
        post("/chat/delete", JSONObject().put("jid", jid)).optBoolean("ok", false)
    }
    suspend fun react(jid: String, id: String, emoji: String, fromMe: Boolean) = withContext(Dispatchers.IO) {
        post("/react", JSONObject().put("jid", jid).put("id", id).put("emoji", emoji).put("fromMe", fromMe)).optBoolean("ok", false)
    }
    // translate (or transliterate) one message's text; returns the result text, or null on failure
    suspend fun translate(text: String, lang: String, romanize: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val o = post("/translate", JSONObject().put("text", text).put("lang", lang).put("romanize", romanize))
            if (o.optBoolean("ok", false)) o.optString("text").ifBlank { null } else null
        } catch (e: Exception) { null }
    }
    // translate/transliterate many lines, preserving count/order (status lyrics). Falls back to input on failure.
    suspend fun translateLines(lines: List<String>, romanize: Boolean, lang: String = "English"): List<String> = withContext(Dispatchers.IO) {
        translateLinesEx(lines, romanize, lang).first
    }
    /** AI Lyrics API configs in failover order. Migrates the old single key (GroqKeyDialog / API configuration) as the first "Groq" entry so nothing is lost. */
    private suspend fun lyricApis(): List<LyricApi> {
        val s = runCatching { getSettings() }.getOrNull() ?: return emptyList()
        // v168: never use a v167 mask ("••••••••…") as a key — it would 401.
        // (The gateway list itself is not redacted, but a mask can land in it
        // via the settings-screen migration of the single key.)
        val list = s.aiLyricsApis.filter { it.url.isNotBlank() && it.key.isNotBlank() && !AiKeyStore.isMask(it.key.trim()) }
        if (list.isNotEmpty()) return list
        // Legacy single-key fallback: since v167, settings.aiApiKey is only a
        // mask, so prefer the real key from the app-side encrypted copy
        // (AiKeyStore); fall back to the settings value only if it is a real
        // (non-mask) key.
        val legacyKey = AiKeyStore.getCached().ifBlank {
            s.aiApiKey.trim().takeUnless { AiKeyStore.isMask(it) } ?: ""
        }
        if (legacyKey.isNotBlank()) return listOf(LyricApi(
            name = "Groq",
            url = s.aiApiUrl.ifBlank { "https://api.groq.com/openai/v1/chat/completions" },
            key = legacyKey,
            model = s.aiModel.ifBlank { "openai/gpt-oss-20b" }))
        return emptyList()
    }
    /** Same as [translateLines] but also returns the backend error (null when ok) so the UI can show real states.
     * Tries each configured AI Lyrics API IN ORDER (failover); first success wins. If all fail,
     * the error lists each API with its failure reason.
     * romanize=true is deterministic on the Node side for Devanagari/Gurmukhi (no key needed),
     * so it always attempts once without an override first (Node falls back to its own settings). */
    suspend fun translateLinesEx(lines: List<String>, romanize: Boolean, lang: String = "English"): Pair<List<String>, String?> = withContext(Dispatchers.IO) {
        val apis = lyricApis()
        val attempts: List<LyricApi?> = if (romanize) listOf(null) + apis else apis
        if (attempts.isEmpty()) return@withContext lines to "no AI key"
        val failures = mutableListOf<String>()
        for (api in attempts) {
            // Translate calls hit a real AI API (long lyric lists can take 30s+): generous read timeout
            // + ONE automatic retry on timeout only, PER API. Other errors move to the next API.
            fun attempt(): Pair<List<String>, String?> {
                return try {
                    val arr = JSONArray(); lines.forEach { arr.put(it) }
                    val body = JSONObject().put("lines", arr).put("romanize", romanize).put("lang", lang)
                    if (api != null) body.put("api", JSONObject().put("url", api.url).put("key", api.key).put("model", api.model))
                    val o = post("/translatelines", body, readTimeoutMs = 90000)
                    if (o.optBoolean("ok", false)) {
                        val out = o.optJSONArray("lines") ?: return lines to "bad response"
                        (0 until out.length()).map { out.optString(it) } to null
                    } else lines to o.optString("error", "failed").ifBlank { "failed" }
                } catch (e: java.net.SocketTimeoutException) { lines to "timeout" }
                catch (e: Exception) { lines to (e.message ?: "connection error") }
            }
            var r = attempt()
            if (r.second == "timeout") {
                // one automatic retry on transient AI slowness; keep the manual Retry button too
                val second = attempt()
                if (second.second == null) r = second
            }
            if (r.second == null) return@withContext r
            failures.add("${api?.name?.ifBlank { "API" } ?: "default"}: ${r.second}")
        }
        lines to failures.joinToString(" · ")
    }
    data class Song(val title: String, val artist: String, val image: String?, val url: String, val durationSec: Int = 0)
    // returns synced lyrics as (timeMs, line); falls back to PLAIN (untimed) lyrics with
    // sequential timestamps when the provider has no synced lines; empty if none at all
    // LYRICS LOCK (2026-10-20): do not modify without user approval.
    // Root cause of the recurring "lyrics mismatch": lrclib synced lyrics can belong to a
    // DIFFERENT release of the song (different total length). Their timestamps are on that
    // release's timeline, so they drift progressively against the real audio — no constant
    // offset (trim anchor etc.) can ever fix that. durationSec (the actual track duration from
    // JioSaavn metadata) is passed to the gateway so it picks the same cut, and if the
    // returned lyrics' own duration (lyricsDuration) still differs materially, timestamps are
    // rescaled proportionally onto the real audio's timeline.
    suspend fun getLyrics(title: String, artist: String, durationSec: Int = 0): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        try {
            val t = java.net.URLEncoder.encode(title, "UTF-8"); val a = java.net.URLEncoder.encode(artist, "UTF-8")
            val o = get("/music/lyrics?title=$t&artist=$a" + if (durationSec > 0) "&duration=$durationSec" else "")
            val synced = o.optString("synced")
            if (synced.isNotBlank()) {
                val rx = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)")
                val parsed = synced.lines().mapNotNull { line ->
                    val m = rx.find(line) ?: return@mapNotNull null
                    val min = m.groupValues[1].toLong(); val sec = m.groupValues[2].toLong()
                    val cs = m.groupValues[3].ifEmpty { "0" }.take(2).padEnd(2, '0').toLong()
                    val ms = min * 60000 + sec * 1000 + cs * 10
                    val text = m.groupValues[4].trim()
                    if (text.isEmpty()) null else ms to text
                }.sortedBy { it.first }
                val lyrDur = o.optInt("lyricsDuration", 0)
                if (durationSec > 0 && lyrDur > 0) {
                    val diffSec = kotlin.math.abs(durationSec - lyrDur)
                    val ratio = durationSec.toDouble() / lyrDur.toDouble()
                    // Only rescale a plausible same-song drift; a wildly different ratio means a
                    // different cut entirely, where stretching would make things worse.
                    // v176 (user-approved 2026-10-24): NO dead zone. The old
                    // `diffSec >= 3` left 1–2s of drift uncorrected (the v166
                    // chimera case); with the gateway's artist+duration gate,
                    // a same-band ratio is a same-song drift, so every
                    // difference is now rescaled. App-pipeline timing error
                    // target: under 1s (poll 80–90ms + 170ms smoothing tween
                    // + 200ms editor lookahead ≈ ±0.1s worst case).
                    if (diffSec > 0 && ratio in 0.85..1.18) {
                        return@withContext parsed.map { (it.first * ratio).toLong() to it.second }
                    }
                }
                return@withContext parsed
            }
            // provider has only plain (untimed) lyrics — spread them sequentially so karaoke still works
            val plain = o.optString("plain")
            if (plain.isNotBlank()) {
                return@withContext plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .mapIndexed { i, line -> (i * 4000L) to line }
            }
            emptyList()
        } catch (e: Exception) { emptyList() }
    }
    suspend fun searchMusic(q: String): Pair<List<Song>, String> = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            val c = (URL("$base/music/search?q=$enc").openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 30000; GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) } }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val obj = JSONObject(txt)
            val arr = obj.optJSONArray("items")
            val songs = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i); val url = o.optString("url")
                if (url.isBlank()) null else Song(o.optString("title"), o.optString("artist"), o.optString("image").ifEmpty { null }, url, o.optInt("duration", 0))
            }
            songs to obj.optString("error")
        } catch (e: Exception) { emptyList<Song>() to (e.message ?: "connection error") }
    }

    suspend fun postStatus(type: String, dataB64: String, caption: String, audience: String, jids: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(); jids.forEach { arr.put(it) }
            val body = JSONObject().put("type", type).put("data", dataB64).put("caption", caption).put("audience", audience).put("jids", arr).toString()
            val c = (URL("$base/status/post").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4000
                // 7 min, NOT the old 3: the gateway's worst case is a
                // reconnect wait (~15s) + TWO upload attempts at a
                // 120s per-host media timeout (v139 fresh-conn retry).
                // A 180s client timeout cut off sends the gateway went
                // on to complete — the user was told "failed" for a
                // status that then posted (or never learned it had).
                readTimeout = 420000
                setRequestProperty("Content-Type", "application/json")
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val o = JSONObject(txt)
            o.optBoolean("ok", false) to (if (o.optBoolean("ok", false)) "Status uploaded" else (o.optString("error") + " | " + o.optString("diag")))
        } catch (e: Exception) { false to (e.message ?: "connection error") }
    }

    // AI voice status: gateway synthesizes the text and posts it as a voice-note status
    suspend fun postVoiceStatus(text: String, audience: String, jids: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(); jids.forEach { arr.put(it) }
            val body = JSONObject().put("text", text).put("audience", audience).put("jids", arr).toString()
            val c = (URL("$base/status/voice").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val o = JSONObject(txt)
            o.optBoolean("ok", false) to (if (o.optBoolean("ok", false)) "Status uploaded" else o.optString("error"))
        } catch (e: Exception) { false to (e.message ?: "connection error") }
    }

    // Coloured text status (like WhatsApp's text status)
    suspend fun postTextStatus(text: String, bg: String, audience: String, jids: List<String>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(); jids.forEach { arr.put(it) }
            val body = JSONObject().put("text", text).put("bg", bg).put("audience", audience).put("jids", arr).toString()
            val c = (URL("$base/status/text").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 60000
                setRequestProperty("Content-Type", "application/json")
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val o = JSONObject(txt)
            o.optBoolean("ok", false) to (if (o.optBoolean("ok", false)) "Status uploaded" else o.optString("error"))
        } catch (e: Exception) { false to (e.message ?: "connection error") }
    }

    suspend fun onWhatsApp(numbers: List<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(); numbers.forEach { arr.put(it) }
            val body = JSONObject().put("numbers", arr).toString()
            val c = (URL("$base/onwhatsapp").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            c.outputStream.use { it.write(body.toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val items = JSONObject(txt).optJSONArray("items") ?: return@withContext emptyMap()
            val m = HashMap<String, String?>()
            for (i in 0 until items.length()) { val o = items.getJSONObject(i); m[o.optString("number")] = o.optString("lid").ifEmpty { null } }
            m
        } catch (e: Exception) { emptyMap() }
    }

    suspend fun sendReply(jid: String, text: String, quotedId: String) = withContext(Dispatchers.IO) {
        post("/sendreply", JSONObject().put("jid", jid).put("text", text).put("quotedId", quotedId)).optBoolean("ok", false)
    }

    suspend fun editMessage(jid: String, id: String, text: String): JSONObject = withContext(Dispatchers.IO) {
        post("/editmessage", JSONObject().put("jid", jid).put("id", id).put("text", text))
    }

    suspend fun forward(id: String, jids: List<String>): JSONObject = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(); jids.forEach { arr.put(it) }
        post("/forward", JSONObject().put("id", id).put("jids", arr))
    }

    suspend fun markStatusRead(id: String, sender: String) = withContext(Dispatchers.IO) {
        runCatching { post("/status/read", JSONObject().put("id", id).put("sender", sender)) }
    }

    suspend fun clearCache(): JSONObject = withContext(Dispatchers.IO) { post("/clearcache", JSONObject()) }
    suspend fun clearData(): JSONObject = withContext(Dispatchers.IO) { post("/cleardata", JSONObject()) }

    suspend fun deleteMessage(jid: String, id: String?, text: String, ts: Long, forEveryone: Boolean, fromMe: Boolean) = withContext(Dispatchers.IO) {
        val o = JSONObject().put("jid", jid).put("forEveryone", forEveryone).put("fromMe", fromMe).put("text", text).put("ts", ts)
        if (id != null) o.put("id", id)
        post("/message/delete", o).optBoolean("ok", false)
    }
    suspend fun getContacts(): List<Contact> = withContext(Dispatchers.IO) {
        try {
            val out = mutableListOf<Contact>()
            get("/contacts").optJSONArray("items")?.let { a ->
                for (i in 0 until a.length()) { val c = a.getJSONObject(i); out.add(Contact(c.optString("jid"), c.optString("name"), c.optString("number"))) }
            }
            out
        } catch (e: Exception) { emptyList() }
    }
    // v148: no session export/import — the WhatsApp login never leaves the
    // app-private auth dir (see SessionMigration for the one-time cleanup).
    suspend fun blockChat(jid: String, block: Boolean) = withContext(Dispatchers.IO) {
        val o = post("/block", JSONObject().put("jid", jid).put("action", if (block) "block" else "unblock"))
        if (!o.optBoolean("ok", false)) throw RuntimeException(o.optString("error", "failed"))
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        // v197: logout() used to throw (post() connect-timeout) when the
        // gateway was down — exactly the Restoring-screen situation — and
        // all three callers launch it bare (Restoring/Link onReset, Settings
        // onLogout), so the SocketTimeoutException escaped the coroutine
        // uncaught and the crash reporter caught it (02:46 + 11:12
        // tombstones: POST -> getOutputStream, 3000 ms timeout). Logout
        // must never throw: false on any failure, callers already ignore
        // the result and the gateway-side wipe simply does not happen.
        try { post("/logout", JSONObject()).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    suspend fun getSettings(): Settings = withContext(Dispatchers.IO) {
        parseSettings(get("/settings"))
    }

    /** Post a partial settings patch; returns the updated settings. */
    suspend fun patchSettings(patch: JSONObject): Settings = withContext(Dispatchers.IO) {
        val updated = parseSettings(post("/settings", patch))
        // v170: THE settings-write choke point — every settings write in
        // the app flows through this one function (the only other
        // /settings POST is setRules, which carries no key/prompt fields).
        // Mirror the whitelisted key/prompt fields app-side (encrypted) so
        // DriveBackup has real values to back up: since v167 the gateway
        // only ever returns masks. mirrorPatch itself ignores blanks and
        // masks; a change offers DriveBackup an auto-sync.
        if (SettingsMirror.mirrorPatch(patch)) DriveBackup.onMirrorUpdated()
        updated
    }

    /** Run the Image-AI self-test against a built-in test image (may take a while — tries each provider). */
    suspend fun visionTest(): JSONObject = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$base/visiontest").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 4000; readTimeout = 90000
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            readJson(c)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "test failed")
        }
    }

    // ---- AI Memory ----
    data class MemMsg(val role: String, val content: String, val ts: Long)
    data class MemContact(
        val jid: String, val name: String, val number: String, val lid: String,
        val msgCount: Int, val lastTs: Long, val lang: String, val recent: List<MemMsg>,
    )

    /** Every contact the AI has talked to (newest first) with recent chat logs. */
    suspend fun getAiMemory(): List<MemContact> = withContext(Dispatchers.IO) {
        try {
            val arr = get("/aimemory").optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val rArr = c.optJSONArray("recent")
                val recent = if (rArr != null) (0 until rArr.length()).mapNotNull { j ->
                    val m = rArr.optJSONObject(j) ?: return@mapNotNull null
                    MemMsg(m.optString("role"), m.optString("content"), m.optLong("ts"))
                } else emptyList()
                MemContact(c.optString("jid"), c.optString("name"), c.optString("number"), c.optString("lid"),
                    c.optInt("msgCount"), c.optLong("lastTs"), c.optString("lang"), recent)
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Clear memory for one contact, or all when jid is blank. */
    suspend fun clearAiMemory(jid: String): Boolean = withContext(Dispatchers.IO) {
        try { post("/aimemory/clear", JSONObject().put("jid", jid)).optBoolean("ok", false) } catch (e: Exception) { false }
    }

    suspend fun setRules(rules: List<Rule>): Settings = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        rules.forEach { arr.put(JSONObject().put("match", it.match).put("reply", it.reply).put("mode", it.mode)) }
        parseSettings(post("/settings", JSONObject().put("autoReplyRules", arr)))
    }

    suspend fun getDeleted(): List<Msg> = withContext(Dispatchers.IO) {
        try { parseMsgs(get("/deleted")) } catch (e: Exception) { emptyList() }
    }

    suspend fun getMessages(): List<Msg> = withContext(Dispatchers.IO) {
        try { parseMsgs(get("/messages")) } catch (e: Exception) { emptyList() }
    }

    /**
     * FULL CHAT LIST (BUG FIX: the app built the chat list ONLY from
     * messages — chats with no recent messages, or whose messages fell out
     * of the gateway's log cap, never appeared, so AyX showed fewer chats
     * than the original WhatsApp). The gateway now returns every chat
     * Baileys knows (1:1 + groups, incl. no-message chats) via /chats.
     */
    data class ChatInfo(
        val jid: String, val name: String, val chatName: String,
        val lastTs: Long, val lastText: String, val lastFromMe: Boolean,
        val lastMediaType: String, val archived: Boolean, val muted: Boolean,
        /** Baileys unreadCount (incoming messages the user hasn't read). */
        val unreadCount: Int = 0,
    )

    suspend fun getChats(): List<ChatInfo> = withContext(Dispatchers.IO) {
        try {
            val arr = get("/chats").optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatInfo(
                    o.optString("jid"), o.optString("name"), o.optString("chatName"),
                    o.optLong("ts", o.optLong("lastTs", 0L)), o.optString("lastText"),
                    o.optBoolean("lastFromMe", false), o.optString("lastMediaType"),
                    o.optBoolean("archived", false), o.optBoolean("muted", false),
                    o.optInt("unreadCount", 0),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Mark a chat read (home unread badge clears): zeroes the gateway's
     * Baileys unreadCount for [jid] + sends WhatsApp read receipts for its
     * recent incoming messages. Best-effort — the local badge clears
     * immediately via clearedUnread regardless.
     */
    suspend fun markRead(jid: String): Boolean = withContext(Dispatchers.IO) {
        try { post("/markread", JSONObject().put("jid", jid)).optBoolean("ok", false) }
        catch (e: Exception) { false }
    }

    /**
     * Per-chat history page (ChatDetail "load older"): messages of [jid]
     * older than [before] (0 = newest page), newest-first, max 500.
     * The global /messages feed only carries the newest 200 across ALL
     * chats, so a long chat's older history must be paged this way —
     * otherwise scrolling up hits a hard end and "stops".
     */
    suspend fun getChatMessages(jid: String, before: Long = 0, limit: Int = 200): List<Msg> = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(jid, "UTF-8")
            parseMsgs(get("/messages?chat=$enc&before=$before&limit=$limit"))
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Fetch a group's subject (group name) from Baileys group metadata.
     * Result is cached in [GroupNameStore] (subjects never overwrite a user
     * alias). Throttled to at most 1 attempt / 60s per group so the chat
     * list never spams the gateway while the socket is down. Returns null
     * when the name is unknown (caller shows the "Group" fallback).
     */
    suspend fun getGroupName(jid: String): String? = withContext(Dispatchers.IO) {
        GroupNameStore.nameFor(jid)?.let { return@withContext it }
        if (!GroupNameStore.shouldFetch(jid)) return@withContext null
        GroupNameStore.markAttempted(jid)
        try {
            val enc = java.net.URLEncoder.encode(jid, "UTF-8")
            val o = get("/groupname?jid=$enc")
            val name = o.optString("name").ifEmpty { null }
            if (name != null) GroupNameStore.putSubject(jid, name)
            // v164: the same response carries the member count
            // (gateway captured it from the same groupMetadata call).
            val pc = o.optInt("participants", 0)
            if (pc > 0) GroupNameStore.putCount(jid, pc)
            name
        } catch (e: Exception) { null }
    }

    /**
     * v164: group member count for the chat info page. Served from
     * [GroupNameStore] when a previous /groupname fetch already stored
     * it; otherwise triggers one fetch via [getGroupName] (same 60s
     * throttle) and reads the stored value back. 0 = unknown — the
     * info page then keeps its plain "Group" subtitle.
     */
    suspend fun getGroupMemberCount(jid: String): Int = withContext(Dispatchers.IO) {
        GroupNameStore.countFor(jid).takeIf { it > 0 }?.let { return@withContext it }
        runCatching { getGroupName(jid) }
        GroupNameStore.countFor(jid)
    }

    data class StatusItem(val sender: String, val name: String, val text: String,
        val mediaName: String? = null, val mediaType: String? = null, val thumb: String? = null, val ts: Long = 0L, val mine: Boolean = false, val id: String? = null, val bg: String? = null,
        // SD2: tombstone — this status was deleted on WhatsApp (gateway sends
        // sender + ts but no media). Rendered as "Status Deleted", never played.
        val deleted: Boolean = false)
    /** (ok, error) — ok=true ONLY when the gateway confirms the revoke was sent to WhatsApp. */
    suspend fun deleteStatus(id: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val o = post("/status/delete", JSONObject().put("id", id))
        o.optBoolean("ok", false) to o.optString("error", "")
    }
    // who viewed my status (jid to display name)
    data class StatusViewer(val jid: String, val lid: String, val name: String, val ts: Long)
    suspend fun getStatusViewers(ids: List<String>): List<StatusViewer> = withContext(Dispatchers.IO) {
        try {
            if (ids.isEmpty()) return@withContext emptyList()
            val idParam = java.net.URLEncoder.encode(ids.joinToString(","), "UTF-8")
            val arr = get("/status/viewers?id=$idParam").optJSONArray("viewers") ?: return@withContext emptyList()
            (0 until arr.length()).map { val o = arr.getJSONObject(it); StatusViewer(o.optString("jid"), o.optString("lid"), o.optString("name"), o.optLong("ts", 0L)) }
        } catch (e: Exception) { emptyList() }
    }
    suspend fun getMe(): String = withContext(Dispatchers.IO) {
        try { get("/me").optString("jid") } catch (e: Exception) { "" }
    }
    suspend fun getStatuses(): List<StatusItem> = withContext(Dispatchers.IO) {
        try {
            val arr = get("/statuses").optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val md = o.optJSONObject("media")
                StatusItem(
                    sender = o.optString("sender"),
                    name = o.optString("name").ifEmpty { if (o.optString("sender").endsWith("@lid")) "" else o.optString("sender").substringBefore("@") },
                    text = o.optString("text"),
                    mediaName = md?.optString("name")?.ifEmpty { null },
                    mediaType = md?.optString("type")?.ifEmpty { null },
                    thumb = md?.optString("thumb")?.ifEmpty { null },
                    ts = o.optLong("ts", 0L),
                    mine = o.optBoolean("mine", false),
                    id = o.optString("id").ifEmpty { null },
                    bg = o.optString("bg").ifEmpty { null },
                    deleted = o.optBoolean("deleted", false),
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    data class Presence(val online: Boolean, val lastSeen: Long)
    suspend fun getPresence(jid: String): Presence = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(jid, "UTF-8")
            val o = get("/presence?jid=$enc")
            Presence(o.optString("presence") == "available", o.optLong("lastSeen", 0L))
        } catch (e: Exception) { Presence(false, 0L) }
    }

    suspend fun dpBytes(jid: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val enc = java.net.URLEncoder.encode(jid, "UTF-8")
            val c = (URL("$base/dp?jid=$enc").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 4000; readTimeout = 15000
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    suspend fun mediaBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$base/media/$name").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 4000; readTimeout = 20000
                GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
            }
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    private fun parseSettings(o: JSONObject): Settings {
        val rules = mutableListOf<Rule>()
        o.optJSONArray("autoReplyRules")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONObject(i)
                rules.add(Rule(r.optString("match"), r.optString("reply"), r.optString("mode", "contains")))
            }
        }
        return Settings(
            alwaysOnline = o.optBoolean("alwaysOnline", false),
            autoRead = o.optBoolean("autoRead", false),
            autoReplyEnabled = o.optBoolean("autoReplyEnabled", false),
            rules = rules,
            aiReplyEnabled = o.optBoolean("aiReplyEnabled", false),
            groupAiEnabled = o.optBoolean("groupAiEnabled", false),
            aiApiUrl = o.optString("aiApiUrl", ""),
            aiApiKey = o.optString("aiApiKey", ""),
            aiModel = o.optString("aiModel", ""),
            hideStatusRead = o.optBoolean("hideStatusRead", true),
            aiSystemPrompt = o.optString("aiSystemPrompt", ""),
            saveMedia = o.optBoolean("saveMedia", false),
            stayOffline = o.optBoolean("stayOffline", false),
            aiPresenceFlow = o.optBoolean("aiPresenceFlow", false),
            aiExcludeJids = o.optJSONArray("aiExcludeJids")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList(),
            aiReplyVoice = o.optBoolean("aiReplyVoice", false),
            aiReplyImage = o.optBoolean("aiReplyImage", false),
            aiVisionModel = o.optString("aiVisionModel", ""),
            aiVisionApiUrl = o.optString("aiVisionApiUrl", ""),
            aiVisionApiKey = o.optString("aiVisionApiKey", ""),
            aiVisionModel2 = o.optString("aiVisionModel2", ""),
            aiVisionApiUrl2 = o.optString("aiVisionApiUrl2", ""),
            aiVisionApiKey2 = o.optString("aiVisionApiKey2", ""),
            aiImageOcr = o.optBoolean("aiImageOcr", false),
            aiImageFree = o.optBoolean("aiImageFree", true),
            aiCommandsEnabled = o.optBoolean("aiCommandsEnabled", true),
            aiVoiceNoteReply = o.optBoolean("aiVoiceNoteReply", false),
            aiTtsVoice = o.optString("aiTtsVoice", "Arista-PlayAI"),
            aiSarvamKey = o.optString("aiSarvamKey", ""),
            aiLangMode = o.optString("aiLangMode", "auto"),
            aiFullContext = o.optBoolean("aiFullContext", true),
            aiReplyLang = o.optString("aiReplyLang", ""),
            aiLyricsApis = o.optJSONArray("aiLyricsApis")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    LyricApi(e.optString("name"), e.optString("url"), e.optString("key"), e.optString("model"))
                }
            } ?: emptyList(),
        )
    }

    private fun parseMsgs(o: JSONObject): List<Msg> {
        val out = mutableListOf<Msg>()
        o.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                val md = m.optJSONObject("media")
                out.add(Msg(
                    m.optString("chat"), m.optString("name"), m.optBoolean("fromMe", false), m.optString("text"), m.optLong("ts"),
                    mediaName = md?.optString("name")?.ifEmpty { null },
                    mediaType = md?.optString("type")?.ifEmpty { null },
                    thumb = md?.optString("thumb")?.ifEmpty { null },
                    deleted = m.optBoolean("deleted", false),
                    id = m.optString("id").ifEmpty { null },
                    reaction = m.optString("reaction").ifEmpty { null },
                    sender = m.optString("sender").ifEmpty { null },
                    quotedText = m.optJSONObject("quoted")?.optString("text")?.ifEmpty { null },
                    quotedId = m.optJSONObject("quoted")?.optString("id")?.ifEmpty { null },
                    edited = m.optBoolean("edited", false),
                    status = m.optInt("status", 0),
                    forwarded = m.optBoolean("forwarded", false),
                    chatName = m.optString("chatName").ifEmpty { null },
                    albumId = m.optString("albumId").ifEmpty { null },
                ))
            }
        }
        return out
    }

    private fun get(path: String): JSONObject {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 3000; readTimeout = 8000
            // SECURITY: Gateway API authentication. Token is per-installation,
            // never logged, never in URL. Gateway rejects requests without it.
            GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
        }
        return readJson(c)
    }

    private fun post(path: String, body: JSONObject, readTimeoutMs: Int = 20000): JSONObject {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 3000; readTimeout = readTimeoutMs
            setRequestProperty("Content-Type", "application/json")
            GatewayAuth.token()?.let { setRequestProperty("X-Api-Token", it) }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return readJson(c)
    }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return try {
            JSONObject(if (text.isBlank()) "{}" else text)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", text.take(180))
        }
    }
}
