package com.cstark.starktv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Last trailer source (after Plex extras and Plex's own trailer): looks the title up on TMDB (using the
 * TMDB/IMDb ids Plex already stores) and returns ranked YouTube video ids, which Trailers.kt resolves and plays in the app.
 * Needs a free TMDB key pasted into app/src/main/assets/tmdb_key.txt.
 */
object Tmdb {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun key(ctx: Context): String =
        try {
            ctx.assets.open("tmdb_key.txt").bufferedReader().use { r ->
                r.readLines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: ""
            }
        } catch (e: Exception) {
            ""
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(path: String, key: String): JSONObject? {
        // a v4 "read access token" is long and goes in a header; a v3 key goes in the URL
        val bearer = key.length > 40
        val sep = if (path.contains("?")) "&" else "?"
        val url = "https://api.themoviedb.org/3$path" + if (bearer) "" else "${sep}api_key=$key"
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (bearer) b.header("Authorization", "Bearer $key")
        return try {
            http.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * YouTube video ids for the title, best first: official trailers (newest first), other trailers, then teasers;
     * English before other languages. Empty when TMDB has none (or no key).
     */
    suspend fun trailerYoutubeIds(ctx: Context, d: TrailerReq): List<String> = withContext(Dispatchers.IO) {
        val key = key(ctx)
        if (key.isBlank()) return@withContext emptyList()
        val kind = if (d.type == "show") "tv" else "movie"

        var id = d.tmdbId
        if (id.isNullOrBlank() && !d.imdbId.isNullOrBlank()) {
            val f = get("/find/${d.imdbId}?external_source=imdb_id", key)
            val arr = f?.optJSONArray(if (kind == "tv") "tv_results" else "movie_results")
            id = arr?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
        }
        if (id.isNullOrBlank()) {
            val yearParam = if (d.year > 0) (if (kind == "tv") "&first_air_date_year=" else "&year=") + d.year else ""
            val s = get("/search/$kind?query=${enc(d.title)}$yearParam", key)
            id = s?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
        }
        if (id.isNullOrBlank()) return@withContext emptyList()

        data class Cand(val key: String, val type: Int, val lang: Int, val published: String)
        val cands = LinkedHashMap<String, Cand>()
        fun add(v: JSONObject?) {
            val list = v?.optJSONArray("results") ?: return
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                if (o.optString("site") != "YouTube") continue
                val k = o.optString("key").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: continue
                val type = when (o.optString("type")) {
                    "Trailer" -> if (o.optBoolean("official")) 0 else 1
                    "Teaser" -> 2
                    else -> continue
                }
                val lang = if (o.optString("iso_639_1") == "en") 0 else 1
                if (k !in cands) cands[k] = Cand(k, type, lang, o.optString("published_at"))
            }
        }
        add(get("/$kind/$id/videos?include_video_language=en,null", key))
        if (cands.size < 5) add(get("/$kind/$id/videos", key))
        cands.values.sortedWith(compareBy<Cand>({ it.type }, { it.lang }).thenByDescending { it.published }).map { it.key }
    }
}
