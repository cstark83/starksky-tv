package com.cstark.starktv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class Item(
    val ratingKey: String,
    val type: String,
    val title: String,
    val summary: String,
    val year: Int,
    val thumb: String?,
    val art: String?,
    val grandparentTitle: String?,
    val grandparentRatingKey: String?,
    val grandparentThumb: String?,
    val parentTitle: String?,
    val parentRatingKey: String?,
    val parentThumb: String?,
    val parentIndex: Int,
    val index: Int,
    val viewOffset: Long,
    val duration: Long,
    val viewCount: Int,
    val contentRating: String?,
    val lastViewedAt: Long = 0L,
    val guid: String? = null,
    val tmdbId: String? = null,
    val titleSort: String? = null, // Plex sort title ("Matrix" for "The Matrix"); null when it equals the title
) {
    /** The key of the show/movie this card should open. */
    val showKey: String
        get() = when (type) {
            "episode" -> grandparentRatingKey ?: ratingKey
            "season" -> parentRatingKey ?: ratingKey
            else -> ratingKey
        }
    val posterPath: String?
        get() = when (type) {
            "episode" -> grandparentThumb ?: thumb
            "season" -> parentThumb ?: thumb
            else -> thumb
        }
    val label: String
        get() = when (type) {
            "episode" -> grandparentTitle ?: title
            "season" -> parentTitle ?: title
            else -> title
        }
    val epCode: String get() = "S${parentIndex}E${index}"
    val progress: Float get() = if (duration > 0 && viewOffset > 0) viewOffset.toFloat() / duration else 0f
}

data class Section(val key: String, val type: String, val title: String)
data class Profile(val uuid: String, val title: String, val thumb: String?, val protected: Boolean, val admin: Boolean)
data class Trailer(val title: String, val partKey: String)
data class DetailData(
    val item: Item,
    val trailers: List<Trailer>,
    val episodes: List<Item>,
    val tmdbId: String? = null,
    val imdbId: String? = null,
)
data class ExtSub(val key: String, val codec: String, val lang: String?, val label: String)
data class PlayInfo(
    val item: Item,
    val partKey: String,
    val subs: List<ExtSub>,
    val introStart: Long,
    val introEnd: Long,
    val nextKey: String?,
)

