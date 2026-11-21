package com.cstark.starktv

import java.io.IOException
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A movie or show from Overseerr/TMDB (not necessarily in your Plex library). */
data class Media(
    val id: Int,
    val type: String, // "movie" or "tv"
    val title: String,
    val year: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val status: Int, // 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available
    val ratingKey: String? = null, // Plex key when the title is already in your library
)

fun Media.statusLabel(): String = when (status) {
    5 -> "Available"
    4 -> "Partly available"
    3 -> "Requested · downloading"
    2 -> "Requested · pending"
    else -> ""
}

fun tmdbImg(path: String?, size: String): String? =
    path?.let { if (it.startsWith("http")) it else "https://image.tmdb.org/t/p/$size$it" }

private fun JSONObject.jstr(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).ifEmpty { null } else null

private fun JSONObject.toMedia(defaultType: String? = null): Media? {
    val t = optString("mediaType").ifEmpty { defaultType ?: return null }
    if (t != "movie" && t != "tv") return null
    val title = optString("title").ifEmpty { optString("name") }
    if (title.isEmpty()) return null
    val date = optString("releaseDate").ifEmpty { optString("firstAirDate") }
    return Media(
        id = optInt("id"),
        type = t,
        title = title,
        year = date.take(4),
        overview = optString("overview"),
        poster = jstr("posterPath"),
        backdrop = jstr("backdropPath"),
        status = optJSONObject("mediaInfo")?.optInt("status") ?: 1,
        ratingKey = optJSONObject("mediaInfo")?.jstr("ratingKey"),
    )
}

private fun JSONArray?.mediaList(defaultType: String? = null): List<Media> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it)?.toMedia(defaultType) }
}

class OvConfig(val url: String, val remote: String, val apiKey: String)

fun readOvConfig(ctx: Context): OvConfig {
    val map = HashMap<String, String>()
    try {
        ctx.assets.open("overseerr.txt").bufferedReader().use { r ->
            for (line in r.readLines()) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val i = l.indexOf('=')
                if (i > 0) map[l.substring(0, i).trim()] = l.substring(i + 1).trim()
            }
        }
    } catch (e: Exception) {
        // no config file: defaults below
    }
    return OvConfig(map["url"] ?: "http://192.168.1.8:5055", map["remote"] ?: "", map["apikey"] ?: "")
}

class OverseerrApi(private val ctx: Context, private val prefs: Prefs) {
    var base by mutableStateOf("")
    var status by mutableStateOf("Not connected yet")
    private var loggedIn = false
    private var useKey = false
    private var apiKey = ""

