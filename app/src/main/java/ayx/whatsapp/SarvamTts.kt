package ayx.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sarvam AI Text-to-Speech for .vv voice messages.
 *
 * Usage: user types ".vv <text>" in chat.
 * - Text after ".vv" is converted to speech via Sarvam Bulbul API.
 * - Only the voice audio is sent, the ".vv" text is NEVER sent.
 * - Audio is saved to internal cache, sent, then deleted.
 *
 * Setup: Set your Sarvam API key via SarvamTts.setApiKey(key).
 * Get a key from https://sarvam.ai (dashboard -> API keys).
 */
object SarvamTts {
    private const val TAG = "SarvamTts"
    private const val BASE_URL = "https://api.sarvam.ai/text-to-speech"
    private const val PREFS_NAME = "ayx_ai_voice"
    private const val KEY_API_KEY = "sarvam_api_key"
    private var apiKey: String? = null

    // Default voice settings - Hindi female, natural pace
    var targetLanguageCode: String = "hi-IN"
    var speaker: String = "meera" // Sarvam Bulbul speaker
    var model: String = "bulbul:v2"
    var pace: Double = 1.0
    var pitch: Double = 0.0

    fun setApiKey(key: String) {
        apiKey = key
    }

    /** Save API key to SharedPreferences (from Settings UI). */
    fun saveApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key).apply()
        apiKey = key
    }

    /** Load API key from SharedPreferences. Call once at app start. */
    fun loadApiKey(ctx: Context): String? {
        if (apiKey.isNullOrBlank()) {
            apiKey = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, null)
        }
        return apiKey
    }

    /** Get saved key without context (for Settings UI to display). */
    fun getSavedApiKey(ctx: Context): String {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
    }

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    /**
     * Returns true if the message is a .vv voice command.
     * Format: ".vv <text>" or ".vv<text>"
     */
    fun isVvCommand(text: String): Boolean {
        val t = text.trim()
        return t.startsWith(".vv") && t.length > 3
    }

    /**
     * Extract the text to speak from a .vv command.
     * ".vv Hello world" -> "Hello world"
     */
    fun extractText(command: String): String {
        return command.trim().removePrefix(".vv").trim()
    }

    /**
     * Convert text to speech via Sarvam API.
     * Returns the audio File (WAV) in app cache, or null on failure.
     * Caller is responsible for deleting the file after sending.
     */
    suspend fun textToSpeech(ctx: Context, text: String): File? = withContext(Dispatchers.IO) {
        val key = loadApiKey(ctx)
        if (key.isNullOrBlank()) {
            Log.e(TAG, "Sarvam API key not set. User must set it in Settings > AI Voice.")
            return@withContext null
        }
        if (text.isBlank()) return@withContext null

        try {
            val url = URL(BASE_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("api-subscription-key", key)
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 30000
                readTimeout = 60000
                doOutput = true
            }

            val body = JSONObject().apply {
                put("inputs", arrayOf(text))
                put("target_language_code", targetLanguageCode)
                put("speaker", speaker)
                put("model", model)
                put("pace", pace)
                put("pitch", pitch)
                put("sample_rate", 22050)
            }

            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.e(TAG, "Sarvam TTS failed: HTTP $code $err")
                return@withContext null
            }

            val respText = conn.inputStream.bufferedReader().readText()
            val resp = JSONObject(respText)
            val audios = resp.optJSONArray("audios")
            if (audios == null || audios.length() == 0) {
                Log.e(TAG, "Sarvam TTS: empty audios array")
                return@withContext null
            }

            // Combine base64 chunks and decode
            val combined = StringBuilder()
            for (i in 0 until audios.length()) {
                combined.append(audios.getString(i))
            }
            val wavBytes = Base64.decode(combined.toString(), Base64.DEFAULT)

            // Save to internal cache (auto-cleaned by system, we delete after send anyway)
            val outFile = File(ctx.cacheDir, "vv_${System.currentTimeMillis()}.wav")
            outFile.writeBytes(wavBytes)
            Log.d(TAG, "Saved VV audio: ${outFile.absolutePath} (${wavBytes.size} bytes)")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Sarvam TTS error", e)
            null
        }
    }

    /**
     * Delete the temp VV file. Call after the voice message is sent.
     */
    fun deleteTempFile(file: File?) {
        try {
            file?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp VV file", e)
        }
    }
}
