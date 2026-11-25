package com.cstark.starktv

import androidx.compose.foundation.lazy.LazyListState
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// ---------------------------------------------------------------- clock + weather

@Composable
fun ClockBlock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val d = Date(now)
    val loc = Locale.getDefault()
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(SimpleDateFormat("h:mm", loc).format(d), fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
            Text(
                SimpleDateFormat("a", loc).format(d),
                fontSize = 14.sp,
                color = Pal.Dim,
                modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
            )
        }
        Text(SimpleDateFormat("EEEE, MMM d", loc).format(d), fontSize = 14.sp, color = Pal.Dim, letterSpacing = 1.sp)
    }
}

/** Temperature + conditions, top right. Focusable: OK (a normal press, no hold menu) opens the weather page. */
@Composable
fun WeatherBlock(prefs: Prefs, onOpen: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        loadCachedWeather(prefs) // last saved answer at once
        while (true) {
            if (System.currentTimeMillis() - WeatherState.lastFetch > 10 * 60_000L) refreshWeather(prefs)
            delay(20 * 60_000L)
        }
    }
    // back from the weather page: focus returns here (HomeScreen skips its first-row focus while this is set)
    LaunchedEffect(Unit) {
        if (WeatherState.returnFocus) {
            delay(150)
            runCatching { fr.requestFocus() }
            WeatherState.returnFocus = false
            NavMemory.take("weather")
        }
    }
    val w = WeatherState.weather
    Focusable(fr = fr, scaleOnFocus = 1.06f, onClick = onOpen, onFocus = { NavMemory.restoreTo = "weather" }) { f ->
        Column(
            Modifier
                .background(if (f) Color(0x33FFFFFF) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                if (w == null) "—" else w.temp(prefs.celsius),
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                if (w == null) "" else w.label,
                fontSize = 14.sp,
                color = if (f) Color.White else Pal.Dim,
            )
        }
    }
}

@Composable
fun TopBar(prefs: Prefs, home: HomeData, onSearch: () -> Unit, onProfile: () -> Unit, onWeather: () -> Unit, onSpotify: () -> Unit) {
    val searchFr = remember { FocusRequester() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Back from Search: focus returns to the Search chip (after the tab content's own first focus)
    LaunchedEffect(Unit) {
        if (NavMemory.pending == "chip:Search") {
            delay(150)
            if (NavMemory.take("chip:Search")) focusSoon(searchFr)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = tvDims().side, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ClockBlock()
        Row(
            Modifier.weight(1f).padding(top = 8.dp).padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Back from the picker returns to Home, on the poster you were on (NavMemory "home")
            Chip(prefs.userName ?: "Profile", false, compact = true) { NavMemory.restoreTo = "home"; onProfile() }
            Chip("Home", home.tab == 0, compact = true) { home.tab = 0 }
            home.sections.forEachIndexed { i, s -> Chip(s.title, home.tab == i + 1, compact = true) { home.tab = i + 1 } }
            Chip("Search", false, compact = true, fr = searchFr, onFocus = { NavMemory.restoreTo = "chip:Search" }, onClick = onSearch)
            Chip("Spotify", false, compact = true, onClick = onSpotify)
            if (!prefs.restricted) Chip("Discover", home.tab == home.discoverTab, compact = true, fr = home.discoverChipFr) { home.tab = home.discoverTab } // kid profiles: no Discover
            // Update: installs right away (Tower first, this TV's own installer as fallback); see UpdatePanel.kt
            if (Updater.newBuild > 0) Chip("Update", false, compact = true) { Updater.startUpdateNow(ctx, scope) }
            Chip("Settings", home.tab == home.settingsTab, compact = true) { home.tab = home.settingsTab }
        }
        WeatherBlock(prefs, onWeather)
    }
}

// ---------------------------------------------------------------- background + accent

private fun backdropPath(item: Item?): String? = item?.let { it.art ?: it.thumb }

@Composable
fun HeroBackground(api: PlexApi, item: Item?) {
    val t = rememberInfiniteTransition(label = "pan")
    val zoom by t.animateFloat(
        1.0f, 1.10f,
        infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0B0E))) {
        Crossfade(targetState = backdropPath(item), animationSpec = tween(700), label = "bg") { p ->
            if (p != null) {
                AsyncImage(
                    model = api.pic(p, 1920, 1080),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color(0xE60A0B0E), Color(0x990A0B0E), Color(0x330A0B0E))),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x990A0B0E), Color.Transparent, Color(0xF20A0B0E))),
            ),
        )
    }
}

