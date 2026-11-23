package com.cstark.starktv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen weather (opened with OK on the Home weather widget). Shows the last saved forecast at once, then refreshes it
 * when it is older than 10 minutes; if that fails it keeps retrying (10 s, after 5 tries 30 s) and says so. Back closes it
 * (App's BackHandler) and Home puts focus back on the widget.
 */
@Composable
fun WeatherScreen(prefs: Prefs) {
    val accent = LocalAccent.current
    val celsius = prefs.celsius
    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val refreshFr = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { refreshFr.requestFocus() } }
    LaunchedEffect(attempt) {
        loadCachedWeather(prefs)
        val cached = WeatherState.forecast
        if (attempt == 0 && cached != null && System.currentTimeMillis() - cached.fetchedAt < 10 * 60_000L) return@LaunchedEffect
        var n = 0
        while (true) {
            if (WeatherState.forecast == null) status = "Loading…"
            if (refreshWeather(prefs)) { status = null; break }
            status = if (WeatherState.forecast == null) "Can't reach the weather service, retrying…"
            else "Couldn't refresh, showing the last forecast. Retrying…"
            delay(retryDelayMs(++n))
        }
    }

    val f = WeatherState.forecast
    Column(
        Modifier.fillMaxSize().background(Pal.Bg).padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // header: location, last update, refresh
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(prefs.weatherName, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp)
                val upd = f?.let { "Updated " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.fetchedAt)) } ?: ""
                Text(listOfNotNull(upd.ifEmpty { null }, status).joinToString("  ·  "), fontSize = 14.sp, color = Pal.Dim)
            }
            Chip("Refresh", fr = refreshFr, compact = true) { attempt++ }
        }
        if (f == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(status ?: "Loading…", fontSize = 20.sp, color = Pal.Dim)
            }
        } else {
            ForecastBody(f, celsius, accent)
        }
    }
}

@Composable
private fun ForecastBody(f: Forecast, celsius: Boolean, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

        // current conditions
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(weatherIcon(f.code, f.isDay), fontSize = 64.sp)
            Text(tempText(f.tempF, celsius), fontSize = 76.sp, fontWeight = FontWeight.Light)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(weatherLabel(f.code), fontSize = 26.sp)
                Text("Feels like ${tempText(f.feelsF, celsius)}", fontSize = 16.sp, color = Pal.Dim)
                Text("Wind ${f.windMph.roundToInt()} mph ${compass(f.windDir)}  ·  Humidity ${f.humidity}%", fontSize = 16.sp, color = Pal.Dim)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SunLine("Sunrise", weatherClock(f.sunrise, true), accent)
                SunLine("Sunset", weatherClock(f.sunset, true), accent)
            }
        }

        // next 12 hours
        Section("Next 12 hours")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            f.hours.forEach { h ->
                Cell(Modifier.weight(1f)) {
                    Text(weatherClock(h.time, false), fontSize = 13.sp, color = Pal.Dim)
                    Text(weatherIcon(h.code, h.day), fontSize = 22.sp)
                    Text(tempText(h.tempF, celsius), fontSize = 18.sp)
                    Text("${h.rain}%", fontSize = 12.sp, color = if (h.rain >= 30) accent else Pal.Dim)
                }
            }
        }

        // 7 days
        Section("7 days")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            f.days.forEachIndexed { i, d ->
                Cell(Modifier.weight(1f)) {
                    Text(weatherDay(d.date, i == 0), fontSize = 15.sp, color = if (i == 0) accent else Color.White)
                    Text(weatherIcon(d.code), fontSize = 26.sp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(tempText(d.hiF, celsius), fontSize = 18.sp)
                        Text("  ${tempText(d.loF, celsius)}", fontSize = 15.sp, color = Pal.Dim)
                    }
                    Text("${d.rain}% rain", fontSize = 12.sp, color = if (d.rain >= 30) accent else Pal.Dim)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), fontSize = 13.sp, color = Pal.Dim, letterSpacing = 2.sp)
}

@Composable
private fun Cell(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.background(Pal.Card, RoundedCornerShape(12.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun SunLine(label: String, time: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Pal.Dim, textAlign = TextAlign.End, modifier = Modifier.width(70.dp))
        Text("  $time", fontSize = 18.sp, color = accent)
    }
}
