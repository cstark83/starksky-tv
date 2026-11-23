package com.cstark.starktv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** What the Home top bar shows. */
data class Weather(val tempC: Double, val label: String)

data class WeatherHour(val time: String, val code: Int, val day: Boolean, val tempF: Double, val rain: Int)
data class WeatherDay(val date: String, val code: Int, val hiF: Double, val loF: Double, val rain: Int)

/** Current conditions + forecast (Open-Meteo, Fahrenheit and mph). Times are local to the location ("2026-11-22T18:00"). */
data class Forecast(
    val fetchedAt: Long,
    val time: String,
    val tempF: Double,
    val feelsF: Double,
    val code: Int,
    val isDay: Boolean,
    val windMph: Double,
    val windDir: Int,
    val humidity: Int,
    val sunrise: String,
    val sunset: String,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
)

object WeatherState {
    var weather by mutableStateOf<Weather?>(null)
    var forecast by mutableStateOf<Forecast?>(null)
    var lastFetch = 0L
    /** Set when the weather page opens: on the way back, Home gives focus to the weather widget instead of the first row. */
    var returnFocus = false
}

private val http = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

fun weatherLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly clear"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67 -> "Rain"
    71, 73, 75, 77 -> "Snow"
    80, 81, 82 -> "Showers"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorms"
    else -> "—"
}

fun weatherIcon(code: Int, day: Boolean = true): String = when (code) {
    0 -> if (day) "☀️" else "🌙"
    1 -> if (day) "🌤️" else "🌙"
    2 -> if (day) "⛅" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67 -> "🌧️"
    80, 81, 82 -> "🌦️"
    71, 73, 75, 77, 85, 86 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}

fun compass(deg: Int): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((deg % 360 + 360) % 360 + 22) / 45 % 8]

private fun JSONArray.d(i: Int) = optDouble(i, Double.NaN)
private fun JSONArray.n(i: Int) = if (isNull(i)) 0 else optInt(i, 0)

/** Parses an Open-Meteo answer; null if it isn't one. */
fun parseForecast(body: String, fetchedAt: Long): Forecast? = try {
    val o = JSONObject(body)
    val cur = o.getJSONObject("current")
    val h = o.getJSONObject("hourly")
    val dl = o.getJSONObject("daily")
    val now = cur.getString("time")
    val ht = h.getJSONArray("time")
    val hours = (0 until ht.length())
        .filter { ht.getString(it) > now }          // the next full hours after "now"
        .take(12)
        .map { i ->
            WeatherHour(ht.getString(i), h.getJSONArray("weather_code").n(i), h.getJSONArray("is_day").n(i) == 1,
                h.getJSONArray("temperature_2m").d(i), h.getJSONArray("precipitation_probability").n(i))
        }
    val dt = dl.getJSONArray("time")
    val days = (0 until dt.length()).take(7).map { i ->
        WeatherDay(dt.getString(i), dl.getJSONArray("weather_code").n(i), dl.getJSONArray("temperature_2m_max").d(i),
            dl.getJSONArray("temperature_2m_min").d(i), dl.getJSONArray("precipitation_probability_max").n(i))
    }
    Forecast(
        fetchedAt, now,
        cur.getDouble("temperature_2m"), cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")),
        cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1,
        cur.optDouble("wind_speed_10m", 0.0), cur.optInt("wind_direction_10m", 0), cur.optInt("relative_humidity_2m", 0),
        dl.getJSONArray("sunrise").optString(0, ""), dl.getJSONArray("sunset").optString(0, ""),
        hours, days,
    )
} catch (e: Exception) {
    null
}

/** Free, key-less weather from Open-Meteo (current + 12 hours + 7 days). Returns the raw answer, or null if it fails. */
private suspend fun fetchRaw(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
    try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=7"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }
}

private fun publish(f: Forecast) {
    WeatherState.forecast = f
    WeatherState.weather = Weather((f.tempF - 32) * 5 / 9, weatherLabel(f.code))
    WeatherState.lastFetch = f.fetchedAt
}

/** Shows the last saved answer at once (app start, weather page), so nothing waits for the network. */
fun loadCachedWeather(prefs: Prefs) {
    if (WeatherState.forecast != null) return
    val body = prefs.weatherCache ?: return
    parseForecast(body, prefs.weatherCacheAt)?.let { publish(it) }
}

/** Fetches, publishes and saves the weather. True when it worked. */
suspend fun refreshWeather(prefs: Prefs): Boolean {
    val now = System.currentTimeMillis()
    val body = fetchRaw(prefs.weatherLat, prefs.weatherLon) ?: return false
    val f = parseForecast(body, now) ?: return false
    publish(f)
    prefs.weatherCache = body
    prefs.weatherCacheAt = now
    return true
}

fun Weather.temp(celsius: Boolean): String =
    if (celsius) "${tempC.roundToInt()}°" else "${(tempC * 9 / 5 + 32).roundToInt()}°"

/** A Fahrenheit value in the unit chosen in Settings (Fahrenheit unless °C is picked). */
fun tempText(f: Double, celsius: Boolean): String =
    if (f.isNaN()) "—" else if (celsius) "${((f - 32) * 5 / 9).roundToInt()}°" else "${f.roundToInt()}°"

private val isoIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
private val dayIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** "2026-11-22T19:00" -> "7 PM" (or "7:52 AM" with minutes). */
fun weatherClock(iso: String, minutes: Boolean): String = try {
    SimpleDateFormat(if (minutes) "h:mm a" else "h a", Locale.getDefault()).format(isoIn.parse(iso)!!)
} catch (e: Exception) {
    ""
}

/** "2026-11-23" -> "Mon"; the first day is "Today". */
fun weatherDay(date: String, first: Boolean): String = if (first) "Today" else try {
    SimpleDateFormat("EEE", Locale.getDefault()).format(dayIn.parse(date)!!)
} catch (e: Exception) {
    date
}
