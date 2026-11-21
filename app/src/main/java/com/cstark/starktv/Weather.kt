package com.cstark.starktv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class Weather(val tempC: Double, val label: String)

object WeatherState {
    var weather by mutableStateOf<Weather?>(null)
    var lastFetch = 0L
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

/** Free, key-less weather from Open-Meteo. Returns null if the lookup fails. */
suspend fun fetchWeather(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
    try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code&timezone=auto"
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val cur = JSONObject(r.body?.string() ?: return@use null).getJSONObject("current")
            Weather(cur.getDouble("temperature_2m"), weatherLabel(cur.getInt("weather_code")))
        }
    } catch (e: Exception) {
        null
    }
}

fun Weather.temp(celsius: Boolean): String =
    if (celsius) "${tempC.roundToInt()}°" else "${(tempC * 9 / 5 + 32).roundToInt()}°"
