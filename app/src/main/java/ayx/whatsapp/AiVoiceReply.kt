package ayx.whatsapp

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F2: AI voice-note replies via Android on-device TextToSpeech (no API key, free).
 *
 * Flow: Node generates the AI reply text and queues it (GET /voicereplies/pending).
 * This drains the queue (called from NodeService's background poll loop, so it works
 * even when the UI is dead), synthesizes each reply with Android TTS, and POSTs the
 * audio to /sendvoicenote (ptt:true). If TTS fails, the reply goes as plain TEXT —
 * a reply is never dropped. Temp files are always deleted after the send attempt.
 */
object AiVoiceTts {
    private const val TAG = "AiVoiceTts"

    /** Synthesize [text] to a WAV file in the app cache dir. Returns null on any failure. */
    suspend fun synthesizeToFile(ctx: Context, text: String, preferHindi: Boolean): File? = withContext(Dispatchers.IO) {
        val clean = text.trim().take(600)
        if (clean.isEmpty()) return@withContext null
        var tts: TextToSpeech? = null
        try {
            // Init TTS (blocking with timeout — TTS init is async).
            val initLatch = CountDownLatch(1)
            val initOk = AtomicBoolean(false)
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) initOk.set(true)
                initLatch.countDown()
            }
            if (!initLatch.await(10, TimeUnit.SECONDS) || !initOk.get()) return@withContext null
            val engine = tts ?: return@withContext null

            // Language: Hindi if the AI reply mode suggests it and the engine has it, else device default.
            val defaultLocale = Locale.getDefault()
            var locale = defaultLocale
            if (preferHindi) {
                val hindi = Locale("hi", "IN")
                val avail = engine.isLanguageAvailable(hindi)
                if (avail >= TextToSpeech.LANG_AVAILABLE) locale = hindi
            }
            if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
                locale = Locale.ENGLISH
                if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) return@withContext null
            }
            engine.language = locale
            // Slightly slower + lower pitch reads more naturally for chat replies.
            engine.setSpeechRate(0.95f)
            engine.setPitch(0.95f)

            val out = File(ctx.cacheDir, "tts_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.wav")
            val doneLatch = CountDownLatch(1)
            val synthOk = AtomicBoolean(false)
            val utterId = UUID.randomUUID().toString()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { synthOk.set(true); doneLatch.countDown() }
                override fun onError(utteranceId: String?) { doneLatch.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { doneLatch.countDown() }
            })
            val rc = engine.synthesizeToFile(clean, null, out, utterId)
            if (rc != TextToSpeech.SUCCESS) return@withContext null
            if (!doneLatch.await(30, TimeUnit.SECONDS) || !synthOk.get()) return@withContext null
            if (!out.exists() || out.length() == 0L) { runCatching { out.delete() }; return@withContext null }
            out
        } catch (_: Exception) {
            null
        } finally {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
        }
    }

    /**
     * Drain Node's voice-reply queue: TTS each reply and send as a voice note.
     * Falls back to plain text when TTS fails. Safe to call often; no-op when empty.
     */
    suspend fun drainQueue(appCtx: Context) {
        val items = try { GatewayClient.getVoiceReplies() } catch (_: Exception) { return }
        if (items.isEmpty()) return
        // Prefer Hindi TTS voice when the AI reply language mode mentions Hindi.
        val preferHindi = try {
            GatewayClient.getSettings().aiLangMode.lowercase().contains("hindi")
        } catch (_: Exception) { false }
        for (item in items) {
            val (jid, text) = item
            if (jid.isBlank() || text.isBlank()) continue
            var tmp: File? = null
            try {
                tmp = withTimeoutOrNull(45000) { synthesizeToFile(appCtx, text, preferHindi) }
                if (tmp != null) {
                    val b64 = withContext(Dispatchers.IO) {
                        Base64.encodeToString(tmp.readBytes(), Base64.NO_WRAP)
                    }
                    val ok = try { GatewayClient.sendVoiceNote(jid, b64) } catch (_: Exception) { false }
                    if (!ok) {
                        // voice-note send failed — fall back to text so the reply isn't lost
                        runCatching { GatewayClient.sendText(jid, text) }
                    }
                } else {
                    // TTS failed — text fallback (never drop the reply)
                    runCatching { GatewayClient.sendText(jid, text) }
                }
            } catch (_: Exception) {
                runCatching { GatewayClient.sendText(jid, text) }
            } finally {
                tmp?.let { runCatching { it.delete() } }
            }
        }
    }
}
