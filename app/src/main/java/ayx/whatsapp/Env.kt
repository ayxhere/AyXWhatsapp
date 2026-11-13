package ayx.whatsapp

/**
 * v269: the app's env — EVERY external API endpoint + service config lives
 * here and nowhere else. Feature code never hardcodes an API URL again; it
 * reads Env.X. The matching `.env` file at the project root documents the
 * same values for the user (edit .env, then mirror the change here — the
 * app reads Env.kt at runtime, .env is the human source of truth).
 *
 * Secret KEYS are never in this file and never in .env: the user's own AI
 * keys come from Settings (AiKeyStore, encrypted on-device) and the gateway
 * token from GatewayAuth (generated per install, checked against env in
 * main.js). Only public endpoint URLs live here.
 */
object Env {
    // ---- AI chat-completions endpoints (OpenAI-compatible) ----
    const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    const val OPENROUTER_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"
    const val GEMINI_CHAT_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    // ---- Google Drive ----
    const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    const val DRIVE_SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"

    // ---- Music search ----
    const val JIOSAAVN_API = "https://www.jiosaavn.com/api.php"
    const val JIOSAAVN_REFERER = "https://www.jiosaavn.com/"
    const val SPOTIFY_SEARCH_API = "https://api.spotify.com/v1/search"
    const val SPOTIFY_EMBED_TRACK = "https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC"

    // ---- On-device gateway (Node server on localhost) ----
    fun gatewayBase(port: Int) = "http://127.0.0.1:$port"

    // ---- Free AI fallbacks (used by the gateway, mirrored here) ----
    const val POLLINATIONS_VISION_URL = "https://text.pollinations.ai/openai?referrer=ayxwhatsapp"
    const val POLLINATIONS_IMAGE_URL = "https://image.pollinations.ai/prompt/"

    // ---- App / releases ----
    const val AYX_DOWNLOAD_URL = "https://github.com/ayxhere/AyXWhatsapp/releases/latest"
}
