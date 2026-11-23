package com.cstark.starktv

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
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

/** Discover tab state; kept outside the screen so it survives opening a title and coming back. */
object DiscoverUi {
    var mode by mutableIntStateOf(0) // 0 for you, 1 search, 2 genres
    var query by mutableStateOf("")
    var searchedFor by mutableStateOf("")
    var searchResults by mutableStateOf<List<Media>>(emptyList())
    var searchStatus by mutableStateOf("")
    var genreKind by mutableStateOf("movie")
    var genres by mutableStateOf<Map<String, List<Pair<Int, String>>>>(emptyMap())
    var selected by mutableStateOf<Set<Int>>(emptySet())
    var genreResults by mutableStateOf<List<Media>>(emptyList())
    var genreStatus by mutableStateOf("")
}

@Composable
fun DiscoverScreen(home: HomeData, ov: OverseerrApi, onRequest: (Media) -> Unit) {
    val s = DiscoverUi
    Column(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) NavMemory.restoreTo = "discover" }) {
        // Up from these chips goes to the Discover tab chip (not the nearest chip, the profile one)
        Row(
            Modifier.padding(start = 48.dp, top = 8.dp, bottom = 12.dp).focusProperties { up = home.discoverChipFr },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Chip("For you", s.mode == 0, compact = true) { s.mode = 0 }
            Chip("Search", s.mode == 1, compact = true) { s.mode = 1 }
            Chip("Genres", s.mode == 2, compact = true) { s.mode = 2 }
        }
        Box(Modifier.weight(1f)) {
            when (s.mode) {
                1 -> DiscoverSearch(ov, onRequest)
                2 -> DiscoverGenres(ov, onRequest)
                else -> DiscoverRows(home, onRequest)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DiscoverSearch(ov: OverseerrApi, onRequest: (Media) -> Unit) {
    val s = DiscoverUi
    val ctx = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) s.query = said
    }
    LaunchedEffect(s.query) {
        val q = s.query.trim()
        if (q == s.searchedFor) return@LaunchedEffect
        if (q.length < 2) {
            s.searchResults = emptyList()
            s.searchStatus = ""
            s.searchedFor = q
            return@LaunchedEffect
        }
        delay(350)
        s.searchStatus = "Searching…"
        s.searchResults = if (ov.ensure()) ov.search(q) else emptyList()
        s.searchedFor = q
        s.searchStatus = if (s.searchResults.isEmpty()) (if (ov.base.isEmpty()) ov.status else "No results for \"$q\"") else ""
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicTextField(
                value = s.query,
                onValueChange = { s.query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(field)
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                            kb?.show()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        if (s.query.isEmpty()) Text("Search movies and shows to request", color = Pal.Dim, fontSize = 24.sp)
                        inner()
                    }
                },
            )
            Chip("Speak") {
                try {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search to request")
                    speech.launch(i)
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Voice search isn't available on this device", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (s.searchStatus.isNotEmpty()) Text(s.searchStatus, fontSize = 18.sp, color = Pal.Dim)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(166.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(s.searchResults, key = { "s" + it.type + it.id }) { m -> MediaCard(m) { onRequest(m) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverGenres(ov: OverseerrApi, onRequest: (Media) -> Unit) {
    val s = DiscoverUi
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(s.genreKind) {
        if (s.genres[s.genreKind].isNullOrEmpty()) {
            s.genreStatus = "Loading genres…"
            val list = ov.genres(s.genreKind)
            s.genres = s.genres + (s.genreKind to list)
            s.genreStatus = if (list.isEmpty()) (if (ov.base.isEmpty()) ov.status else "Couldn't load genres") else ""
        }
    }
    fun setKind(k: String) {
        if (k == s.genreKind) return
        s.genreKind = k
        s.selected = emptySet()
        s.genreResults = emptyList()
        s.genreStatus = ""
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(166.dp),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "kind", span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Movies", s.genreKind == "movie", compact = true) { setKind("movie") }
                Chip("TV", s.genreKind == "tv", compact = true) { setKind("tv") }
            }
        }
        item(key = "genres", span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, name) in s.genres[s.genreKind].orEmpty()) {
                    val on = id in s.selected
                    Chip(if (on) "✓ $name" else name, on, compact = true) {
                        s.selected = if (on) s.selected - id else s.selected + id
                    }
                }
            }
        }
        item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(if (busy) "Searching…" else "Show results", compact = true) {
                    if (busy) return@Chip
                    if (s.selected.isEmpty()) {
                        s.genreStatus = "Pick at least one genre"
                        return@Chip
                    }
                    busy = true
                    scope.launch {
                        val n = s.selected.size
                        s.genreResults = ov.discoverByGenres(s.genreKind, s.selected)
                        s.genreStatus = if (s.genreResults.isEmpty()) "Nothing matches all $n genres" else ""
                        busy = false
                    }
                }
                Chip("Clear", compact = true) {
                    s.selected = emptySet()
                    s.genreResults = emptyList()
                    s.genreStatus = ""
                }
                val n = s.selected.size
                Text(
                    if (s.genreStatus.isNotEmpty()) s.genreStatus else if (n > 1) "$n genres · titles must match all" else "",
                    fontSize = 14.sp,
                    color = Pal.Dim,
                )
            }
        }
        items(s.genreResults, key = { "g" + it.type + it.id }) { m -> MediaCard(m) { onRequest(m) } }
    }
}

@Composable
private fun DiscoverRows(home: HomeData, onRequest: (Media) -> Unit) {
    val rows = home.discover
    if (rows == null) { Centered(home.discoverError ?: "Finding things you might like…"); return }
    if (rows.isEmpty()) { Centered("Nothing to suggest yet. Watch a few things and check back."); return }

    val first = remember { FocusRequester() }
    // coming Back to Main with focus owed elsewhere (e.g. the Search chip): don't take it
    val restoring = remember { NavMemory.pending }
    LaunchedEffect(rows.size) {
        if (restoring == null || NavMemory.take("discover")) runCatching { first.requestFocus() }
    }

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
                    if (ov.restricted) {
                        Chip("Back", fr = focus, onClick = onClose)
                        Text("Requests are off for this profile.", fontSize = 16.sp, color = Pal.Dim)
                    } else if (status < 2) {
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
