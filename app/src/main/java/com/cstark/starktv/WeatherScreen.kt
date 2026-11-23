package com.cstark.starktv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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

private const val WEATHER_STALE_MS = 10 * 60_000L

/**
 * Full-screen weather (OK on the Home weather widget). A vertical list, so everything fits and the focused tile is always
 * scrolled into view: header (location, update time, Refresh), NWS alerts banner, current conditions + details, next 12 hours
 * (focusable tiles), 7 days (focusable tiles; OK opens that day's detail). Shows the last saved forecast at once, refreshes it
 * when older than 10 minutes, retries with a message when it fails. Back closes the day detail first, then the page
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

    LaunchedEffect(Unit) { runCatching { refreshFr.requestFocus() } }
    LaunchedEffect(attempt) {
        loadCachedWeather(prefs)
        if (attempt > 0 || System.currentTimeMillis() - WeatherState.alertsAt > WEATHER_STALE_MS) refreshAlerts(prefs)
        val cached = WeatherState.forecast
        if (attempt == 0 && cached != null && System.currentTimeMillis() - cached.fetchedAt < WEATHER_STALE_MS) return@LaunchedEffect
        var n = 0
        while (true) {
            if (WeatherState.forecast == null) status = "Loading…"
            if (refreshWeather(prefs)) { status = null; break }
            status = if (WeatherState.forecast == null) "Can't reach the weather service, retrying…"
            else "Couldn't refresh, showing the last forecast. Retrying…"
            delay(retryDelayMs(++n))
        }
    }
    BackHandler(enabled = detail >= 0) { returnTo = detail; detail = -1 }
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
