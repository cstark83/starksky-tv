package com.cstark.starktv

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.nio.ByteBuffer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen weather (OK on the Home weather widget). A vertical list, so everything fits and the focused tile is always
 * scrolled into view: header (location, update time, Refresh, Radar), NWS alerts banner, current conditions + details, next 12 hours
 * (focusable tiles), 7 days (focusable tiles; OK opens that day's detail). Shows the last saved forecast at once and refreshes it in
 * the background on every open ("Refreshing…"), retries with a message when it fails. Back closes the radar or day detail first, then the page
 * (App's BackHandler), and Home puts focus back on the widget.
 */
@Composable
fun WeatherScreen(prefs: Prefs) {
    val accent = LocalAccent.current
    val celsius = prefs.celsius
    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val refreshFr = remember { FocusRequester() }
    val dayFrs = remember { List(7) { FocusRequester() } }
    var detail by remember { mutableIntStateOf(-1) }      // day shown in the detail overlay, -1 = none
    var returnTo by remember { mutableIntStateOf(-1) }    // day tile that gets focus back when the detail closes
    val radarFr = remember { FocusRequester() }
    var radar by remember { mutableStateOf(false) }       // full-screen radar shown
    var radarBack by remember { mutableStateOf(false) }   // radar just closed: focus goes back to the Radar button

    LaunchedEffect(Unit) { runCatching { refreshFr.requestFocus() } }
    // Every open (and every Refresh): the saved forecast shows at once, then a fresh one is fetched in the background.
    // publish() updates WeatherState, so the page, its "Updated" time and the Home widget all follow.
    LaunchedEffect(attempt) {
        loadCachedWeather(prefs)
        var n = 0
        while (true) {
            status = if (WeatherState.forecast == null) "Loading…" else "Refreshing…"
            if (n == 0) refreshAlerts(prefs)
            if (refreshWeather(prefs)) { status = null; break }
            status = if (WeatherState.forecast == null) "Can't reach the weather service, retrying…"
            else "Couldn't refresh, showing the last forecast. Retrying…"
            delay(retryDelayMs(++n))
        }
    }
    BackHandler(enabled = detail >= 0) { returnTo = detail; detail = -1 }
    BackHandler(enabled = radar) { radar = false; radarBack = true }
    LaunchedEffect(radar, radarBack) {
        if (!radar && radarBack) {
            delay(50)
            runCatching { radarFr.requestFocus() }
            radarBack = false
        }
    }
    LaunchedEffect(detail, returnTo) {
        if (detail < 0 && returnTo >= 0) {
            delay(50)
            runCatching { dayFrs[returnTo].requestFocus() }
            returnTo = -1
        }
    }

    val f = WeatherState.forecast
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(prefs.weatherName, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
                        val upd = f?.let { "Updated " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.fetchedAt)) }
                        Text(listOfNotNull(upd, status).joinToString("  ·  "), fontSize = 14.sp, color = Pal.Dim)
                    }
                    Chip("Refresh", fr = refreshFr, compact = true) { attempt++ }
                    Spacer(Modifier.width(12.dp))
                    Chip("Radar", fr = radarFr, compact = true) { radar = true }
                }
            }
            WeatherState.alerts.forEach { a ->
                item { AlertBanner(a) }
            }
            if (f == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                        Text(status ?: "Loading…", fontSize = 20.sp, color = Pal.Dim)
                    }
                }
            } else {
                item { CurrentBlock(f, celsius, accent) }
                item { SectionTitle("Next 12 hours") }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.hours.forEach { h ->
                            Tile(Modifier.weight(1f), onClick = {}) {
                                Text(weatherClock(h.time, false), fontSize = 13.sp, color = Pal.Dim, maxLines = 1)
                                Text(weatherIcon(h.code, h.day), fontSize = 22.sp)
                                Text(tempText(h.tempF, celsius), fontSize = 18.sp)
                                Text("${h.rain}%", fontSize = 12.sp, color = if (h.rain >= 30) accent else Pal.Dim)
                            }
                        }
                    }
                }
                item { SectionTitle("7 days  ·  OK for details") }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        f.days.forEachIndexed { i, d ->
                            Tile(Modifier.weight(1f), fr = dayFrs.getOrNull(i), onClick = { detail = i }) {
                                Text(weatherDay(d.date, i == 0), fontSize = 15.sp, color = if (i == 0) accent else Color.White)
                                Text(weatherIcon(d.code), fontSize = 26.sp)
                                Text("H ${tempText(d.hiF, celsius)}", fontSize = 17.sp)
                                Text("L ${tempText(d.loF, celsius)}", fontSize = 15.sp, color = Pal.Dim)
                                Text("${d.rain}% rain", fontSize = 12.sp, color = if (d.rain >= 30) accent else Pal.Dim)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.padding(top = 8.dp)) }
            }
        }
        val dd = f?.days?.getOrNull(detail)
        if (dd != null) DayDetail(dd, detail == 0, celsius, accent) { returnTo = detail; detail = -1 }
        if (radar) RadarView()
    }
}

