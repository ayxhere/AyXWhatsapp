package ayx.whatsapp

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Multi-provider song search for the status editor's music picker (v159).
 *
 * Providers (user-approved design):
 * - JioSaavn — full-length streams via the public web API + the standard
 *   encrypted_media_url DES decryption every open-source client uses
 *   (mirrors what the gateway's /music/search already did server-side;
 *   the picker now calls it directly so all providers behave the same).
 * - iTunes / Deezer — keyless search, 30 s preview clips as the audio.
 * - Spotify — SEARCH ONLY, with the user's OWN developer credentials
 *   (Client Credentials flow). Spotify's API hands out no audio file,
 *   so a picked Spotify track is resolved to real audio by matching it
 *   on JioSaavn (closest duration wins), falling back to the iTunes
 *   preview for the same title+artist.
 *
 * Every result is a GatewayClient.Song, so the rest of the pipeline
 * (preview player, AudioTrimmer, download/trim/bake at send time) is
 * completely unchanged — only the URL's origin differs per provider.
 * All calls run on Dispatchers.IO with the same browser UA + timeout
 * discipline as MediaTools' song download (v146).
 */
object MusicProviders {
    const val SAAVN = "saavn"
    const val ITUNES = "itunes"
    const val DEEZER = "deezer"
    const val SPOTIFY = "spotify"
    val PROVIDERS: List<Pair<String, String>> = listOf(
        SAAVN to "JioSaavn", ITUNES to "iTunes", DEEZER to "Deezer", SPOTIFY to "Spotify")

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // ---------- Spotify credentials (EncryptedSharedPreferences, same pattern as GatewayAuth) ----------
    private const val PREFS_FILE = "music_providers"
    private const val KEY_SPOTIFY_ID = "spotify_client_id"
    private const val KEY_SPOTIFY_SECRET = "spotify_client_secret"

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        PREFS_FILE,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        ctx.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun spotifyCreds(ctx: Context): Pair<String, String> = try {
        val p = prefs(ctx)
        (p.getString(KEY_SPOTIFY_ID, "") ?: "") to (p.getString(KEY_SPOTIFY_SECRET, "") ?: "")
    } catch (e: Exception) { "" to "" }

    fun saveSpotifyCreds(ctx: Context, id: String, secret: String) {
        try {
            prefs(ctx).edit().putString(KEY_SPOTIFY_ID, id.trim()).putString(KEY_SPOTIFY_SECRET, secret.trim()).apply()
        } catch (e: Exception) {}
        // credentials changed → any cached token belongs to the old pair
        synchronized(this) { spToken = ""; spTokenExpMs = 0L }
    }

    fun spotifyConfigured(ctx: Context): Boolean {
        val (id, secret) = spotifyCreds(ctx)
        return id.isNotBlank() && secret.isNotBlank()
    }

