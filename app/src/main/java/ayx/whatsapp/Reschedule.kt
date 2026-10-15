package ayx.whatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Smart AI reminders (Feature A) — shared scheduling core.
 *
 * Design constraints (do not loosen without the user's explicit say-so):
 * - The chat composer / chat box is FROZEN (user instruction 2026-10-15). Nothing here
 *   touches MainActivity.kt, the composer UI, onSend, or the optimistic message list.
 * - .vv / SarvamTts is FROZEN. This file never calls it.
 * - No build.gradle.kts / AndroidManifest.xml changes: scheduling uses the already-present
 *   WorkManager (work-runtime-ktx), persistence uses SharedPreferences (existing pattern),
 *   HTTP uses HttpURLConnection (same as GatewayClient). Timing is WorkManager-inexact —
 *   Android does not guarantee exact background execution.
 *
 * Trigger: the user types a natural reminder request in any chat, e.g.
 * "mujhe kal subah 8 baje good morning message karna". There is NO command
 * prefix (user instruction 2026-10-15). It is intercepted in
 * [GatewayClient.sendToJid] (delivery layer, NOT the composer), so the
 * composer UI is untouched. A fast local heuristic pre-filters candidates;
 * the AI's structured extraction makes the final call, and anything doubtful
 * fails open (the message sends normally).
 *
 * Flow:
 *  1. [handleOutgoing] runs the heuristic; for candidates it asks the AI to
 *     extract structured data synchronously (the swallow-vs-send decision must
 *     happen before delivery), persists the task, enqueues
 *     [RescheduleFireWorker], and posts a confirmation notification
 *     ("Theek hai! ..."). The notification stands in for an in-chat reply
 *     because injecting chat messages would require MainActivity changes.
 *  3. The fire worker re-verifies state atomically, asks the AI to generate the
 *     message text, and delivers it via [GatewayClient.sendToJidDirect] — the
 *     existing authorized delivery mechanism. SENT is marked only on confirmed
 *     delivery; duplicates are impossible by construction (atomic claim).
 */
object Reschedule {

    // ------------------------------------------------------------------ model

    /** Task kinds. MANUAL_MESSAGE is reserved for a future manual scheduler UI. */
    enum class RescheduleType { AI_GENERATED_MESSAGE, MANUAL_MESSAGE }

    /** Lifecycle. Only the five suggested states are persisted. */
    enum class RescheduleStatus { PENDING, PROCESSING, SENT, FAILED, CANCELLED }

    data class RescheduleTask(
        val id: String,
        val type: RescheduleType,
        /** WhatsApp JID of the conversation where the request was made = delivery destination. */
        val jid: String,
        /** Original user request text (the natural-language message). */
        val request: String,
        /** AI-extracted short description of what to do. Empty until extracted. */
        val action: String,
        /** Exact text for MANUAL_MESSAGE. Unused by AI_GENERATED_MESSAGE. */
        val messageText: String,
        /** Epoch millis when the reminder should fire. 0 = not yet extracted. */
        val scheduledAt: Long,
        /** Device local timezone id at creation, e.g. "Asia/Kolkata". */
        val timezoneId: String,
        val createdAt: Long,
        val status: RescheduleStatus,
        val retryCount: Int,
        val lastError: String,
        /** True once the extract worker filled in scheduledAt/action. */
        val extracted: Boolean,
    )

    // -------------------------------------------------------------- persistence

    private const val PREFS = "reschedule"
    private const val KEY_TASKS = "tasks_v1"
    private val lock = Any()

    @Volatile private var appCtx: Context? = null

    /** Called once from [NodeRuntime.ensureStarted] (app startup, not the chat box). */
    fun init(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    private fun prefs(): android.content.SharedPreferences? =
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun taskToJson(t: RescheduleTask): JSONObject = JSONObject()
        .put("id", t.id)
        .put("type", t.type.name)
        .put("jid", t.jid)
        .put("request", t.request)
        .put("action", t.action)
        .put("messageText", t.messageText)
        .put("scheduledAt", t.scheduledAt)
        .put("timezoneId", t.timezoneId)
        .put("createdAt", t.createdAt)
        .put("status", t.status.name)
        .put("retryCount", t.retryCount)
        .put("lastError", t.lastError)
        .put("extracted", t.extracted)

    private fun taskFromJson(o: JSONObject): RescheduleTask? = try {
        RescheduleTask(
            id = o.getString("id"),
            type = RescheduleType.valueOf(o.optString("type", "AI_GENERATED_MESSAGE")),
            jid = o.optString("jid", ""),
            request = o.optString("request", ""),
            action = o.optString("action", ""),
            messageText = o.optString("messageText", ""),
            scheduledAt = o.optLong("scheduledAt", 0L),
            timezoneId = o.optString("timezoneId", TimeZone.getDefault().id),
            createdAt = o.optLong("createdAt", 0L),
            status = RescheduleStatus.valueOf(o.optString("status", "PENDING")),
            retryCount = o.optInt("retryCount", 0),
            lastError = o.optString("lastError", ""),
            extracted = o.optBoolean("extracted", false),
        )
    } catch (_: Exception) { null }

    /** Read-modify-write of the whole task map under one lock; commit() for durability. */
    private fun mutate(fn: (MutableMap<String, RescheduleTask>) -> Unit) {
        val p = prefs() ?: return
        synchronized(lock) {
            val raw = p.getString(KEY_TASKS, "{}") ?: "{}"
            val map = mutableMapOf<String, RescheduleTask>()
            try {
                val jo = JSONObject(raw)
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    taskFromJson(jo.optJSONObject(k) ?: continue)?.let { map[k] = it }
                }
            } catch (_: Exception) { /* corrupt store: start fresh rather than crash */ }
            fn(map)
            val out = JSONObject()
            for ((k, v) in map) out.put(k, taskToJson(v))
            p.edit().putString(KEY_TASKS, out.toString()).commit()
        }
    }

    fun save(task: RescheduleTask) = mutate { it[task.id] = task }

    fun get(id: String): RescheduleTask? {
        val p = prefs() ?: return null
        synchronized(lock) {
            return try {
                val jo = JSONObject(p.getString(KEY_TASKS, "{}") ?: "{}")
                taskFromJson(jo.optJSONObject(id) ?: return null)
            } catch (_: Exception) { null }
        }
    }

    fun all(): List<RescheduleTask> {
        val p = prefs() ?: return emptyList()
        synchronized(lock) {
            return try {
                val jo = JSONObject(p.getString(KEY_TASKS, "{}") ?: "{}")
                val out = mutableListOf<RescheduleTask>()
                val keys = jo.keys()
                while (keys.hasNext()) {
                    taskFromJson(jo.optJSONObject(keys.next()) ?: continue)?.let { out.add(it) }
                }
                out.sortedByDescending { it.createdAt }
            } catch (_: Exception) { emptyList() }
        }
    }

    fun update(id: String, fn: (RescheduleTask) -> RescheduleTask) = mutate { m ->
        m[id]?.let { m[id] = fn(it) }
    }

    /**
     * Atomically claim a task for firing: PENDING -> PROCESSING.
     * Only the worker that wins the claim may send. Returns false when the task
     * is missing, not extracted, already claimed, cancelled, sent or failed —
     * in which case the caller must NOT send (duplicate prevention).
     */
    fun atomicClaim(id: String): Boolean {
        val p = prefs() ?: return false
        synchronized(lock) {
            val raw = p.getString(KEY_TASKS, "{}") ?: "{}"
            val jo = try { JSONObject(raw) } catch (_: Exception) { return false }
            val cur = taskFromJson(jo.optJSONObject(id) ?: return false) ?: return false
            if (cur.status != RescheduleStatus.PENDING || !cur.extracted || cur.scheduledAt <= 0L) return false
            jo.put(id, taskToJson(cur.copy(status = RescheduleStatus.PROCESSING)))
            p.edit().putString(KEY_TASKS, jo.toString()).commit()
            return true
        }
    }

    /** Release a claim after a transient failure so a later retry can claim again. */
    fun releaseClaim(id: String) = mutate { m ->
        m[id]?.let { if (it.status == RescheduleStatus.PROCESSING) m[id] = it.copy(status = RescheduleStatus.PENDING) }
    }

    fun newId(): String = UUID.randomUUID().toString()

    // ------------------------------------------------------- candidate detection
    // Pure logic (no Android APIs) — covered by unit tests.
    //
    // There is deliberately NO command prefix (user instruction 2026-10-15).
    // Every outgoing message passes this fast local heuristic; only candidates
    // are sent to the AI for a verdict. The heuristic is a pre-filter, never
    // the decider — the AI's structured extraction confirms a reminder, and
    // anything doubtful fails open (the message sends normally).

    private val REMIND_VERB = Regex(
        "\\b(remind|reminder|yaad\\s+dila|message\\s+kar|msg\\s+kar|message\\s+bhej|msg\\s+bhej|bhej\\s+de|send\\s+kar)\\w*",
        RegexOption.IGNORE_CASE
    )
    private val TIME_REF = Regex(
        "\\b(kal|parso|aaj|subah|shaam|raat|dopahar|baje|tomorrow|today|morning|evening|night|o'clock|ghante?|hours?|minutes?|seconds?)\\b" +
            "|\\b\\d{1,2}\\s*:\\s*\\d{2}\\b|\\b\\d+\\s*(am|pm)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SELF_REF = Regex(
        "\\b(mujhe|mujhko|mujko|merko|mere\\s+ko|myself|me)\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * Fast local pre-filter: true when a remind-verb is present AND (a time
     * reference OR a self-reference like "mujhe") is present. The AI makes
     * the final call.
     */
    fun isReminderCandidate(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return REMIND_VERB.containsMatchIn(t) &&
            (TIME_REF.containsMatchIn(t) || SELF_REF.containsMatchIn(t))
    }

    // ------------------------------------------------- Hindi-friendly formatting
    // Pure logic (java.util only — safe on minSdk 24) — covered by unit tests.

    private fun dayPeriod(hour24: Int): String = when (hour24) {
        in 5..11 -> "subah"
        in 12..15 -> "dopahar"
        in 16..18 -> "shaam"
        else -> "raat"
    }

    /**
     * "kal raat 9 baje", "aaj shaam 6:30 baje", "17 Oct ko subah 8 baje".
     * Uses the device default timezone, matching how the task was scheduled.
     */
    fun formatScheduleHin(scheduledAt: Long, nowMs: Long = System.currentTimeMillis()): String {
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz).apply { timeInMillis = scheduledAt }
        val nowCal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val dayPart = when {
            cal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR) -> "aaj"
            run {
                val tom = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                cal.get(Calendar.YEAR) == tom.get(Calendar.YEAR) &&
                    cal.get(Calendar.DAY_OF_YEAR) == tom.get(Calendar.DAY_OF_YEAR)
            } -> "kal"
            else -> SimpleDateFormat("d MMM", Locale.US).apply { timeZone = tz }.format(cal.time) + " ko"
        }
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val min = cal.get(Calendar.MINUTE)
        val timePart = if (min == 0) "$h12" else String.format(Locale.US, "%d:%02d", h12, min)
        return "$dayPart ${dayPeriod(h24)} $timePart baje"
    }

    /** "2026-10-16 21:00" in device local time — for notification detail lines. */
    fun formatDateTime(scheduledAt: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(scheduledAt)

    // ------------------------------------------- tolerant LLM-JSON extraction
    // Pure logic — covered by unit tests. LLMs sometimes wrap JSON in fences or
    // add prose; this extracts the known flat fields without org.json so the
    // parsing stays unit-testable on plain JUnit.

    data class ExtractionResult(
        val hasReminder: Boolean,
        /** "YYYY-MM-DDTHH:MM" in device local time, or "" when absent. */
        val datetime: String,
        val action: String,
        val confidence: Double,
        val clarification: String,
    )

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substring(3)
            val nl = t.indexOf('\n')
            if (nl >= 0) t = t.substring(nl + 1)
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t.trim()
    }

    private fun jsonStringField(src: String, key: String): String {
        // "key" : "value"  (handles escaped quotes minimally)
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(src)
        return m?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
    }

    private fun jsonBoolField(src: String, key: String): Boolean {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(true|false)").find(src)
        return m?.groupValues?.get(1) == "true"
    }

    private fun jsonDoubleField(src: String, key: String): Double {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").find(src)
        return m?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    /** Parse the extractor LLM's JSON. Null = unusable output (caller retries). */
    fun parseExtractionJson(raw: String): ExtractionResult? {
        if (raw.isBlank()) return null
        val src = stripFences(raw)
        if (!src.contains("\"has_reminder\"")) return null
        return ExtractionResult(
            hasReminder = jsonBoolField(src, "has_reminder"),
            datetime = jsonStringField(src, "datetime"),
            action = jsonStringField(src, "action"),
            confidence = jsonDoubleField(src, "confidence"),
            clarification = jsonStringField(src, "clarification"),
        )
    }

    private val datetimeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { isLenient = false }
    }

    /**
     * Validate the extracted datetime: parses as device-local time and must be
     * at least [minAheadMs] in the future. Returns epoch millis or null.
     */
    fun validateFutureDatetime(datetime: String, nowMs: Long, minAheadMs: Long = 60_000L): Long? {
        if (datetime.isBlank()) return null
        return try {
            val fmt = datetimeFmt.get()!!.apply { timeZone = TimeZone.getDefault() }
            val ms = fmt.parse(datetime)?.time ?: return null
            if (ms >= nowMs + minAheadMs) ms else null
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------- AI client
    // Reuses the user's configured AI (aiApiUrl/aiApiKey/aiModel) with the exact
    // OpenAI-compatible chat/completions contract the Node gateway uses
    // (see chatComplete in nodejs-project/main.js). No invented endpoints.

    private fun chatCompletions(
        apiUrl: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        attempts: Int = 3,
    ): String? {
        var lastErr: Exception? = null
        for (attempt in 1..attempts) {
            try {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", org.json.JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("temperature", 0.7)
                    .toString()
                val c = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 10_000; readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) {
                    val content = JSONObject(text)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content", "")?.trim()
                    if (!content.isNullOrEmpty()) return content
                } else if (code == 429 || code >= 500) {
                    // transient — fall through to retry with backoff
                } else {
                    return null // 4xx (bad key/model): retrying won't help
                }
            } catch (e: Exception) { lastErr = e }
            if (attempt < attempts) try { Thread.sleep(1200L * attempt) } catch (_: InterruptedException) { return null }
        }
        lastErr?.let { android.util.Log.w("Reschedule", "ai failed: ${it.message}") }
        return null
    }

    private fun extractionSystemPrompt(nowLocal: String, tzId: String): String =
        "You extract reminder scheduling details from a user request. Current local time: $nowLocal ($tzId). " +
            "Respond with ONLY a JSON object, no markdown fences, no explanation: " +
            "{\"has_reminder\": true, \"datetime\": \"YYYY-MM-DDTHH:MM\", \"action\": \"short description\", " +
            "\"confidence\": 0.0-1.0, \"clarification\": \"\"}. " +
            "Rules: datetime is the future local time the reminder should fire, 24-hour clock. " +
            "Understand Hindi, Hinglish and English relative times (kal, aaj, subah/shaam/raat, tomorrow, in 2 hours, etc.). " +
            "If there is no clear future time, set has_reminder=false, confidence below 0.6, and put a short " +
            "clarification question (Hindi/Hinglish ok) in \"clarification\"."

    /** Ask the AI to extract structured reminder data. Null = transport failure (caller retries). */
    fun aiExtract(apiUrl: String, apiKey: String, model: String, request: String): ExtractionResult? {
        val tz = TimeZone.getDefault()
        val nowLocal = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = tz }.format(System.currentTimeMillis())
        val raw = chatCompletions(apiUrl, apiKey, model, extractionSystemPrompt(nowLocal, tz.id), request)
            ?: return null
        return parseExtractionJson(raw)
    }

    private fun generationSystemPrompt(request: String, action: String): String =
        "Generate the short reminder/message the user asked to receive. Original request: \"$request\". " +
            (if (action.isNotBlank()) "Action: $action. " else "") +
            "Write 1-2 warm, casual lines in the same language as the request (Hindi/Hinglish/English as appropriate). " +
            "Return ONLY the message text — no quotes, no preamble, no explanation."

    /** Ask the AI to generate the actual reminder message text. Null = failure. */
    fun aiGenerate(apiUrl: String, apiKey: String, model: String, request: String, action: String): String? {
        val out = chatCompletions(apiUrl, apiKey, model, generationSystemPrompt(request, action), "Generate it now.")
            ?.trim().orEmpty()
        if (out.isEmpty() || out.length > 1000) return null
        return out
    }

    // ------------------------------------------------------------ notifications

    private const val CHANNEL_REMINDERS = "reminders"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_REMINDERS) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }

    private fun notifIcon(ctx: Context): Int =
        ctx.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    fun notifySimple(ctx: Context, title: String, body: String, tag: String = "reschedule") {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_REMINDERS)
            .setSmallIcon(notifIcon(ctx))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(tag, tag.hashCode(), n)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
    }

    // ------------------------------------------------------- outgoing interception
    // Called from GatewayClient.sendToJid (delivery layer). The composer is NOT
    // involved: the user types normally, hits send normally. Returns true when
    // the text was consumed as a reminder (message must NOT be sent).
    //
    // No command prefix: every outgoing message passes the fast local
    // heuristic; only candidates reach the AI. The AI's verdict is synchronous
    // here because the swallow-vs-send decision must happen before delivery.
    // Fail-open everywhere: any doubt or failure → the message sends normally.

    /**
     * Entry point for outgoing text. Returns true if [text] was consumed as a
     * reminder and must not be delivered as a WhatsApp message.
     */
    suspend fun handleOutgoing(jid: String, text: String): Boolean {
        val ctx = appCtx ?: return false // not initialized: fail open, send normally
        if (!isReminderCandidate(text)) return false
        GatewayAuth.init(ctx)
        val settings = try {
            GatewayClient.getSettings()
        } catch (_: Exception) { return false } // gateway not up: fail open
        val apiUrl = settings.aiApiUrl.trim()
        val apiKey = settings.aiApiKey.trim()
        if (apiUrl.isEmpty() || apiKey.isEmpty()) return false // no AI configured: send normally
        val model = settings.aiModel.trim().ifEmpty { "openai/gpt-oss-20b" }
        val ext = try {
            aiExtract(apiUrl, apiKey, model, text)
        } catch (_: Exception) { null } ?: return false // AI failure: fail open
        if (!ext.hasReminder || ext.confidence < 0.75) return false // not a reminder: send normally
        val now = System.currentTimeMillis()
        val fireAt = validateFutureDatetime(ext.datetime, now)
        if (fireAt == null) {
            // Confident it's a reminder but the time is unclear: do NOT deliver
            // a confusing message; ask the user to rephrase with a clear time.
            notifySimple(ctx, "Time clear karo",
                "Reminder samajh aaya, par time clear nahi tha. Aise bhejo: \"kal raat 9 baje good morning message karna\"")
            return true
        }
        val action = ext.action.ifBlank { text.take(80) }
        val task = RescheduleTask(
            id = newId(),
            type = RescheduleType.AI_GENERATED_MESSAGE,
            jid = jid,
            request = text,
            action = action,
            messageText = "",
            scheduledAt = fireAt,
            timezoneId = TimeZone.getDefault().id,
            createdAt = now,
            status = RescheduleStatus.PENDING,
            retryCount = 0,
            lastError = "",
            extracted = true,
        )
        save(task)
        enqueueFire(ctx, task.id, fireAt - now)
        notifySimple(ctx, "Reminder set",
            "Theek hai! Main ${formatScheduleHin(fireAt, now)} tumhe yaad dilaunga.\n“$action”",
            tag = "reschedule_${task.id}")
        return true
    }

    // ---------------------------------------------------------------- scheduling

    private fun fireWorkName(id: String) = "reschedule-fire-$id"

    fun enqueueFire(ctx: Context, taskId: String, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<RescheduleFireWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("taskId" to taskId))
            .addTag("reschedule")
            .build()
        // KEEP: a fire worker already queued for this task wins (duplicate prevention).
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            fireWorkName(taskId), ExistingWorkPolicy.KEEP, req
        )
    }

    // ------------------------------------------------------------------ workers

    /**
     * At the scheduled time, claim the task atomically,
     * ask the AI to generate the message text, and deliver it through the
     * existing authorized pipeline ([GatewayClient.sendToJidDirect]).
     *
     * Duplicate-send prevention: only the worker that wins [atomicClaim] sends.
     * A retry that finds the task already PROCESSING never resends — it marks
     * FAILED and notifies, because delivery state is unknown (truthful status).
     */
    class RescheduleFireWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val taskId = inputData.getString("taskId") ?: return Result.failure()
            val ctx = applicationContext
            GatewayAuth.init(ctx)
            val claimed = Reschedule.atomicClaim(taskId)
            if (!claimed) {
                val cur = Reschedule.get(taskId)
                if (cur?.status == RescheduleStatus.PROCESSING && runAttemptCount > 0) {
                    // A previous attempt died mid-send: delivery state is unknown.
                    // Never resend (duplicate prevention); report honestly.
                    Reschedule.update(taskId) {
                        it.copy(status = RescheduleStatus.FAILED, lastError = "interrupted during send")
                    }
                    Reschedule.notifySimple(ctx, "Reminder status unknown",
                        "“${cur.action.ifBlank { cur.request.take(50) }}” bhejte waqt app band ho gaya tha. " +
                            "Chat me check kar lo — duplicate na ho isliye dobara nahi bheja.")
                }
                return Result.success() // cancelled / sent / failed meanwhile: nothing to do
            }
            val task = Reschedule.get(taskId) ?: return Result.failure()
            val settings = try {
                GatewayClient.getSettings()
            } catch (_: Exception) {
                return if (runAttemptCount < 5) {
                    Reschedule.releaseClaim(taskId)
                    Result.retry()
                } else {
                    failTask(ctx, taskId, task, "gateway unreachable")
                    Result.failure()
                }
            }
            val apiUrl = settings.aiApiUrl.trim()
            val apiKey = settings.aiApiKey.trim()
            if (apiUrl.isEmpty() || apiKey.isEmpty()) {
                failTask(ctx, taskId, task, "AI not configured")
                return Result.failure()
            }
            val model = settings.aiModel.trim().ifEmpty { "openai/gpt-oss-20b" }
            val generated = try {
                Reschedule.aiGenerate(apiUrl, apiKey, model, task.request, task.action)
            } catch (_: Exception) { null }
            if (generated.isNullOrBlank()) {
                return if (runAttemptCount < 5) {
                    Reschedule.releaseClaim(taskId)
                    Result.retry()
                } else {
                    failTask(ctx, taskId, task, "AI generation failed")
                    Result.failure()
                }
            }
            val sent = try {
                GatewayClient.sendToJidDirect(task.jid, generated)
            } catch (_: Exception) { false }
            return if (sent) {
                Reschedule.update(taskId) { it.copy(status = RescheduleStatus.SENT) }
                Result.success()
            } else {
                if (runAttemptCount < 5) {
                    Reschedule.releaseClaim(taskId)
                    Result.retry()
                } else {
                    failTask(ctx, taskId, task, "send failed")
                    Result.failure()
                }
            }
        }

        private fun failTask(ctx: Context, taskId: String, task: RescheduleTask, reason: String) {
            Reschedule.update(taskId) {
                it.copy(status = RescheduleStatus.FAILED, lastError = reason, retryCount = it.retryCount + 1)
            }
            Reschedule.notifySimple(ctx, "Reminder nahi bheja ja saka",
                "“${task.action.ifBlank { task.request.take(50) }}” — wajah: $reason. " +
                    "Task saved hai; dobara try karo ya AI se poochho.")
        }
    }
}