private const val RADAR_REFRESH_MS = 5 * 60_000L
private const val RADAR_RETRY_MS = 30_000L
private val RADAR_ARROWS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * Full-screen NWS radar loop (KIWX, Northern Indiana), drawn over the page. Reloads every 5 minutes while open; a failed
 * reload keeps the last loop and tries again in 30 s. With no loop at all it shows "Radar unavailable, press OK to retry".
 * Focus stays here: OK only retries after a failure (a GIF can't pause), the arrows do nothing, and Back goes to the
 * page's BackHandler, which closes the radar and puts focus back on the Radar button.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RadarView() {
    val ctx = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(ctx)
            .components { if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory()) }
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    DisposableEffect(Unit) { onDispose { loader.shutdown() } }
    val fr = remember { FocusRequester() }
    var gif by remember { mutableStateOf<ByteArray?>(null) }
    var gotAt by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(retry) {
        failed = false
        while (true) {
            loading = true
            val b = fetchRadarGif()
            loading = false
            if (b != null) { gif = b; gotAt = System.currentTimeMillis() }
            else if (gif == null) { failed = true; return@LaunchedEffect }
            delay(if (b != null) RADAR_REFRESH_MS else RADAR_RETRY_MS)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onPreviewKeyEvent { e ->
                when {
                    e.key == Key.Back -> false                      // to the BackHandler
                    isOkKey(e.key) -> {
                        if (e.type == KeyEventType.KeyUp && failed && !loading) retry++
                        true
                    }
                    e.key in RADAR_ARROWS -> true                   // nothing underneath may move
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val bytes = gif
        if (bytes != null) {
            val req = remember(bytes) {
                ImageRequest.Builder(ctx).data(ByteBuffer.wrap(bytes)).crossfade(false).build()
            }
            AsyncImage(
                model = req,
                imageLoader = loader,
                contentDescription = "Radar loop",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            Text("Radar unavailable, press OK to retry", fontSize = 22.sp, color = Pal.Dim)
        } else {
            CircularProgressIndicator(color = LocalAccent.current)
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(horizontal = 24.dp, vertical = 16.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val upd = if (gotAt > 0) "  ·  Updated " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(gotAt)) else ""
            Text("Radar  ·  NWS KIWX Northern Indiana$upd", fontSize = 14.sp, color = Color.White)
            if (loading && bytes != null) Text("  ·  Refreshing…", fontSize = 14.sp, color = Pal.Dim)
        }
        Text(
            "Back to close", fontSize = 13.sp, color = Pal.Dim,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 24.dp, vertical = 22.dp),
        )
    }
}

@Composable
private fun CurrentBlock(f: Forecast, celsius: Boolean, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(weatherIcon(f.code, f.isDay), fontSize = 60.sp)
            Text(tempText(f.tempF, celsius), fontSize = 72.sp, fontWeight = FontWeight.Light)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(weatherLabel(f.code), fontSize = 26.sp)
                Text("Feels like ${tempText(f.feelsF, celsius)}", fontSize = 16.sp, color = Pal.Dim)
                Text("Wind ${f.windMph.roundToInt()} mph ${compass(f.windDir)}  ·  Humidity ${f.humidity}%", fontSize = 16.sp, color = Pal.Dim)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelValue("Sunrise", weatherClock(f.sunrise, true), accent)
                LabelValue("Sunset", weatherClock(f.sunset, true), accent)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat("UV index", num(f.uv, 0))
            Stat("Pressure", if (f.pressureInHg.isNaN()) "—" else "${num(f.pressureInHg, 2)} inHg")
            Stat("Visibility", if (f.visibilityMi.isNaN()) "—" else "${num(f.visibilityMi, if (f.visibilityMi < 10) 1 else 0)} mi")
            Stat("Dew point", tempText(f.dewF, celsius))
            Stat("Precipitation today", inches(f.precipTodayIn))
        }
    }
}