private fun JSONObject.str(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null

fun JSONObject.tmdbGuid(): String? {
    val g = optJSONArray("Guid") ?: return null
    for (i in 0 until g.length()) {
        val id = g.optJSONObject(i)?.optString("id") ?: continue
        if (id.startsWith("tmdb://")) return id.removePrefix("tmdb://")
    }
    return null
}

fun JSONObject.toItem() = Item(
    ratingKey = optString("ratingKey"),
    type = optString("type"),
    title = optString("title"),
    summary = optString("summary"),
    year = optInt("year"),
    thumb = str("thumb"),
    art = str("art"),
    grandparentTitle = str("grandparentTitle"),
    grandparentRatingKey = str("grandparentRatingKey"),
    grandparentThumb = str("grandparentThumb"),
    parentTitle = str("parentTitle"),
    parentRatingKey = str("parentRatingKey"),
    parentThumb = str("parentThumb"),
    parentIndex = optInt("parentIndex"),
    index = optInt("index"),
    viewOffset = optLong("viewOffset"),
    duration = optLong("duration"),
    viewCount = optInt("viewCount"),
    contentRating = str("contentRating"),
    lastViewedAt = optLong("lastViewedAt"),
    guid = str("guid"),
    tmdbId = tmdbGuid(),
    titleSort = str("titleSort"),
)

class PlexApi(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Server base URL, e.g. http://192.168.1.8:32400 — set by connect(). */
    var base: String = ""
    val token: String get() = prefs.userToken ?: prefs.accountToken ?: ""
    val srvToken: String get() = prefs.serverToken ?: token

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun req(url: String, tok: String?, method: String = "GET"): Request {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", prefs.clientId)
            .header("X-Plex-Product", "Starsky TV")
            .header("X-Plex-Version", "1.0")
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Device-Name", "Starsky TV")
        if (!tok.isNullOrEmpty()) b.header("X-Plex-Token", tok)
        if (method == "POST") b.post("".toRequestBody(null))
        if (method == "PUT") b.put("".toRequestBody(null))
        return b.build()
    }

    private fun exec(r: Request, client: OkHttpClient = http): String {
        client.newCall(r).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} (${r.url.encodedPath})")
            return body
        }
    }

    private suspend fun json(url: String, tok: String?, method: String = "GET"): JSONObject =
        withContext(Dispatchers.IO) {
            val s = exec(req(url, tok, method))
            if (s.isBlank()) JSONObject() else JSONObject(s)
        }

    // ---------- plex.tv: sign in, server discovery, profiles ----------

    suspend fun createPin(): Pair<Long, String> {
        val o = json("https://plex.tv/api/v2/pins?strong=false", null, "POST")
        return o.getLong("id") to o.getString("code")
    }

    suspend fun pollPin(id: Long): String? {
        val o = json("https://plex.tv/api/v2/pins/$id", null)
        return if (o.isNull("authToken")) null else o.optString("authToken").ifEmpty { null }
    }

    /** Returns (server name, candidate base URLs ordered local -> remote -> relay). */
    suspend fun discoverServer(accountToken: String): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        val arr = JSONArray(exec(req("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", accountToken)))
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            if (!d.optString("provides").contains("server")) continue
            if (best == null) best = d
            if (d.optBoolean("owned")) { best = d; break }
        }
        val srv = best ?: throw IOException("No Plex server found on this account")
        val localL = mutableListOf<String>()
        val remoteL = mutableListOf<String>()
        val relayL = mutableListOf<String>()
        val conns = srv.optJSONArray("connections") ?: JSONArray()
        for (i in 0 until conns.length()) {
            val c = conns.getJSONObject(i)
            val uri = c.optString("uri")
            val addr = c.optString("address")
            val port = c.optInt("port", 32400)
            when {
                c.optBoolean("relay") -> relayL += uri
                c.optBoolean("local") -> {
                    if (addr.isNotEmpty() && !addr.contains(":")) localL += "http://$addr:$port"
                    localL += uri
                }
                else -> remoteL += uri
            }
        }
        srv.optString("name", "Plex") to (localL + remoteL + relayL).filter { it.isNotBlank() }.distinct()
    }

    /** Probes each candidate and keeps the first one that answers. */
    suspend fun connect(candidates: List<String>): String = withContext(Dispatchers.IO) {
        val quick = http.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(4000, TimeUnit.MILLISECONDS)
            .build()
        for (c in candidates) {
            try {
                exec(req("$c/identity", null), quick)
                base = c
                return@withContext c
            } catch (_: Exception) {
            }
        }
        throw IOException("Couldn't reach your Plex server")
    }

    suspend fun homeUsers(accountToken: String): List<Profile> = withContext(Dispatchers.IO) {
        val txt = exec(req("https://plex.tv/api/v2/home/users", accountToken)).trim()
        val arr = if (txt.startsWith("[")) JSONArray(txt) else (JSONObject(txt).optJSONArray("users") ?: JSONArray())
        (0 until arr.length()).map {
            val u = arr.getJSONObject(it)
            Profile(
                uuid = u.optString("uuid"),
                title = u.optString("title").ifEmpty { u.optString("username") },
                thumb = u.optString("thumb").ifEmpty { null },
                protected = u.optBoolean("protected"),
                admin = u.optBoolean("admin"),
            )
        }
    }

    suspend fun switchProfile(accountToken: String, uuid: String, pin: String?): String = withContext(Dispatchers.IO) {
        val url = "https://plex.tv/api/v2/home/users/$uuid/switch" + if (pin.isNullOrEmpty()) "" else "?pin=$pin"
        JSONObject(exec(req(url, accountToken, "POST"))).getString("authToken")
    }

    suspend fun serverAccessToken(profileToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray(exec(req("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", profileToken)))
            var best: JSONObject? = null
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                if (!d.optString("provides").contains("server")) continue
                if (best == null) best = d
                if (d.optString("name") == prefs.serverName) { best = d; break }
                if (d.optBoolean("owned")) best = d
            }
            best?.optString("accessToken")?.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- your server ----------

    private suspend fun srv(path: String): JSONObject =
        json("$base$path", srvToken).optJSONObject("MediaContainer") ?: JSONObject()

    private fun JSONObject.metas(): List<Item> {
        val a = optJSONArray("Metadata") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).toItem() }
    }

    suspend fun sections(): List<Section> {
        val a = srv("/library/sections").optJSONArray("Directory") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }
            .map { Section(it.optString("key"), it.optString("type"), it.optString("title")) }
    }

    suspend fun onDeck(): List<Item> =
        srv("/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=30").metas()

    suspend fun recent(s: Section): List<Item> {
        val t = if (s.type == "show") 2 else 1
        return srv("/library/sections/${s.key}/recentlyAdded?type=$t&X-Plex-Container-Start=0&X-Plex-Container-Size=30").metas()
    }

    suspend fun library(s: Section, sort: String): List<Item> =
        srv("/library/sections/${s.key}/all?sort=${enc(sort)}&X-Plex-Container-Start=0&X-Plex-Container-Size=3000").metas()

    private fun extrasFrom(arr: JSONArray?): List<Trailer> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Trailer>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optString("subtype") != "trailer") continue
            val key = e.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key")
            if (key.isNullOrEmpty()) continue
            out += Trailer(e.optString("title", "Trailer"), key)
        }
        return out
    }

    suspend fun detail(key: String): DetailData {
        val m = srv("/library/metadata/$key?includeExtras=1&includeGuids=1").optJSONArray("Metadata")?.getJSONObject(0)
            ?: throw IOException("Item not found")
        val item = m.toItem()
        var trailers = extrasFrom(m.optJSONObject("Extras")?.optJSONArray("Metadata"))
        if (trailers.isEmpty()) {
            trailers = try {
                extrasFrom(srv("/library/metadata/$key/extras").optJSONArray("Metadata"))
            } catch (e: Exception) {
                emptyList()
            }
        }
        val eps = if (item.type == "show") srv("/library/metadata/$key/allLeaves").metas() else emptyList()
        var tmdb: String? = null
        var imdb: String? = null
        val guids = m.optJSONArray("Guid")
        if (guids != null) {
            for (i in 0 until guids.length()) {
                val id = guids.getJSONObject(i).optString("id")
                if (id.startsWith("tmdb://")) tmdb = id.removePrefix("tmdb://")
                if (id.startsWith("imdb://")) imdb = id.removePrefix("imdb://")
            }
        }
        return DetailData(item, trailers, eps, tmdb, imdb)
    }

    suspend fun playInfo(key: String): PlayInfo {
        val m = srv("/library/metadata/$key?includeMarkers=1").optJSONArray("Metadata")?.getJSONObject(0)
            ?: throw IOException("Item not found")
        val item = m.toItem()
        val part = m.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
            ?: throw IOException("No playable file")
        val partKey = part.optString("key")

        val subs = mutableListOf<ExtSub>()
        val streams = part.optJSONArray("Stream")
        if (streams != null) {
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                if (s.optInt("streamType") == 3 && s.has("key")) {
                    subs += ExtSub(
                        key = s.getString("key"),
                        codec = s.optString("codec"),
                        lang = s.optString("languageCode").ifEmpty { null },
                        label = s.optString("displayTitle", "Subtitles"),
                    )
                }
            }
        }

        var introStart = -1L
        var introEnd = -1L
        val markers = m.optJSONArray("Marker")
        if (markers != null) {
            for (i in 0 until markers.length()) {
                val mk = markers.getJSONObject(i)
                if (mk.optString("type") == "intro") {
                    introStart = mk.optLong("startTimeOffset", -1)
                    introEnd = mk.optLong("endTimeOffset", -1)
                }
            }
        }

        var next: String? = null
        val show = item.grandparentRatingKey
        if (item.type == "episode" && show != null) {
            try {
                val leaves = srv("/library/metadata/$show/allLeaves").metas()
                val idx = leaves.indexOfFirst { it.ratingKey == item.ratingKey }
                if (idx >= 0) next = leaves.getOrNull(idx + 1)?.ratingKey
            } catch (_: Exception) {
            }
        }
        return PlayInfo(item, partKey, subs, introStart, introEnd, next)
    }

    suspend fun timeline(item: Item, state: String, posMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                val k = enc("/library/metadata/${item.ratingKey}")
                exec(req("$base/:/timeline?ratingKey=${item.ratingKey}&key=$k&identifier=com.plexapp.plugins.library&state=$state&time=$posMs&duration=${item.duration}", srvToken))
            } catch (_: Exception) {
            }
        }
    }

    suspend fun scrobble(ratingKey: String) {
        withContext(Dispatchers.IO) {
            try {
                exec(req("$base/:/scrobble?key=$ratingKey&identifier=com.plexapp.plugins.library", srvToken))
            } catch (_: Exception) {
            }
        }
    }

    /** Marks a movie, episode, season or whole show watched/unwatched on the Plex server. */
    suspend fun setWatched(ratingKey: String, watched: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val action = if (watched) "scrobble" else "unscrobble"
            exec(req("$base/:/$action?key=$ratingKey&identifier=com.plexapp.plugins.library", srvToken))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Asks Plex to drop an item from Continue Watching without marking it watched. */
    suspend fun hideFromContinue(ratingKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            exec(req("$base/actions/removeFromContinueWatching?ratingKey=$ratingKey", srvToken, "PUT"))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Searches every movie and show library on the server. */
    suspend fun search(q: String): List<Item> {
        val hubs = srv("/hubs/search?query=${enc(q)}&limit=30&includeCollections=0").optJSONArray("Hub") ?: return emptyList()
        val out = mutableListOf<Item>()
        for (i in 0 until hubs.length()) {
            val h = hubs.getJSONObject(i)
            val t = h.optString("type")
            if (t != "movie" && t != "show" && t != "episode") continue
            out += h.metas()
        }
        return out.distinctBy { it.showKey }
    }

    /** Most recently watched titles in one library (newest first). */
    suspend fun recentlyWatched(s: Section): List<Item> =
        srv("/library/sections/${s.key}/all?sort=lastViewedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=15")
            .metas()
            .filter { it.lastViewedAt > 0 }

    // ---------- Plex Watchlist (the same list every Plex app shows for this profile) ----------

    private val discoverHost = "https://discover.provider.plex.tv"

    suspend fun watchlist(): List<Item> = try {
        json("$discoverHost/library/sections/watchlist/all?includeGuids=1&sort=watchlistedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=60", token)
            .optJSONObject("MediaContainer")?.metas() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun setWatchlist(discoverKey: String, on: Boolean): Boolean = try {
        val action = if (on) "addToWatchlist" else "removeFromWatchlist"
        json("$discoverHost/actions/$action?ratingKey=$discoverKey", token, "PUT")
        true
    } catch (e: Exception) {
        false
    }

    /** Plex's own id for a title (needed to Watchlist something that isn't in your library). */
    suspend fun discoverKeyFor(title: String, year: Int, type: String, tmdbId: String?): String? = try {
        val kind = if (type == "tv") "tv" else "movies"
        val mc = json("$discoverHost/library/search?query=${enc(title)}&limit=10&searchTypes=$kind&includeGuids=1", token)
            .optJSONObject("MediaContainer")
        val found = mutableListOf<JSONObject>()
        val groups = mc?.optJSONArray("SearchResults")
        if (groups != null) {
            for (i in 0 until groups.length()) {
                val rs = groups.getJSONObject(i).optJSONArray("SearchResult") ?: continue
                for (j in 0 until rs.length()) {
                    val md = rs.getJSONObject(j).optJSONObject("Metadata")
                    if (md != null) found += md
                }
            }
        }
        val byId = if (tmdbId != null) found.firstOrNull { it.tmdbGuid() == tmdbId } else null
        val pick = byId ?: found.firstOrNull {
            it.optString("title").equals(title, ignoreCase = true) && (year == 0 || it.optInt("year") == year)
        }
        pick?.optString("ratingKey")?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    // ---------- URL helpers ----------

    fun img(path: String?, w: Int, h: Int): String? =
        path?.let { "$base/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1&url=${enc(it)}&X-Plex-Token=$srvToken" }

    fun media(path: String): String =
        "$base$path${if (path.contains("?")) "&" else "?"}X-Plex-Token=$srvToken"
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtRuntime(ms: Long): String {
    val mins = ms / 60000
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}