    // ---------- HTTP ----------
    private fun httpText(url: String, method: String = "GET", formBody: String? = null,
                         headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 20000): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (formBody != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            } else if (method != "GET") {
                requestMethod = method
            }
        }
        if (formBody != null) c.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw java.io.IOException("HTTP $code " + txt.take(160))
        return txt
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // JioSaavn titles/artists arrive HTML-entity-encoded — same replacements the gateway applies.
    private fun deEnt(s: String): String = s.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

    // ---------- dispatch ----------
    suspend fun search(provider: String, q: String, ctx: Context): Pair<List<GatewayClient.Song>, String> =
        withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    ITUNES -> searchItunesList(q) to ""
                    DEEZER -> searchDeezerList(q) to ""
                    SPOTIFY -> searchSpotifyList(ctx, q) to ""
                    else -> searchSaavnList(q) to ""
                }
            } catch (e: Exception) {
                emptyList<GatewayClient.Song>() to (e.message ?: "connection error")
            }
        }

    // ---------- JioSaavn (full-length) ----------
    private fun searchSaavnList(q: String): List<GatewayClient.Song> {
        val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&q=" +
            enc(q) + "&p=1&n=30"
        val body = httpText(url, headers = mapOf("Referer" to "https://www.jiosaavn.com/", "Accept" to "application/json"))
        val data = try { JSONObject(body) } catch (pe: Exception) { JSONObject(body.replace(Regex("^[^\\{\\[]*"), "")) }
        val results = data.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<GatewayClient.Song>()
        for (i in 0 until results.length()) {
            val it = results.optJSONObject(i) ?: continue
            val mi = it.optJSONObject("more_info")
            val encUrl = (mi?.optString("encrypted_media_url") ?: "").ifBlank { it.optString("encrypted_media_url") }
            if (encUrl.isBlank()) continue
            val audio = saavnDecrypt(encUrl) ?: continue
            var artist = ""
            try {
                val prim = mi?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
                if (prim != null && prim.length() > 0) {
                    artist = (0 until prim.length()).mapNotNull { j -> prim.optJSONObject(j)?.optString("name") }.joinToString(", ")
                }
            } catch (e: Exception) {}
            if (artist.isBlank()) artist = it.optString("primary_artists").ifBlank { it.optString("subtitle") }
            val dur = (mi?.optString("duration") ?: "").toIntOrNull() ?: it.optInt("duration", 0)
            out.add(GatewayClient.Song(
                deEnt(it.optString("title").ifBlank { it.optString("song") }),
                deEnt(artist),
                it.optString("image").replace("150x150", "500x500").ifEmpty { null },
                audio,
                dur))
        }
        return out
    }

    // The standard route used by open-source JioSaavn clients: DES-ECB
    // (PKCS#7) with the web player's well-known key, Base64 ciphertext,
    // then upgrade the 96 kbps filename suffix to 320 kbps.
    private fun saavnDecrypt(encUrl: String): String? = try {
        val key = SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val plain = String(cipher.doFinal(Base64.decode(encUrl, Base64.DEFAULT)), Charsets.UTF_8)
        plain.replace("_96.mp4", "_320.mp4").takeIf { it.startsWith("http") }
    } catch (e: Exception) { null }

    // ---------- iTunes (30 s preview) ----------
    private fun searchItunesList(q: String): List<GatewayClient.Song> {
        val url = "https://itunes.apple.com/search?term=" + enc(q) + "&media=music&entity=song&limit=30"
        val data = JSONObject(httpText(url))
        val results = data.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<GatewayClient.Song>()
        for (i in 0 until results.length()) {
            val it = results.optJSONObject(i) ?: continue
            val preview = it.optString("previewUrl")
            if (preview.isBlank()) continue
            out.add(GatewayClient.Song(
                it.optString("trackName"),
                it.optString("artistName"),
                it.optString("artworkUrl100").replace("100x100bb.jpg", "600x600bb.jpg").ifEmpty { null },
                preview,
                (it.optLong("trackTimeMillis", 0L) / 1000L).toInt()))
        }
        return out
    }

    // ---------- Deezer (30 s preview) ----------
    private fun searchDeezerList(q: String): List<GatewayClient.Song> {
        val url = "https://api.deezer.com/search?q=" + enc(q) + "&limit=30"
        val data = JSONObject(httpText(url))
        val results = data.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<GatewayClient.Song>()
        for (i in 0 until results.length()) {
            val it = results.optJSONObject(i) ?: continue
            val preview = it.optString("preview")
            if (preview.isBlank()) continue
            val album = it.optJSONObject("album")
            var cover: String? = album?.optString("cover_big")?.ifEmpty { null }
            if (cover == null) cover = album?.optString("cover_medium")?.ifEmpty { null }
            out.add(GatewayClient.Song(
                it.optString("title"),
                it.optJSONObject("artist")?.optString("name") ?: "",
                cover,
                preview,
                it.optInt("duration", 0)))
        }
        return out
    }

    // ---------- Spotify (search only; audio resolved separately) ----------
    @Volatile private var spToken: String = ""
    @Volatile private var spTokenExpMs: Long = 0L

    private fun spotifyToken(id: String, secret: String): String {
        val now = System.currentTimeMillis()
        if (spToken.isNotBlank() && now < spTokenExpMs) return spToken
        synchronized(this) {
            val t2 = System.currentTimeMillis()
            if (spToken.isNotBlank() && t2 < spTokenExpMs) return spToken
            val basic = Base64.encodeToString("$id:$secret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val body = httpText("https://accounts.spotify.com/api/token",
                formBody = "grant_type=client_credentials",
                headers = mapOf("Authorization" to "Basic $basic"))
            val o = JSONObject(body)
            val tok = o.optString("access_token")
            if (tok.isBlank()) throw java.io.IOException("Spotify token failed")
            spToken = tok
            spTokenExpMs = t2 + (o.optInt("expires_in", 3600) - 60).coerceAtLeast(60) * 1000L
        }
        return spToken
    }

    private fun searchSpotifyList(ctx: Context, q: String): List<GatewayClient.Song> {
        val (id, secret) = spotifyCreds(ctx)
        if (id.isBlank() || secret.isBlank()) throw java.io.IOException("Spotify keys missing")
        val token = try {
            spotifyToken(id, secret)
        } catch (e: Exception) {
            throw java.io.IOException("Spotify login failed — check Client ID/Secret in Settings → AI → Spotify (song search)")
        }
        val url = "https://api.spotify.com/v1/search?q=" + enc(q) + "&type=track&limit=30"
        val data = JSONObject(httpText(url, headers = mapOf("Authorization" to "Bearer $token")))
        val items = data.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<GatewayClient.Song>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val artists = it.optJSONArray("artists")
            val artist = if (artists != null) (0 until artists.length())
                .mapNotNull { j -> artists.optJSONObject(j)?.optString("name") }.joinToString(", ") else ""
            val images = it.optJSONObject("album")?.optJSONArray("images")
            var image: String? = null
            var bestW = -1
            if (images != null) for (j in 0 until images.length()) {
                val im = images.optJSONObject(j) ?: continue
                if (im.optInt("width", 0) >= bestW) { bestW = im.optInt("width", 0); image = im.optString("url") }
            }
            // url intentionally blank: Spotify serves no audio file. The
            // picker resolves one via resolveSpotifyAudio() before the
            // song can be previewed or applied.
            out.add(GatewayClient.Song(
                it.optString("name"),
                artist,
                image?.ifEmpty { null },
                "",
                (it.optLong("duration_ms", 0L) / 1000L).toInt()))
        }
        return out
    }

    /**
     * A picked Spotify track → a Song with real audio: best JioSaavn
     * match (same base title, closest duration), else the iTunes
     * preview for the same title+artist, else null (picker toasts).
     */
    suspend fun resolveSpotifyAudio(ctx: Context, song: GatewayClient.Song): GatewayClient.Song? =
        withContext(Dispatchers.IO) {
            try {
                val q = (song.title + " " + song.artist).trim()
                val saavn = runCatching { searchSaavnList(q) }.getOrDefault(emptyList())
                val best = pickMatch(song, saavn)
                if (best != null) return@withContext song.copy(
                    url = best.url,
                    durationSec = if (best.durationSec > 0) best.durationSec else song.durationSec)
                val itunes = runCatching { searchItunesList(q) }.getOrDefault(emptyList())
                val iBest = pickMatch(song, itunes) ?: itunes.firstOrNull()
                if (iBest != null) return@withContext song.copy(
                    url = iBest.url,
                    durationSec = if (iBest.durationSec > 0) iBest.durationSec else song.durationSec)
                null
            } catch (e: Exception) { null }
        }

    // Base-title comparison: "(From …)" / "[…]" suffixes and punctuation
    // differ across catalogs for the very same recording.
    private fun normTitle(s: String): String = s.lowercase()
        .replace(Regex("\\(.*?\\)"), " ").replace(Regex("\\[.*?\\]"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun pickMatch(target: GatewayClient.Song, cands: List<GatewayClient.Song>): GatewayClient.Song? {
        val t = normTitle(target.title)
        if (t.isBlank()) return null
        val hits = cands.filter {
            val c = normTitle(it.title)
            c == t || (c.length >= 4 && t.contains(c)) || (t.length >= 4 && c.contains(t))
        }
        if (hits.isEmpty()) return null
        return if (target.durationSec > 0) hits.minByOrNull { kotlin.math.abs(it.durationSec - target.durationSec) } else hits.first()
    }
}
