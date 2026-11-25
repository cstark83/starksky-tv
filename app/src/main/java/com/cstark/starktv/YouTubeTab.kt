package com.cstark.starktv

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/*
 * In-app YouTube (the "YouTube" tab): trending + search through NewPipeExtractor, played in Starsky's own player with
 * the trailer source chain (in-app extraction up to 1080p, then Tower's yt-dlp). Never opens the YouTube app, writes
 * nothing to Plex. The Plex "YouTube" library stays one press away ("My YouTube library").
 */

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    /** Seconds; -1 = live / unknown. */
    val durationSec: Long,
    /** -1 = unknown. */
    val views: Long,
    val age: String?,
    val thumb: String?,
)

class YtCat(val id: String, val label: String, val items: List<YtVideo>, val url: String?, val next: Page?)

private val YT_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""")
private const val YT_LIST_MS = 15_000L
/** Whole video start (in-app extraction + start, then Tower), as the spinner promises. */
private const val YT_PLAY_MS = 25_000L
private const val KIOSK_CACHE_MS = 30 * 60_000L
private const val MAX_RECENT = 10

/** Trending kiosks NewPipe offers for YouTube (YouTube's own classic Trending page was retired; kept in case it returns). */
private val KIOSKS = listOf(
    "trending_podcasts_episodes" to "News & Podcasts",
    "trending_gaming" to "Gaming",
    "trending_music" to "Music",
    "trending_movies_and_shows" to "Movies & TV",
    "Trending" to "Trending (classic)",
    "live" to "Live",
)

private fun ylog(ctx: Context, msg: String) = PlayLog.add(ctx, "youtube: $msg")

private fun toVideo(i: InfoItem): YtVideo? {
    val s = i as? StreamInfoItem ?: return null
    val id = YT_ID.find(s.url ?: "")?.groupValues?.get(1) ?: return null
    val th = s.thumbnails ?: emptyList()
    val thumb = (th.filter { it.height in 180..480 }.minByOrNull { it.height } ?: th.maxByOrNull { it.height })?.url
    return YtVideo(id, s.name ?: "", s.uploaderName ?: "", s.duration, s.viewCount, s.textualUploadDate, thumb)
}

/** Blocking NewPipe call with a hard time limit (the thread is interrupted, so the HTTP call stops too). */
private suspend fun <T> np(ms: Long, block: () -> T): T? = withTimeoutOrNull(ms) {
    runInterruptible(Dispatchers.IO) {
        YouTube.init()
        block()
    }
}

private suspend fun loadKiosks(): List<YtCat> = coroutineScope {
    val yt = ServiceList.YouTube
    val cats = KIOSKS.map { (id, label) ->
        async {
            runCatching {
                np(YT_LIST_MS) {
                    val url = yt.kioskList.getListLinkHandlerFactoryByType(id).fromId(id).url
                    val ki = KioskInfo.getInfo(yt, url)
                    YtCat(id, label, ki.relatedItems.mapNotNull { toVideo(it) }, url, if (ki.hasNextPage()) ki.nextPage else null)
                }
            }.getOrNull()
        }
    }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
    // "Trending": every trending kiosk mixed (one from each in turn), no duplicates; live streams stay in "Live"
    val mixFrom = cats.filter { it.id != "live" }
    val seen = HashSet<String>()
    val mixed = mutableListOf<YtVideo>()
    var i = 0
    while (mixFrom.any { i < it.items.size }) {
        for (c in mixFrom) c.items.getOrNull(i)?.let { if (seen.add(it.id)) mixed += it }
        i++
    }
    if (mixed.isEmpty()) cats else listOf(YtCat("all", "Trending", mixed, null, null)) + cats
}

/** Everything the tab shows; kept while a video plays, so Back returns to the same list, scroll and video. */
private object YtMem {
    var cats by mutableStateOf<List<YtCat>>(emptyList())
    var catsAt = 0L
    /** Category id on screen, or "search". */
    var cat by mutableStateOf("all")
    var query by mutableStateOf("")
    var searched by mutableStateOf("")
    var items by mutableStateOf<List<YtVideo>>(emptyList())
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var loadingMore by mutableStateOf(false)
    var searchQH: SearchQueryHandler? = null
    var pageUrl: String? = null
    var next: Page? = null
    var grid = LazyGridState()
    var focusId: String? = null
    var showLibrary by mutableStateOf(false)

    fun show(c: YtCat) {
        cat = c.id; items = c.items; next = c.next; pageUrl = c.url; searchQH = null
        status = ""; grid = LazyGridState(); focusId = null
    }
}

private fun recentPrefs(ctx: Context) = ctx.getSharedPreferences("youtube", Context.MODE_PRIVATE)

private fun loadRecent(ctx: Context): List<String> = runCatching {
    val a = JSONArray(recentPrefs(ctx).getString("recent", "[]"))
    (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

private fun saveRecent(ctx: Context, list: List<String>) {
    recentPrefs(ctx).edit().putString("recent", JSONArray(list).toString()).apply()
}

fun fmtDuration(sec: Long): String {
    if (sec < 0) return "LIVE"
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtViews(v: Long): String? = when {
    v < 0 -> null
    v >= 1_000_000_000 -> "%.1fB views".format(v / 1e9).replace(".0B", "B")
    v >= 1_000_000 -> "%.1fM views".format(v / 1e6).replace(".0M", "M")
    v >= 1_000 -> "%.0fK views".format(v / 1e3)
    else -> plural(v.toInt(), "view")
}

/**
 * The YouTube tab. Kid profiles never get here (they keep the Plex library only, see MainScreen).
 * [library] = the Plex "YouTube" library screen, shown by "My YouTube library"; Back from it returns here.
 */
@Composable
fun YouTubeTab(onVideo: (YtVideo) -> Unit, library: @Composable () -> Unit) {
    if (YtMem.showLibrary) {
        BackHandler { YtMem.showLibrary = false; NavMemory.pending = "yt:lib" }
        library()
    } else {
        YouTubeBrowse(onVideo)
    }
}

@Composable
private fun YouTubeBrowse(onVideo: (YtVideo) -> Unit) {
    val ctx = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val tv = tvDims()
    val m = YtMem
    var recent by remember { mutableStateOf(loadRecent(ctx)) }
    var fieldFocused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val libFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }
    val returning = remember { NavMemory.take("yt") }
    val fromLibrary = remember { NavMemory.take("yt:lib") }
    val restoreId = remember { if (returning) m.focusId else null }

    fun search(q0: String) {
        val q = q0.trim()
        if (q.length < 2) return
        m.query = q
        recent = (listOf(q) + recent.filter { !it.equals(q, true) }).take(MAX_RECENT).also { saveRecent(ctx, it) }
        scope.launch {
            m.cat = "search"; m.searched = q; m.items = emptyList(); m.next = null; m.pageUrl = null
            m.grid = LazyGridState(); m.focusId = null
            m.loading = true; m.status = "Searching YouTube…"
            val t0 = now()
            try {
                val yt = ServiceList.YouTube
                val res = np(YT_LIST_MS) {
                    val qh = yt.searchQHFactory.fromQuery(q, listOf("videos"), "")
                    qh to SearchInfo.getInfo(yt, qh)
                }
                if (res == null) {
                    m.status = "YouTube didn't answer in time. Try again."
                    ylog(ctx, "search timed out after ${now() - t0} ms")
                } else {
                    val (qh, si) = res
                    m.searchQH = qh
                    m.items = si.relatedItems.mapNotNull { toVideo(it) }.distinctBy { it.id }
                    m.next = if (si.hasNextPage()) si.nextPage else null
                    m.status = if (m.items.isEmpty()) "No videos found for \"$q\"" else ""
                    ylog(ctx, "search: ${m.items.size} videos in ${now() - t0} ms")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                m.status = "Search failed: ${e.message?.take(100) ?: e.javaClass.simpleName}"
                ylog(ctx, "search failed after ${now() - t0} ms: ${e.message?.take(120)}")
            } finally {
                m.loading = false
            }
        }
    }

    fun showCat(id: String) {
        m.cats.firstOrNull { it.id == id }?.let { m.show(it) }
    }

    fun loadMore() {
        val page = m.next ?: return
        if (m.loadingMore || m.loading) return
        m.loadingMore = true
        scope.launch {
            try {
                val yt = ServiceList.YouTube
                val qh = m.searchQH
                val url = m.pageUrl
                val got = np(YT_LIST_MS) {
                    when {
                        qh != null -> SearchInfo.getMoreItems(yt, qh, page).let { it.items.mapNotNull { i -> toVideo(i) } to it.nextPage }
                        url != null -> KioskInfo.getMoreItems(yt, url, page).let { it.items.mapNotNull { i -> toVideo(i) } to it.nextPage }
                        else -> null
                    }
                }
                if (got != null && m.next == page) {
                    val have = m.items.map { it.id }.toHashSet()
                    m.items = m.items + got.first.filter { have.add(it.id) }
                    m.next = got.second?.takeIf { got.first.isNotEmpty() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ylog(ctx, "more results failed: ${e.message?.take(120)}")
            } finally {
                m.loadingMore = false
            }
        }
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) search(said)
    }

    // trending: loaded once, kept 30 min
    LaunchedEffect(Unit) {
        if (m.cats.isNotEmpty() && System.currentTimeMillis() - m.catsAt < KIOSK_CACHE_MS) return@LaunchedEffect
        if (m.cat != "search") { m.loading = true; m.status = "Loading trending videos…" }
        val t0 = now()
        val cats = try { loadKiosks() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        ylog(ctx, "trending: ${cats.joinToString { "${it.id} ${it.items.size}" }} in ${now() - t0} ms")
        if (cats.isNotEmpty()) { m.cats = cats; m.catsAt = System.currentTimeMillis() }
        if (m.cat != "search") {
            m.loading = false
            val c = cats.firstOrNull { it.id == m.cat } ?: cats.firstOrNull()
            if (c != null) m.show(c) else m.status = "Couldn't load trending videos. Search still works."
        }
    }
    // Back from a video: same list and scroll, focus on the video you played
    LaunchedEffect(Unit) {
        if (returning && restoreId != null) focusSoon(restoreFr)
        else if (fromLibrary) focusSoon(libFr)
    }
    // paging: near the end of the list, fetch the next page
    LaunchedEffect(m.grid) {
        snapshotFlow { m.grid.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) to it.totalItemsCount } }
            .collect { (last, total) -> if (total > 0 && last >= total - 8) loadMore() }
    }
    // on-screen keyboard closed with a new query: search it (same as its Search key)
    LaunchedEffect(Unit) {
        var shown = false
        while (true) {
            val nowShown = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (shown && !nowShown && fieldFocused && m.query.trim().length >= 2 && m.query.trim() != m.searched) search(m.query)
            shown = nowShown
            delay(200)
        }
    }

    LazyVerticalGrid(
        state = m.grid,
        columns = GridCells.Adaptive(200.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = tv.side, end = tv.side, top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicTextField(
                    value = m.query,
                    onValueChange = { m.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { kb?.hide(); search(m.query) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(field)
                        .onFocusChanged { fieldFocused = it.isFocused }
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
                                .border(if (fieldFocused) 3.dp else 0.dp, if (fieldFocused) accent else Color.Transparent, RoundedCornerShape(10.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            if (m.query.isEmpty()) Text("Search YouTube", color = Pal.Dim, fontSize = 20.sp)
                            inner()
                        }
                    },
                )
                Chip("Speak") {
                    try {
                        speech.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search YouTube"),
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Voice search isn't available on this device", Toast.LENGTH_LONG).show()
                    }
                }
                Chip("My YouTube library", fr = libFr) { m.showLibrary = true }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Recent", fontSize = 14.sp, color = Pal.Dim, modifier = Modifier.padding(end = 4.dp))
                    recent.forEach { q -> Chip(q, m.cat == "search" && m.searched == q, compact = true) { search(q) } }
                    Chip("Clear", compact = true) { recent = emptyList(); saveRecent(ctx, recent) }
                }
            }
        }
        item(key = "cats", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (m.cat == "search") Chip("Results: ${m.searched}", true, compact = true) { }
                m.cats.forEach { c -> Chip(c.label, m.cat == c.id, compact = true) { showCat(c.id) } }
            }
        }
        if (m.status.isNotEmpty() || m.loading) {
            item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (m.loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    Text(m.status, fontSize = 16.sp, color = Pal.Dim)
                }
            }
        }
        itemsIndexed(m.items, key = { _, v -> "v" + v.id }) { _, v ->
            YtCard(
                v,
                fr = if (v.id == restoreId) restoreFr else null,
                onFocus = { m.focusId = v.id; NavMemory.restoreTo = "yt" },
            ) { m.focusId = v.id; NavMemory.restoreTo = "yt"; onVideo(v) }
        }
        if (m.loadingMore) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Text("Loading more…", fontSize = 14.sp, color = Pal.Dim, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
private fun YtCard(v: YtVideo, fr: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth(), fr = fr, scaleOnFocus = 1.05f, onClick = onClick, onFocus = onFocus) { f ->
        Column(Modifier.fillMaxWidth().background(if (f) Color(0x33FFFFFF) else Color.Transparent).padding(bottom = 6.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Pal.Card)) {
                if (v.thumb != null) {
                    AsyncImage(model = v.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(
                    fmtDuration(v.durationSec),
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(if (v.durationSec < 0) Color(0xCCCC0000) else Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Text(
                v.title,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp),
            )
            Text(v.channel, fontSize = 12.sp, color = Pal.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            val meta = listOfNotNull(fmtViews(v.views), v.age?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, fontSize = 12.sp, color = Pal.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- player

/**
 * Plays one YouTube video in Starsky's own player: in-app extraction (up to 1080p), then Tower's yt-dlp, 25 s in all.
 * Same controls as trailers (D-pad left/right with the bar hidden: 15 s back / 10 s forward). Nothing goes to Plex.
 * Back = the app's Back handler (returns to the list, focus on this video).
 */
@OptIn(UnstableApi::class)
@Composable
fun YtVideoScreen(id: String, title: String) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).setSeekBackIncrementMs(15_000L).setSeekForwardIncrementMs(10_000L).build() }
    val counter = remember { ByteCounter() }
    val dsf = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Starsky TV")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setTransferListener(counter)
    }
    var status by remember { mutableStateOf("Loading video…") }
    var loading by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf("") }
    var tries by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    val retryFr = remember { FocusRequester() }
    DisposableEffect(Unit) {
        NpDownloader.sink = { ylog(ctx, it) }
        // a stream that breaks after it started: error screen with Retry (instead of a frozen picture)
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (started) {
                    val why = describe(error)
                    ylog(ctx, "playback of $id stopped: $why")
                    started = false
                    detail = why
                    failed = "Playback stopped"
                }
            }
        }
        player.addListener(l)
        onDispose {
            NpDownloader.sink = null
            player.removeListener(l)
            player.release()
        }
    }

    LaunchedEffect(tries) {
        loading = true; started = false; failed = null; detail = ""
        val reasons = mutableListOf<String>()
        val t0 = now()
        val deadline = t0 + YT_PLAY_MS
        fun left() = deadline - now()
        ylog(ctx, "start $id \"${title.take(60)}\" (try ${tries + 1})")

        suspend fun play(src: String, p: Playable, maxMs: Long): Boolean {
            val r = tryPlay(player, dsf, counter, p, minOf(10_000L, maxMs), maxMs)
            if (r.ok) ylog(ctx, "phase player-ready $src ${p.label} from ${p.host}: ${r.ms} ms")
            else { ylog(ctx, "phase player $src ${p.label} from ${p.host} failed after ${r.ms} ms: ${r.reason}"); reasons += "$src: ${r.reason}" }
            return r.ok
        }

        var ok = false
        // 1) in the app (cached link first; Retry always resolves again)
        if (tries > 0) YouTube.drop(id)
        val cachedHit = YouTube.cached(id)
        val e0 = now()
        var badVideo = false
        val res = cachedHit ?: try {
            withTimeoutOrNull(15_000L) { runInterruptible(Dispatchers.IO) { YouTube.resolve(id) } }
                .also { if (it == null) { ylog(ctx, "phase newpipe-extract $id: timed out after ${now() - e0} ms"); reasons += "in-app: timed out" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message?.take(120) ?: e.javaClass.simpleName
            badVideo = unplayable(e)
            ylog(ctx, "phase newpipe-extract $id failed after ${now() - e0} ms: $msg")
            reasons += "in-app: $msg"
            null
        }
        if (res != null) {
            ylog(ctx, "phase newpipe-extract $id: ${if (cachedHit != null) "cached" else "${now() - e0} ms"} -> ${res.kind} ${res.height}p")
            ok = play("in-app", YouTube.playable(res), (left() - 6_000L).coerceAtLeast(6_000L))
            if (!ok) YouTube.drop(id)
        }
        // 2) Tower (yt-dlp), with what is left of the 25 s
        if (!ok && left() > 2_000L) {
            status = "Loading video (Tower)…"
            val w0 = now()
            val tower = try {
                withTimeoutOrNull(left()) { towerPlayables(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ylog(ctx, "phase tower-resolve $id failed after ${now() - w0} ms: ${e.message?.take(120)}")
                reasons += "Tower: ${e.message?.take(80)}"
                null
            }
            if (tower != null) {
                ylog(ctx, "phase tower-resolve $id: ${now() - w0} ms, ${tower.size} stream(s)")
                for (p in tower) {
                    if (left() < 1_500L) break
                    if (play("Tower", p, left())) { ok = true; break }
                }
            } else if (now() - w0 >= 1_000L && reasons.none { it.startsWith("Tower") }) {
                reasons += "Tower: no answer in time"
            }
        }
        loading = false
        if (ok) {
            started = true
            ylog(ctx, "playing $id after ${now() - t0} ms")
            view?.requestFocus()
        } else {
            detail = reasons.joinToString("  ·  ").ifBlank { "no source answered" }
            failed = if (badVideo) "This video can't be played (YouTube says it's unavailable)" else "This video isn't available right now"
            ylog(ctx, "none worked for $id after ${now() - t0} ms: $detail")
        }
    }
    LaunchedEffect(failed) { if (failed != null) focusSoon(retryFr) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (failed == null) {
            AndroidView(
                factory = { c ->
                    object : PlayerView(c) {
                        // bar hidden: left/right seek right away (15 s back / 10 s forward) instead of only showing the bar
                        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                            val k = event.keyCode
                            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && !isControllerFullyVisible && started) {
                                if (event.action == KeyEvent.ACTION_DOWN) {
                                    if (k == KeyEvent.KEYCODE_DPAD_LEFT) player.seekBack() else player.seekForward()
                                }
                                return true
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        this.player = player
                        useController = true
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        view = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (loading) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(56.dp), color = Color.White)
                Text(title, fontSize = 20.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(status, fontSize = 16.sp, color = Pal.Dim)
            }
        }
        val f = failed
        if (f != null) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(f, fontSize = 28.sp, color = Color.White, textAlign = TextAlign.Center)
                Text(title, fontSize = 16.sp, color = Pal.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Chip("Retry", fr = retryFr) { tries++ }
                Text("Press Back to return to the list.", fontSize = 14.sp, color = Pal.Dim)
                Text(detail, fontSize = 12.sp, color = Pal.Dim, textAlign = TextAlign.Center, maxLines = 4)
            }
        }
    }
}