@Composable
private fun AlertBanner(a: WeatherAlert) {
    val severe = a.severity.equals("Extreme", true) || a.severity.equals("Severe", true)
    Column(
        Modifier.fillMaxWidth()
            .background(if (severe) Color(0xFF7A1F1F) else Color(0xFF6B4A12), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("⚠️  ${a.event}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (a.headline.isNotBlank()) Text(a.headline, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Detail of one forecast day, drawn over the page. Focus stays inside; OK on Close or Back closes it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DayDetail(d: WeatherDay, today: Boolean, celsius: Boolean, accent: Color, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    LaunchedEffect(d.date) { runCatching { close.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp)
                .background(Pal.Card, RoundedCornerShape(16.dp))
                .padding(32.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val name = if (today) "Today" else try {
                SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d.date)!!)
            } catch (e: Exception) { d.date }
            Text(name, fontSize = 26.sp, fontWeight = FontWeight.Light, color = accent)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(weatherIcon(d.code), fontSize = 44.sp)
                Column {
                    Text(weatherLabel(d.code), fontSize = 22.sp)
                    Text("High ${tempText(d.hiF, celsius)}  ·  Low ${tempText(d.loF, celsius)}", fontSize = 18.sp, color = Pal.Dim)
                }
            }
            LabelValue("Rain chance", "${d.rain}%", Color.White, 170)
            LabelValue("Precipitation", inches(d.precipIn), Color.White, 170)
            LabelValue("Wind", if (d.windMph.isNaN()) "—" else "${d.windMph.roundToInt()} mph, gusts ${if (d.gustMph.isNaN()) "—" else d.gustMph.roundToInt()} mph", Color.White, 170)
            LabelValue("Sunrise", weatherClock(d.sunrise, true), Color.White, 170)
            LabelValue("Sunset", weatherClock(d.sunset, true), Color.White, 170)
            LabelValue("UV index max", num(d.uvMax, 1), Color.White, 170)
            Box(Modifier.padding(top = 8.dp)) { Chip("Close", fr = close, onClick = onClose) }
        }
    }
}

/** Focusable forecast tile: accent border + lighter card when focused (Focusable), OK runs [onClick] (no hold menu). */
@Composable
private fun Tile(modifier: Modifier, fr: FocusRequester? = null, onClick: () -> Unit, content: @Composable () -> Unit) {
    Focusable(modifier, fr = fr, scaleOnFocus = 1.06f, onClick = onClick) { focused ->
        Column(
            Modifier.fillMaxWidth()
                .background(if (focused) Color(0xFF34343E) else Pal.Card, RoundedCornerShape(10.dp))
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title.uppercase(), fontSize = 13.sp, color = Pal.Dim, letterSpacing = 2.sp)
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = Pal.Dim)
        Text(value, fontSize = 18.sp)
    }
}

@Composable
private fun LabelValue(label: String, value: String, valueColor: Color, labelWidth: Int = 70) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Pal.Dim, modifier = Modifier.width(labelWidth.dp))
        Text(value, fontSize = 18.sp, color = valueColor)
    }
}

private fun num(v: Double, decimals: Int): String =
    if (v.isNaN()) "—" else if (decimals == 0) v.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", v)

private fun inches(v: Double): String = if (v.isNaN()) "—" else "${String.format(Locale.US, "%.2f", v)} in"
