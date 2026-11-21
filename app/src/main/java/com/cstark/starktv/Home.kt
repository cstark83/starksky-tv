package com.cstark.starktv

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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
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

@Composable
fun WeatherBlock(prefs: Prefs) {
    LaunchedEffect(Unit) {
        while (true) {
            fetchWeather(prefs.weatherLat, prefs.weatherLon)?.let { WeatherState.weather = it }
            delay(20 * 60_000L)
        }
    }
    val w = WeatherState.weather
    Column(horizontalAlignment = Alignment.End) {
        Text(
            if (w == null) "—" else w.temp(prefs.celsius),
            fontSize = 34.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            if (w == null) "" else w.label,
            fontSize = 14.sp,
            color = Pal.Dim,
        )
    }
}

@Composable
fun TopBar(prefs: Prefs, home: HomeData, onSearch: () -> Unit, onProfile: () -> Unit, onSpotify: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ClockBlock()
        Row(
            Modifier.weight(1f).padding(top = 8.dp).padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Chip(prefs.userName ?: "Profile", false, compact = true, onClick = onProfile)
            Chip("Home", home.tab == 0, compact = true) { home.tab = 0 }
            home.sections.forEachIndexed { i, s -> Chip(s.title, home.tab == i + 1, compact = true) { home.tab = i + 1 } }
            Chip("Search", false, compact = true, onClick = onSearch)
            Chip("Spotify", false, compact = true, onClick = onSpotify)
            Chip("Discover", home.tab == home.discoverTab, compact = true) { home.tab = home.discoverTab }
            if (Updater.newBuild > 0) Chip("Update", false, compact = true) { home.tab = home.settingsTab }
            Chip("Settings", home.tab == home.settingsTab, compact = true) { home.tab = home.settingsTab }
        }
        WeatherBlock(prefs)
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
) {
    val playable = item.type == "episode" || item.type == "movie"
    val remaining = if (item.duration > 0) item.duration - item.viewOffset else 0L
    val started = item.viewOffset > 0
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
            fontSize = 52.sp,
            lineHeight = 60.sp,
            letterSpacing = 1.sp,
            maxLines = 2,
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
        if (item.summary.isNotBlank()) {
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
    LaunchedEffect(rows.size) {
        if (home.selected == null) home.selected = rows.first().items.first()
        runCatching { first.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().weight(0.46f).padding(horizontal = 48.dp),
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
                )
            }
            if (NowPlaying.active) {
                Box(Modifier.align(Alignment.BottomEnd)) { NowPlayingPill(onNowPlaying) }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(0.54f),
            state = home.listState,
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(rows) { ri, row ->
                Column {
                    Text(
                        row.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(row.items) { ii, item ->
                            PosterCard(
                                api,
                                item,
                                row.landscape,
                                if (ri == 0 && ii == 0) first else null,
                                onFocus = {
                                    home.selected = item
                                    home.selectedDeck = row.playDirect
                                    home.selectedExternal = row.external
                                },
                                onLongPress = if (row.external) null else ({ menu = item to row.playDirect }),
                            ) {
                                if (row.external) openExternal(item) else if (row.playDirect) onPlay(item) else onDetail(item.showKey)
                            }
                        }
                    }
                }
            }
        }
    }
    val m = menu
    if (m != null) {
        val (item, deck) = m
        val playable = item.type == "episode" || item.type == "movie"
        Dialog(onDismissRequest = { menu = null }) {
            val firstChip = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { firstChip.requestFocus() } }
            Column(
                Modifier.width(380.dp).background(Color(0xEE14161C)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playable) {
                    Chip(if (item.viewOffset > 0) "Resume" else "Play", selected = true, fr = firstChip) { menu = null; onPlay(item) }
                    Chip("Details") { menu = null; onDetail(item.showKey) }
                } else {
                    Chip("Open", selected = true, fr = firstChip) { menu = null; onDetail(item.showKey) }
                }
                Chip("Mark watched") {
                    menu = null
                    scope.launch { api.setWatched(item.ratingKey, true); loadHome(api, home) }
                }
                Chip("Mark unwatched") {
                    menu = null
                    scope.launch { api.setWatched(item.ratingKey, false); loadHome(api, home) }
                }
                if (deck) {
                    Chip("Remove from Continue Watching") {
                        menu = null
                        scope.launch { api.hideFromContinue(item.ratingKey); loadHome(api, home) }
                    }
                }
            }
        }
    }
}
