package com.cstark.starktv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Backup trailers for titles Plex has none for. Looks the title up on TMDB (using the
 * TMDB/IMDb ids Plex already stores) and returns a YouTube video id.
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

    /** Returns a YouTube video id for the best trailer, or null. */
    suspend fun trailerYoutubeId(ctx: Context, d: DetailData): String? = withContext(Dispatchers.IO) {
        val key = key(ctx)
        if (key.isBlank()) return@withContext null
        val kind = if (d.item.type == "show") "tv" else "movie"

        var id = d.tmdbId
        if (id.isNullOrBlank() && !d.imdbId.isNullOrBlank()) {
            val f = get("/find/${d.imdbId}?external_source=imdb_id", key)
            val arr = f?.optJSONArray(if (kind == "tv") "tv_results" else "movie_results")
            id = arr?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
        }
        if (id.isNullOrBlank()) {
            val yearParam = if (d.item.year > 0) (if (kind == "tv") "&first_air_date_year=" else "&year=") + d.item.year else ""
            val s = get("/search/$kind?query=${enc(d.item.title)}$yearParam", key)
            id = s?.optJSONArray("results")?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
        }
        if (id.isNullOrBlank()) return@withContext null

        val v = get("/$kind/$id/videos?include_video_language=en,null", key)
            ?: get("/$kind/$id/videos", key)
        val list = v?.optJSONArray("results") ?: return@withContext null
        data class Cand(val key: String, val score: Int)
        var best: Cand? = null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optString("site") != "YouTube") continue
            val type = o.optString("type")
            var score = when (type) {
                "Trailer" -> 100
                "Teaser" -> 40
                else -> continue
            }
            if (o.optBoolean("official")) score += 20
            if (o.optString("iso_639_1") == "en") score += 10
            if (best == null || score > best.score) best = Cand(o.optString("key"), score)
        }
        best?.key?.takeIf { it.isNotBlank() }
    }
}

/** Plays a YouTube video in the YouTube app (ExoPlayer can't play YouTube directly). */
fun openYoutube(ctx: Context, id: String) {
    val tries = listOf("vnd.youtube://$id", "https://www.youtube.com/watch?v=$id")
    for (u in tries) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // try the next one
        }
    }
    Toast.makeText(ctx, "YouTube isn't installed on this device", Toast.LENGTH_LONG).show()
}