    private val store = HashMap<String, MutableList<Cookie>>()
    private val jar = object : CookieJar {
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            for (c in cookies) {
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host]?.toList() ?: emptyList()
    }

    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Call when the profile changes so the next request signs in as the new profile. */
    fun reset() {
        loggedIn = false
        useKey = false
        synchronized(jar) { store.clear() }
        base = ""
        status = "Not connected yet"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Kid profile: every Overseerr call is refused (no Discover, no requests). */
    val restricted: Boolean get() = prefs.restricted

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        if (prefs.restricted) throw IOException("Requests are off for this profile")
        val b = Request.Builder().url("$base/api/v1$path").header("Accept", "application/json")
        if (useKey) b.header("X-Api-Key", apiKey)
        val rb = body?.toRequestBody("application/json".toMediaType())
        when (method) {
            "POST" -> b.post(rb ?: "".toRequestBody(null))
            else -> b.get()
        }
        return http.newCall(b.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
    }

    /** Finds Overseerr and signs in. Safe to call repeatedly. */
    suspend fun ensure(): Boolean = withContext(Dispatchers.IO) {
        if (prefs.restricted) {
            status = "Off for this profile (kid profile)"
            return@withContext false
        }
        if (loggedIn) return@withContext true
        val cfg = readOvConfig(ctx)
        apiKey = cfg.apiKey
        base = ""
        for (c in listOf(cfg.url, cfg.remote).filter { it.isNotBlank() }.map { it.trimEnd('/') }) {
            try {
                val ok = http.newCall(Request.Builder().url("$c/api/v1/status").build()).execute().use { it.isSuccessful }
                if (ok) { base = c; break }
            } catch (e: Exception) {
                // try the next address
            }
        }
        if (base.isEmpty()) {
            status = "Couldn't reach Overseerr (check overseerr.txt)"
            return@withContext false
        }
        for (tok in listOfNotNull(prefs.userToken, prefs.accountToken).distinct()) {
            try {
                val (code, text) = call("POST", "/auth/plex", JSONObject().put("authToken", tok).toString())
                if (code in 200..299) {
                    val name = try { JSONObject(text).optString("displayName") } catch (e: Exception) { "" }
                    loggedIn = true
                    useKey = false
                    status = "Connected" + if (name.isNotEmpty()) " as $name" else ""
                    return@withContext true
                }
            } catch (e: Exception) {
                // try the next token
            }
        }
        if (apiKey.isNotBlank()) {
            useKey = true
            loggedIn = true
            status = "Connected with API key (requests go under the admin account)"
            return@withContext true
        }
        status = "Overseerr didn't accept this Plex login"
        false
    }

    suspend fun search(q: String): List<Media> = withContext(Dispatchers.IO) {
        try {
            val (code, text) = call("GET", "/search?query=${enc(q)}&page=1&language=en")
            if (code !in 200..299) emptyList() else JSONObject(text).optJSONArray("results").mediaList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Genre list for "movie" or "tv": (TMDB genre id, name). */
    suspend fun genres(kind: String): List<Pair<Int, String>> = withContext(Dispatchers.IO) {
        try {
            if (!ensure()) return@withContext emptyList()
            val (code, text) = call("GET", "/genres/$kind?language=en")
            if (code !in 200..299) return@withContext emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isEmpty()) null else o.optInt("id") to name
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Titles matching ALL the given genres (the comma must be URL-encoded, Seerr rejects a raw one). */
    suspend fun discoverByGenres(kind: String, genreIds: Set<Int>): List<Media> = withContext(Dispatchers.IO) {
        try {
            if (genreIds.isEmpty() || !ensure()) return@withContext emptyList()
            val path = if (kind == "tv") "tv" else "movies"
            val (code, text) = call("GET", "/discover/$path?page=1&language=en&genre=${enc(genreIds.joinToString(","))}")
            if (code !in 200..299) emptyList() else JSONObject(text).optJSONArray("results").mediaList(kind)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** One title with its current status (and Plex key if it's already in your library). */
    suspend fun detail(kind: String, tmdbId: Int): Media? = withContext(Dispatchers.IO) {
        try {
            if (!ensure()) return@withContext null
            val (code, text) = call("GET", "/$kind/$tmdbId?language=en")
            if (code !in 200..299) null else JSONObject(text).also { if (!it.has("mediaType")) it.put("mediaType", kind) }.toMedia()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun trending(): List<Media> = withContext(Dispatchers.IO) {
        try {
            val (code, text) = call("GET", "/discover/trending?page=1&language=en")
            if (code !in 200..299) emptyList() else JSONObject(text).optJSONArray("results").mediaList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun recommendations(kind: String, tmdbId: Int): List<Media> = withContext(Dispatchers.IO) {
        try {
            val (code, text) = call("GET", "/$kind/$tmdbId/recommendations?page=1&language=en")
            if (code !in 200..299) emptyList() else JSONObject(text).optJSONArray("results").mediaList(kind)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Recent requests, newest first, with titles filled in (capped to keep it quick). */
    suspend fun recentRequests(): List<Media> = withContext(Dispatchers.IO) {
        try {
            val (code, text) = call("GET", "/request?take=10&skip=0&sort=added&filter=all")
            if (code !in 200..299) return@withContext emptyList()
            val arr = JSONObject(text).optJSONArray("results") ?: return@withContext emptyList()
            val out = mutableListOf<Media>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i).optJSONObject("media") ?: continue
                val kind = m.optString("mediaType")
                val id = m.optInt("tmdbId")
                if ((kind != "movie" && kind != "tv") || id == 0) continue
                val (c2, t2) = call("GET", "/$kind/$id?language=en")
                if (c2 !in 200..299) continue
                JSONObject(t2).also { o -> if (!o.has("mediaType")) o.put("mediaType", kind) }.toMedia()?.let { out += it }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Returns (worked, message). TV shows request every season. */
    suspend fun request(m: Media): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!ensure()) return@withContext false to status
            val body = JSONObject().put("mediaType", m.type).put("mediaId", m.id)
            if (m.type == "tv") body.put("seasons", "all")
            val (code, text) = call("POST", "/request", body.toString())
            when {
                code in 200..299 -> true to "Requested. It'll show up in Plex once it's downloaded."
                code == 409 -> true to "Already requested."
                code == 403 -> false to "Overseerr says this account can't request that (permissions or request limit)."
                else -> false to "Request failed (HTTP $code)" + (try { ": " + JSONObject(text).optString("message") } catch (e: Exception) { "" })
            }
        } catch (e: Exception) {
            false to "Couldn't reach Overseerr"
        }
    }
}
