package com.cstark.starktv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** Idle screensaver choices in minutes (0 = off). */
val SAVER_CHOICES = listOf(0, 5, 10, 15)

/**
 * Home's idle screensaver: backdrops of what is on Home (so only what this profile may see, kid limits included),
 * one at a time, the next one preloaded before a slow 2 s crossfade every 12 s, a slow zoom, a quiet clock + weather.
 * Not focusable: MainScreen swallows the key that ends it, so focus is exactly where it was.
 */
@Composable
fun Screensaver(api: PlexApi, home: HomeData, prefs: Prefs) {
    val ctx = LocalContext.current
    val paths = remember {
        (home.rows ?: emptyList()).flatMap { it.items }.filter { !it.art.isNullOrBlank() && !it.art.startsWith("http") }
            .map { it.art!! }.distinct().shuffled()
    }
    var shown by remember { mutableStateOf(paths.firstOrNull()) }
    LaunchedEffect(Unit) {
        if (paths.size < 2) return@LaunchedEffect
        var i = 0
        while (true) {
            val next = paths[(i + 1) % paths.size]
            // preload (memory cache) so the crossfade never shows a half-loaded picture
            runCatching { ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(api.pic(next, 1920, 1080)).build()) }
            delay(12_000)
            i = (i + 1) % paths.size
            shown = next
        }
    }
    val t = rememberInfiniteTransition(label = "saver")
    val zoom by t.animateFloat(1.0f, 1.12f, infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom")
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(shown, animationSpec = tween(2000), label = "saverImg") { p ->
            if (p != null) {
                AsyncImage(
                    model = api.pic(p, 1920, 1080), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xB3000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = tvDims().side, bottom = tvDims().bottom).alpha(0.85f)) {
            ClockBlock()
            WeatherState.weather?.let { w -> Text("${w.temp(prefs.celsius)}  ·  ${w.label}", fontSize = 16.sp, color = Color(0xFFDDDDDD)) }
        }
    }
}