fun pickAccent(bmp: Bitmap): Color {
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch ?: return Pal.Accent
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(sw.rgb, hsl)
    hsl[1] = max(hsl[1], 0.45f)
    hsl[2] = hsl[2].coerceIn(0.55f, 0.72f)
    return Color(ColorUtils.HSLToColor(hsl))
}

@Composable
fun rememberAccent(api: PlexApi, item: Item?): Color {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(Pal.Accent) }
    LaunchedEffect(item?.ratingKey) {
        val p = backdropPath(item)
        val url = api.pic(p, 192, 108)
        if (url == null) {
            target = Pal.Accent
            return@LaunchedEffect
        }
        val req = ImageRequest.Builder(ctx).data(url).allowHardware(false).build()
        val res = ctx.imageLoader.execute(req)
        val bmp = (res.drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        target = withContext(Dispatchers.Default) { pickAccent(bmp) }
    }
    val animated by animateColorAsState(target, tween(800), label = "accent")
    return animated
}

// ---------------------------------------------------------------- hero info + home

private fun fmtClock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

@Composable
private fun HeroInfo(
    item: Item,
    deck: Boolean,
    external: Boolean,
    onPlay: (Item) -> Unit,
    onDetail: (String) -> Unit,
    onMarkWatched: () -> Unit,
    onRemove: () -> Unit,
    onExternal: () -> Unit,
    space: Dp = 400.dp,
) {
    // Fit the hero area Home gives us: a smaller title on one line when space is short, the synopsis only with room
    val roomy = space >= 190.dp
    val titleSize = if (space >= 170.dp) 52.sp else 40.sp
    val showSummary = space >= 240.dp
    val playable = item.type == "episode" || item.type == "movie"
    val remaining = if (item.duration > 0) item.duration - item.viewOffset else 0L
    val started = item.resumable
    val line1 = when (item.type) {
        "episode" -> "${item.epCode}  ·  ${item.title}"
        else -> listOfNotNull(item.year.takeIf { it > 0 }?.toString(), item.contentRating).joinToString("  ·  ")
    }
    val line2 = when {
        playable && started && remaining > 0 ->
            "${fmtRuntime(remaining)} left  ·  done by ${fmtClock(System.currentTimeMillis() + remaining)}"
        playable && item.duration > 0 -> fmtRuntime(item.duration)
        else -> ""
    }
    val hint = "Hold OK for options"
    Column(Modifier.width(620.dp).padding(bottom = 12.dp)) {
        Text(
            item.label.uppercase(),
            fontFamily = FontFamily.Serif,
            fontSize = titleSize,
            lineHeight = titleSize * 1.15f,
            letterSpacing = 1.sp,
            maxLines = if (roomy) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        if (line1.isNotBlank()) Text(line1, fontSize = 16.sp, color = Color(0xFFD8D4CA), modifier = Modifier.padding(top = 8.dp))
        if (line2.isNotBlank()) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(line2, fontSize = 15.sp, color = Pal.Dim, maxLines = 1)
                Text("  ·  $hint", fontSize = 12.sp, color = Pal.Dim, maxLines = 1, modifier = Modifier.alpha(0.6f))
            }
        } else {
            Text(hint, fontSize = 12.sp, color = Pal.Dim, maxLines = 1, modifier = Modifier.padding(top = 2.dp).alpha(0.6f))
        }
        if (showSummary && item.summary.isNotBlank()) {
            Text(
                item.summary,
                fontSize = 14.sp,
                color = Pal.Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingPill(onClick: () -> Unit) {
    val np = NowPlaying
    Focusable(Modifier.width(320.dp).height(70.dp), null, 1.05f, onClick) {
        Row(
            Modifier.fillMaxSize().background(Color(0x99101216)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val art = np.art
            if (art != null) {
                Image(art.asImageBitmap(), null, Modifier.size(70.dp), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(70.dp).background(Color(0x33FFFFFF)))
            }
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(np.title ?: "", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(np.artist ?: "", fontSize = 13.sp, color = Pal.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (np.playing) "Playing" else "Paused", fontSize = 12.sp, color = Pal.Dim, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@Composable
fun HomeScreen(
    api: PlexApi,
    home: HomeData,
    ov: OverseerrApi,
    onDetail: (String) -> Unit,
    onPlay: (Item) -> Unit,
    onNowPlaying: () -> Unit,
    onRequest: (Media) -> Unit,
) {
    val rows = home.rows
    if (rows == null) { Centered(home.error ?: "Loading…"); return }
    if (rows.isEmpty()) { Centered("Nothing here yet — add some media to Plex."); return }

    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<Pair<Item, Boolean>?>(null) }
    // Watchlist titles: open the library page if you already have it, otherwise the request page
    fun openExternal(item: Item) {
        val id = item.tmdbId?.toIntOrNull() ?: return
        val kind = if (item.type == "movie") "movie" else "tv"
        scope.launch {
            val m = ov.detail(kind, id)
                ?: Media(id, kind, item.title, item.year.toString(), item.summary, item.thumb, item.art, 1)
            val rk = m.ratingKey
            if (m.status >= 4 && rk != null) onDetail(rk) else onRequest(m)
        }
    }
    // Back from a title, the player, Settings or the profile picker: focus on the exact poster you came from (NavMemory)
    val restoring = remember { NavMemory.pending }
    val restoreKey = remember { if (restoring == "home") NavMemory.homeKey else null }
    val restoreFr = remember { FocusRequester() }
    LaunchedEffect(rows.size) {
        if (home.selected == null) home.selected = rows.firstOrNull()?.items?.firstOrNull()
        if (WeatherState.returnFocus) return@LaunchedEffect // coming back from the weather page: the widget takes focus
        if (restoring == "home") {
            NavMemory.take("home")
            val ri = rows.indexOfFirst { r -> r.items.any { "${r.title}|${it.ratingKey}" == restoreKey } }
            if (ri >= 0) {
                val row = rows[ri]
                val ii = row.items.indexOfFirst { "${row.title}|${it.ratingKey}" == restoreKey }
                if (home.listState.layoutInfo.visibleItemsInfo.none { it.index == ri }) home.listState.scrollToItem(ri)
                delay(40)
                val rs = home.rowStates[row.title]
                if (rs != null && rs.layoutInfo.visibleItemsInfo.none { it.index == ii }) rs.scrollToItem(ii)
                if (focusSoon(restoreFr)) return@LaunchedEffect
            }
        } else if (restoring != null) {
            return@LaunchedEffect // the top bar (Search chip) or another screen puts focus back
        }
        if (!focusSoon(first) && restoreKey != null) focusSoon(restoreFr)
    }
    // Kept in HomeData (not rememberLazyListState) so the scroll position survives opening a title.
    val listState = home.listState
    var focusedRow by remember { mutableIntStateOf(-1) }
    // Snap the focused row's header to the top of the list area.
    LaunchedEffect(focusedRow) {
        if (focusedRow >= 0) listState.animateScrollToItem(focusedRow)
    }

    // Sizes from the real space (MainScreen already keeps the bottom safe margin free): the poster rows get room for one
    // FULL row (title, poster incl. its focus scale-up, label), the hero gets the rest, but hero + top bar stay within
    // ~45% of the screen. The hero shrinks before the posters do; posters never go below 150dp.
    val tv = tvDims()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val avail = maxHeight
    val rowTitleH = with(density) { 18.sp.toDp() } * 1.4f + 10.dp
    val labelH = 8.dp + with(density) { 14.sp.toDp() } * 1.45f
    val rowChrome = 12.dp + rowTitleH + labelH                       // list top padding + row title + poster label
    val heroMin = 140.dp
    val posterH = ((avail - heroMin - rowChrome) / 1.06f).coerceIn(150.dp, 225.dp)
    val rowsNeed = rowChrome + posterH * 1.06f
    val heroCap = maxOf(heroMin, tv.screenH * 0.45f - (tv.screenH - tv.bottom - avail))  // top bar counts toward the 45%
    val heroH = (avail - rowsNeed).coerceIn(heroMin, heroCap)
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(heroH).clipToBounds().padding(horizontal = tv.side),
            contentAlignment = Alignment.BottomStart,
        ) {
            val sel = home.selected
            if (sel != null) {
                HeroInfo(
                    sel,
                    home.selectedDeck,
                    home.selectedExternal,
                    onPlay,
                    onDetail,
                    onMarkWatched = {
                        scope.launch {
                            api.setWatched(sel.ratingKey, true)
                            loadHome(api, home)
                        }
                    },
                    onRemove = {
                        scope.launch {
                            api.hideFromContinue(sel.ratingKey)
                            loadHome(api, home)
                        }
                    },
                    onExternal = { openExternal(sel) },
                    space = heroH,
                )
            }
            if (NowPlaying.active) {
                Box(Modifier.align(Alignment.BottomEnd)) { NowPlayingPill(onNowPlaying) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                state = listState,
                contentPadding = PaddingValues(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(rows) { ri, row ->
                    Column {
                        Text(
                            row.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = tv.side, bottom = 10.dp),
                        )
                        LazyRow(
                            state = home.rowStates.getOrPut(row.title) { LazyListState() },
                            contentPadding = PaddingValues(horizontal = tv.side),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(row.items) { ii, item ->
                                PosterCard(
                                    api,
                                    item,
                                    row.landscape,
                                    when {
                                        "${row.title}|${item.ratingKey}" == restoreKey -> restoreFr
                                        ri == 0 && ii == 0 -> first
                                        else -> null
                                    },
                                    onFocus = {
                                        NavMemory.homeKey = "${row.title}|${item.ratingKey}"
                                        NavMemory.restoreTo = "home"
                                        home.selected = item
                                        home.selectedDeck = row.playDirect
                                        home.selectedExternal = row.external
                                        focusedRow = ri
                                    },
                                    onLongPress = if (row.external) null else ({ menu = item to row.playDirect }),
                                    posterH = posterH,
                                    revealLabel = false,
                                ) {
                                    if (row.external) openExternal(item) else if (row.playDirect) onPlay(item) else onDetail(item.showKey)
                                }
                            }
                        }
                    }
                }
            }
            // Soft top edge so a row sliding out never shows through over the hero.
            Box(
                Modifier.fillMaxWidth().height(24.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0A0A0A), Color.Transparent))),
            )
        }
    }
    }
    // Options menu (hold OK for HOLD_OK_MS on a Home row): drawn over the screen, focus starts on the first option and
    // stays inside; it stays open after OK is released and closes only when you pick an option or press Back.
    val m = menu
    if (m != null) {
        val (item, deck) = m
        val playable = item.type == "episode" || item.type == "movie"
        val opts = buildList<Pair<String, () -> Unit>> {
            if (playable) {
                add((if (item.resumable) "Resume" else "Play") to { menu = null; onPlay(item) })
                add("Details" to { menu = null; onDetail(item.showKey) })
            } else {
                add("Open" to { menu = null; onDetail(item.showKey) })
            }
            add("Mark watched" to { menu = null; scope.launch { api.setWatched(item.ratingKey, true); loadHome(api, home) }; Unit })
            add("Mark unwatched" to { menu = null; scope.launch { api.setWatched(item.ratingKey, false); loadHome(api, home) }; Unit })
            if (deck) {
                add("Remove from Continue Watching" to { menu = null; scope.launch { api.hideFromContinue(item.ratingKey); loadHome(api, home) }; Unit })
            }
        }
        HoldMenu(item.label, opts, onDismiss = { menu = null }, selected = 0)
    }
}
