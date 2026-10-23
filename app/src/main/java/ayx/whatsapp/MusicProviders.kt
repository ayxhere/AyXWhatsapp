package ayx.whatsapp

import android.content.Context
import android.util.Base64
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
 * - iTunes — keyless search, 30 s preview clips as the audio.
 * - Spotify — SEARCH ONLY, free route only (v162): no login, no user
 *   keys. The search runs on the free anonymous route (token read from
 *   the public embed player page; unofficial and fragile, see
 *   spotifyAnonToken). Spotify's API hands out no audio file, so a
 *   picked Spotify track is resolved to real audio by matching it on
 *   JioSaavn (closest duration wins), falling back to the iTunes preview
 *   for the same title+artist. iTunes picks resolve the same way on
 *   apply (v160) and fall back to their own 30 s preview when JioSaavn
 *   has no match. (Deezer was removed in v161 at the user's order.)
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
    const val SPOTIFY = "spotify"
    val PROVIDERS: List<Pair<String, String>> = listOf(
        SAAVN to "JioSaavn", ITUNES to "iTunes", SPOTIFY to "Spotify")

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // v162: Spotify is free-route only — the user's own Client ID/Secret
    // (v159–v161, stored in the dedicated encrypted "music_providers"
    // prefs file) are gone. One-time cleanup: delete that leftover file
    // so no orphaned keys sit on the device; guarded so it runs once.
    fun wipeLegacySpotifyKeys(ctx: Context) {
        try {
            val guard = ctx.getSharedPreferences("ayx_music", Context.MODE_PRIVATE)
            if (!guard.getBoolean("spotify_keys_wiped", false)) {
                ctx.deleteSharedPreferences("music_providers")
                guard.edit().putBoolean("spotify_keys_wiped", true).apply()
            }
        } catch (e: Exception) {}
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

    // Canonical RFC 3986 query encoding: URLEncoder is form encoding and
    // emits "+" for spaces; the provider APIs expect "%20" (v160). A literal
    // "+" in the input is already "%2B" by the time the replace runs, so
    // only space-derived pluses are rewritten.
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // JioSaavn titles/artists arrive HTML-entity-encoded — same replacements the gateway applies.
    private fun deEnt(s: String): String = s.replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")

    // ---------- dispatch ----------
    suspend fun search(provider: String, q: String, ctx: Context): Pair<List<GatewayClient.Song>, String> =
        withContext(Dispatchers.IO) {
            try {
                when (provider) {
                    ITUNES -> searchItunesList(q) to ""
                    SPOTIFY -> searchSpotifyWithFallback(q) to ""
                    else -> searchSaavnList(q) to ""   // unknown/persisted ids (e.g. removed providers) land on JioSaavn
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

    /**
     * v163 — the Spotify tab must never dead-end (user: "Spotify
     * unlimited free krde, bina error ke chalta rahe"). Spotify results
     * when the anonymous route answers; on ANY failure (per-IP 429
     * quota, token failure, network) or an empty result, SILENTLY fall
     * back in the same search to JioSaavn, then iTunes, for the same
     * query — deduped by normalized title+artist, JioSaavn first. The
     * caller always gets an empty error string here, so no Spotify HTTP
     * error can ever render on this tab; only when every source yields
     * nothing does the sheet show its plain "No results". Fallback
     * songs carry real audio URLs already, so preview/apply need no
     * Spotify-style resolution for them.
     */
    private fun searchSpotifyWithFallback(q: String): List<GatewayClient.Song> {
        val sp = runCatching { searchSpotifyList(q) }.getOrDefault(emptyList())
        if (sp.isNotEmpty()) return sp
        val out = ArrayList<GatewayClient.Song>()
        val seen = HashSet<String>()
        fun addAll(list: List<GatewayClient.Song>) {
            for (s in list) if (seen.add(songKey(s))) out.add(s)
        }
        addAll(runCatching { searchSaavnList(q) }.getOrDefault(emptyList()))
        addAll(runCatching { searchItunesList(q) }.getOrDefault(emptyList()))
        return out
    }

    private fun songKey(s: GatewayClient.Song) = normTitle(s.title) + "|" + normTitle(s.artist)

    // ---------- Spotify (search only; audio resolved separately) ----------
    // v162: the ONLY route is the free anonymous one below — no login,
    // no user keys (the Client Credentials path was removed).
    private fun searchSpotifyList(q: String): List<GatewayClient.Song> = spotifyAnonSearch(q)

    // The Web API search parse path for the anonymous token.
    private fun spotifySearchWithToken(token: String, q: String): List<GatewayClient.Song> {
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

    // ---------- Spotify free (anonymous) route ----------
    // UNOFFICIAL / FRAGILE (v160): the web player's classic token
    // endpoints are walled off (open.spotify.com/get_access_token → 403,
    // /api/token → 400 "not permitted under the Spotify Developer
    // Terms"), but the public embed player page still ships an anonymous
    // accessToken + its expiry timestamp in the page JSON — read it from
    // there and call the same Web API search. Spotify can change or
    // close this at any time; if it breaks, the Spotify tab surfaces
    // the real error (v161).
    //
    // v161 live re-verification (2026-10-23), from evidence not memory:
    // - Token extraction works with desktop, mobile AND no User-Agent
    //   (HTTP 200, token + expiry in the page, all variants). The
    //   open.spotify.com MAIN page carries NO accessToken — the embed
    //   page is the only working source.
    // - The fragile point is the SEARCH quota, not the token: from an
    //   exhausted IP, /v1/search answers HTTP 429 QUOTA_EXCEEDED with
    //   Retry-After ≈ 75142 s (~21 h). Fresh tokens and different
    //   embed track ids do NOT help — the quota is per-IP (a carrier
    //   NAT address is shared with thousands of other users, so on a
    //   phone this route can be exhausted around the clock; the v160
    //   one-time pass was a fresh-IP window). A 429 therefore becomes
    //   a clear message + a cool-down instead of silent retries.
    // - httpText audit: the headers map is applied AFTER the default
    //   User-Agent, so the desktop UA below verifiably wins for the
    //   embed request (setRequestProperty replaces); Accept-Encoding
    //   is never set manually, so HttpURLConnection's transparent
    //   gzip decoding stays active. Both were already correct.
    @Volatile private var spAnonToken: String = ""
    @Volatile private var spAnonExpMs: Long = 0L
    @Volatile private var spAnonQuotaUntilMs: Long = 0L

    private fun spotifyAnonSearch(q: String): List<GatewayClient.Song> {
        if (System.currentTimeMillis() < spAnonQuotaUntilMs)
            throw java.io.IOException("Spotify free search is rate-limited (HTTP 429) — try again later, or use the JioSaavn tab")
        try {
            return spotifySearchWithToken(spotifyAnonToken(), q)
        } catch (e: java.io.IOException) {
            when {
                e.message?.startsWith("HTTP 429") == true -> {
                    // Shared anonymous quota exhausted for this IP
                    // (Retry-After is measured in HOURS — retrying now
                    // is pointless). Cool down and say so plainly.
                    spAnonQuotaUntilMs = System.currentTimeMillis() + 10 * 60_000L
                    throw java.io.IOException("Spotify free search is rate-limited (HTTP 429) — try again later, or use the JioSaavn tab")
                }
                e.message?.startsWith("HTTP 401") == true -> {
                    // cached token rejected → drop it, refetch once, retry once
                    synchronized(this) { spAnonToken = ""; spAnonExpMs = 0L }
                    return spotifySearchWithToken(spotifyAnonToken(), q)
                }
                else -> throw e
            }
        }
    }

    private fun spotifyAnonToken(): String {
        val now = System.currentTimeMillis()
        if (spAnonToken.isNotBlank() && now < spAnonExpMs) return spAnonToken
        synchronized(this) {
            val t2 = System.currentTimeMillis()
            if (spAnonToken.isNotBlank() && t2 < spAnonExpMs) return spAnonToken
            // The embed page is a desktop web-player surface — use the
            // desktop browser UA the live verification used (it replaces
            // httpText's default UA via the headers map).
            val html = try {
                httpText("https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC",
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
            } catch (e: java.io.IOException) {
                throw java.io.IOException("Spotify token fetch failed — " + (e.message ?: "network error"))
            }
            val tok = Regex("""\\?"accessToken\\?"\s*:\s*\\?"([^"\\]+)""").find(html)?.groupValues?.get(1)
                ?: throw java.io.IOException("Spotify token not found in the embed page — Spotify may have changed it")
            val exp = Regex("""\\?"accessTokenExpirationTimestampMs\\?"\s*:\s*(\d+)""").find(html)?.groupValues?.get(1)?.toLongOrNull()
            spAnonToken = tok
            spAnonExpMs = when {
                exp != null && exp > t2 + 60_000L -> exp - 60_000L   // honour the page's expiry, 60 s safety margin
                exp != null && exp > t2 -> exp
                else -> t2 + 30 * 60_000L                            // no usable expiry published → assume 30 min
            }
        }
        return spAnonToken
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

    /**
     * iTunes rows carry only the 30 s preview URL — on APPLY, the
     * same JioSaavn matcher used for Spotify finds the full-length song
     * (v160). Null = no match; the caller then falls back to the row's
     * own preview URL (the pre-v160 behavior).
     */
    suspend fun resolveFullAudio(ctx: Context, song: GatewayClient.Song): GatewayClient.Song? =
        withContext(Dispatchers.IO) {
            try {
                val q = (song.title + " " + song.artist).trim()
                val saavn = runCatching { searchSaavnList(q) }.getOrDefault(emptyList())
                val best = pickMatch(song, saavn) ?: return@withContext null
                song.copy(url = best.url,
                    durationSec = if (best.durationSec > 0) best.durationSec else song.durationSec)
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
