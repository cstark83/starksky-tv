package com.cstark.starktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Builds the Discover rows for the current profile: titles similar to what this profile
 * watched recently (from Plex), plus what's trending. Anything already requested or
 * available is left out.
 */
suspend fun loadDiscover(api: PlexApi, ov: OverseerrApi, home: HomeData) {
    if (!ov.ensure()) {
        home.discoverError = ov.status
        return
    }
    val rows = mutableListOf<Pair<String, List<Media>>>()

    val seeds = (home.rows ?: emptyList())
        .filter { it.title == "Recently Watched" || it.title == "Continue Watching" }
        .flatMap { it.items }
        .distinctBy { it.showKey }
        .take(4)
    for (s in seeds) {
        try {
            val d = api.detail(s.showKey)
            val id = d.tmdbId?.toIntOrNull() ?: continue
            val kind = if (s.type == "movie") "movie" else "tv"
            val recs = ov.recommendations(kind, id).filter { it.status < 2 }.take(15)
            if (recs.isNotEmpty()) rows += "Because you watched ${s.label}" to recs
        } catch (e: Exception) {
            // skip this seed
        }
    }
    val trending = ov.trending().filter { it.status < 2 }.take(20)
    if (trending.isNotEmpty()) rows += "Trending" to trending
    val mine = ov.recentRequests()
    if (mine.isNotEmpty()) rows += "Recent requests" to mine

    home.discover = rows
    home.discoverError = null
}

@Composable
fun MediaCard(m: Media, fr: FocusRequester? = null, onClick: () -> Unit) {
    Column(Modifier.width(150.dp)) {
        Focusable(Modifier.size(150.dp, 225.dp), fr, 1.06f, onClick) {
            AsyncImage(
                model = tmdbImg(m.poster, "w342"),
                contentDescription = m.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(Pal.Card),
            )
            val label = m.statusLabel()
            if (label.isNotEmpty()) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        if (m.year.isNotEmpty()) Text(m.year, fontSize = 12.sp, color = Pal.Dim)
    }
}

@Composable
fun DiscoverScreen(home: HomeData, onRequest: (Media) -> Unit) {
    val rows = home.discover
    if (rows == null) { Centered(home.discoverError ?: "Finding things you might like…"); return }
    if (rows.isEmpty()) { Centered("Nothing to suggest yet. Watch a few things and check back."); return }

    val first = remember { FocusRequester() }
    LaunchedEffect(rows.size) { runCatching { first.requestFocus() } }

    LazyColumn(
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(rows) { ri, row ->
            Column {
                Text(
                    row.first,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(row.second) { ii, m ->
                        MediaCard(m, if (ri == 0 && ii == 0) first else null) { onRequest(m) }
                    }
                }
            }
        }
    }
}

@Composable
fun RequestScreen(api: PlexApi, ov: OverseerrApi, media: Media, onClose: () -> Unit) {
    var status by remember { mutableIntStateOf(media.status) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var wlKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(media.id) {
        wlKey = api.watchlist().firstOrNull { it.tmdbId == media.id.toString() }?.ratingKey
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = tmdbImg(media.backdrop, "w1280"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.22f),
        )
        Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            AsyncImage(
                model = tmdbImg(media.poster, "w500"),
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(220.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Pal.Card),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(media.title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    listOf(if (media.type == "tv") "TV show" else "Movie", media.year).filter { it.isNotEmpty() }.joinToString("  ·  "),
                    fontSize = 16.sp,
                    color = Pal.Dim,
                )
                Text(media.overview, fontSize = 16.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                val label = Media(media.id, media.type, media.title, media.year, "", null, null, status).statusLabel()
                if (label.isNotEmpty()) Text(label, fontSize = 16.sp, color = Pal.Dim)
                if (msg.isNotEmpty()) Text(msg, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (status < 2) {
                        Chip(if (busy) "Requesting…" else if (media.type == "tv") "Request all seasons" else "Request", fr = focus) {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val (ok, text) = ov.request(media)
                                    msg = text
                                    if (ok) status = 2
                                    busy = false
                                }
                            }
                        }
                        Chip("Back", onClick = onClose)
                    } else {
                        Chip("Back", fr = focus, onClick = onClose)
                    }
                }
                Chip(if (wlKey != null) "Remove from Watchlist" else "Add to Watchlist") {
                    scope.launch {
                        val existing = wlKey
                        if (existing != null) {
                            if (api.setWatchlist(existing, false)) wlKey = null else msg = "Couldn't update your Watchlist."
                        } else {
                            val k = api.discoverKeyFor(media.title, media.year.toIntOrNull() ?: 0, media.type, media.id.toString())
                            if (k != null && api.setWatchlist(k, true)) wlKey = k else msg = "Couldn't add that to your Watchlist."
                        }
                    }
                }
            }
        }
    }
}
