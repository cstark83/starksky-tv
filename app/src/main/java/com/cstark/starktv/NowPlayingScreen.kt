package com.cstark.starktv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val np = NowPlaying
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsState by remember { mutableStateOf("") }
    var pos by remember { mutableLongStateOf(0L) }
    var mode by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(np.title, np.artist) {
        lyrics = emptyList()
        val t = np.title
        val a = np.artist
        if (t != null && a != null) {
            lyricsState = "loading"
            lyrics = fetchLyrics(a, t, np.album, np.durationMs)
            lyricsState = if (lyrics.isEmpty()) "none" else "ok"
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            pos = np.positionMs()
            delay(200)
        }
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { Capture.stop(ctx) } }
    // App left the screen: stop the visualizer's audio-capture service too, so nothing keeps running in the background.
    // (Spotify itself plays in its own app; Starsky only shows what it plays.)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) Capture.stop(ctx) }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val glow = remember(np.art) { np.art?.let { pickAccent(it) } ?: Pal.Accent }

    // --- audio capture permission flow (experimental visualizer)
    val projLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, CaptureService::class.java).putExtra("code", r.resultCode).putExtra("data", data),
            )
        } else {
            Capture.state = "denied"
            Capture.note = "Audio capture wasn't allowed."
        }
    }
    fun launchProjection() {
        try {
            val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Capture.state = "unavailable"
            Capture.note = "This device doesn't offer the screen-capture permission screen, so it can't share audio with Starsky TV."
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchProjection()
        } else {
            Capture.state = "denied"
            Capture.note = "Microphone permission was declined, which Android requires for audio capture."
        }
    }
    fun startVisualizer() {
        mode = 1
        if (Capture.state != "running") {
            if (Build.VERSION.SDK_INT < 29) {
                Capture.state = "unavailable"
                Capture.note = "This Android version can't capture app audio."
            } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Capture.state = "idle"
                launchProjection()
            } else {
                Capture.state = "idle"
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val t = rememberInfiniteTransition(label = "glow")
    val drift by t.animateFloat(
        0.35f, 0.65f,
        infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )

    // Nothing playing (or no access yet): say so, offer to open Spotify once; this screen fills in live when it plays
    if (!np.active) {
        NothingPlaying(onClose)
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF08090C))) {
        // album art, blurred (scaled down to a few pixels, then up: works on every Android version), tinted by its accent
        val blurred = remember(np.art) {
            np.art?.let { runCatching { android.graphics.Bitmap.createScaledBitmap(it, 12, 12, true).asImageBitmap() }.getOrNull() }
        }
        if (blurred != null) {
            Image(
                blurred, null,
                Modifier.fillMaxSize().alpha(0.55f),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(glow.copy(alpha = 0.25f), Color(0xCC08090C)))))
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(size.width * drift, size.height * 0.55f),
                    radius = size.width * 0.75f,
                ),
            )
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                val art = np.art
                if (art != null) {
                    Image(
                        art.asImageBitmap(), null,
                        Modifier.size(340.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.size(340.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)))
                }
                Text(np.title ?: "Nothing playing", fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
                Text(np.artist ?: "", fontSize = 18.sp, color = Pal.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 12.dp).width(340.dp)) {
                    val frac = if (np.durationMs > 0) pos.toFloat() / np.durationMs else 0f
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF)))
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).background(glow))
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Prev") { np.previous() }
                    Chip(if (np.playing) "Pause" else "Play", fr = playFocus) { np.toggle() }
                    Chip("Next") { np.next() }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Lyrics", mode == 0) { mode = 0 }
                    Chip("Visualizer", mode == 1) { startVisualizer() }
                    Chip("Close", onClick = onClose)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (mode == 0) LyricsView(lyrics, lyricsState, pos) else VisualizerView(glow)
            }
        }
    }
}

@Composable
private fun LyricsView(lines: List<LyricLine>, state: String, pos: Long) {
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when (state) {
                    "loading" -> "Looking for lyrics…"
                    "none" -> "No synced lyrics found for this song"
                    else -> ""
                },
                fontSize = 20.sp,
                color = Pal.Dim,
            )
        }
        return
    }
    val idx = lines.indexOfLast { it.timeMs <= pos + 150 }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        for (o in -2..3) {
            val i = idx + o
            val raw = lines.getOrNull(i)?.text ?: ""
            val text = if (lines.getOrNull(i) != null && raw.isBlank()) "♪" else raw
            Text(
                text,
                fontSize = if (o == 0) 38.sp else 24.sp,
                color = if (o == 0) Color.White else Color(0x88FFFFFF),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun VisualizerView(accent: Color) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val redraw = frame
            val w = size.width
            val h = size.height
            val gap = w / (BANDS * 4f)
            val bw = (w - gap * (BANDS - 1)) / BANDS
            for (i in 0 until BANDS) {
                val v = Capture.bands[i]
                val bh = (h * 0.7f * v).coerceAtLeast(6f)
                val x = i * (bw + gap)
                drawRoundRect(accent.copy(alpha = 0.30f), Offset(x - 3f, h / 2 - bh / 2 - 8f), Size(bw + 6f, bh + 16f), CornerRadius(bw / 2))
                drawRoundRect(accent, Offset(x, h / 2 - bh / 2), Size(bw, bh), CornerRadius(bw / 2))
            }
        }
        val s = Capture.state
        if (s != "running") {
            Text(
                when (s) {
                    "idle" -> "Waiting for permission…"
                    else -> Capture.note.ifBlank { "The visualizer isn't available on this device." } +
                        "\n\nLyrics still work. Press Lyrics to go back."
                },
                fontSize = 18.sp,
                color = Pal.Dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        } else if (!NowPlaying.playing) {
            Text("Paused", fontSize = 18.sp, color = Pal.Dim, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun NothingPlaying(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val first = remember { FocusRequester() }
    val access = remember { hasMusicAccess(ctx) }
    LaunchedEffect(Unit) { focusSoon(first) }
    Box(Modifier.fillMaxSize().background(Color(0xFF08090C)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (!access) {
                Text("Starsky can't see Spotify yet", fontSize = 28.sp)
                Text(
                    "Allow \"Notification access\" for Starsky TV (Settings > Apps > Special app access), then come back.",
                    fontSize = 16.sp, color = Pal.Dim, textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("Open access settings", fr = first) { openAccessSettings(ctx) }
                    Chip("Close", onClick = onClose)
                }
            } else {
                Text("Start something in Spotify first", fontSize = 28.sp)
                Text(
                    "Spotify opens once. When it plays, press Back (or open Starsky TV again) and this screen shows it.",
                    fontSize = 16.sp, color = Pal.Dim, textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip("Open Spotify", fr = first) { openSpotify(ctx) }
                    Chip("Close", onClick = onClose)
                }
            }
        }
    }
}
