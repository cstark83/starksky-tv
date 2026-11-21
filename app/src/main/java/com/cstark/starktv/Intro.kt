package com.cstark.starktv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Opening styles, most dramatic first. INTRO_OFF skips the opening entirely. */
val INTRO_NAMES = listOf("Cinema film", "Blockbuster", "Spotlight", "Marquee", "Curtain rise", "Starlight")
const val INTRO_OFF = 6

private const val INTRO_TITLE = "STARSKY TV"
private const val INTRO_TAGLINE = "YOUR LIBRARY.  YOUR THEATER."
private val GOLD = Pal.Accent

private fun ease(x: Float): Float = 1f - (1f - x).pow(3)
private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t
private fun segOf(ms: Float, a: Float, b: Float): Float = ((ms - a) / (b - a)).coerceIn(0f, 1f)

@Composable
fun IntroScreen(style: Int, onDone: () -> Unit) {
    when (style) {
        0 -> VideoIntro(onDone)
        2 -> IntroStage(5600f, 5000f, { synth(5.6, spotlightGen()) }, onDone) { ms -> SpotlightContent(ms) }
        3 -> IntroStage(5600f, 5000f, { synth(5.6, marqueeGen()) }, onDone) { ms -> MarqueeContent(ms) }
        4 -> IntroStage(6200f, 5600f, { synth(6.2, curtainGen()) }, onDone) { ms -> CurtainContent(ms) }
        5 -> IntroStage(5200f, 4600f, { synth(5.2, starlightGen()) }, onDone) { ms -> StarlightContent(ms) }
        else -> IntroStage(6800f, 6000f, { synth(7.0, blockbusterGen()) }, onDone) { ms -> BlockbusterContent(ms) }
    }
}

/**
 * The realistic opening: a pre-rendered 1080p film title (res/raw/intro.mp4) with its own sound.
 * Any button press skips it; if the video can't play for any reason the app just carries on.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoIntro(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var finished by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun finish() {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) finish()
            }

            override fun onPlayerError(error: PlaybackException) {
                finish()
            }
        }
        player.addListener(l)
        player.setMediaItem(MediaItem.fromUri(RawResourceDataSource.buildRawResourceUri(R.raw.intro)))
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    finish()
                    true
                } else {
                    false
                }
            }
            .clickable { finish() },
    ) {
        AndroidView(
            factory = { c ->
                val exo = player
                PlayerView(c).apply {
                    this.player = exo
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ------------------------------------------------------------------ shared pieces

/** Runs the clock, plays the sound, handles skip (any button) and the final fade to black. */
@Composable
private fun IntroStage(
    totalMs: Float,
    fadeOutAt: Float,
    sound: () -> AudioTrack?,
    onDone: () -> Unit,
    content: @Composable (Float) -> Unit,
) {
    val clock = remember { Animatable(0f) }
    var finished by remember { mutableStateOf(false) }
    var track by remember { mutableStateOf<AudioTrack?>(null) }
    val focus = remember { FocusRequester() }

    fun finish() {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    DisposableEffect(Unit) {
        onDispose { track?.release() }
    }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        track = withContext(Dispatchers.Default) { sound() }
        track?.play()
        clock.animateTo(totalMs, tween(totalMs.toInt(), easing = LinearEasing))
        finish()
    }

    val ms = clock.value
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(1f - segOf(ms, fadeOutAt, totalMs))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown) {
                    finish()
                    true
                } else {
                    false
                }
            }
            .clickable { finish() },
        contentAlignment = Alignment.Center,
    ) {
        content(ms)
    }
}

@Composable
private fun TitleBlock(
    alpha: Float,
    scale: Float,
    spacing: Float,
    weight: FontWeight,
    sweepPx: Float,
    glow: Float,
    line: Float,
    tagAlpha: Float,
) {
    val shimmer = Brush.linearGradient(
        colors = listOf(GOLD, GOLD, Color.White, GOLD, GOLD),
        start = Offset(sweepPx - 300f, 0f),
        end = Offset(sweepPx + 300f, 0f),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            INTRO_TITLE,
            style = TextStyle(
                brush = shimmer,
                shadow = Shadow(GOLD.copy(alpha = 0.7f * glow), Offset.Zero, 40f),
            ),
            fontSize = 72.sp,
            fontWeight = weight,
            maxLines = 1,
            softWrap = false,
            letterSpacing = spacing.sp,
            modifier = Modifier
                .alpha(alpha)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
        Spacer(Modifier.height(18.dp))
        Box(Modifier.width((520 * line).dp).height(3.dp).background(GOLD))
        Spacer(Modifier.height(16.dp))
        Text(
            INTRO_TAGLINE,
            fontSize = 15.sp,
            letterSpacing = 5.sp,
            color = Pal.Dim,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.alpha(tagAlpha),
        )
    }
}

// ------------------------------------------------------------------ 1. Blockbuster

private class Star(val angle: Float, val r0: Float, val speed: Float, val size: Float)

@Composable
private fun BlockbusterContent(ms: Float) {
    val hit = 3000f
    val stars = remember {
        val r = Random(7)
        List(170) {
            Star(
                angle = r.nextFloat() * 2f * PI.toFloat(),
                r0 = r.nextFloat(),
                speed = 0.6f + r.nextFloat() * 0.8f,
                size = 1f + r.nextFloat() * 2.2f,
            )
        }
    }
    val shake = 1f - segOf(ms, hit, hit + 450f)
    val dx = sin(ms * 0.9f) * 14f * shake
    val dy = cos(ms * 1.3f) * 10f * shake

    Box(
        Modifier.fillMaxSize().graphicsLayer { translationX = dx; translationY = dy },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = hypot(cx, cy)

            val warp = if (ms < hit) (ms / hit).pow(2) * 2.2f else 2.2f + (ms - hit) / 3000f * 0.25f
            val streak = if (ms < hit) 90f * segOf(ms, 300f, hit).pow(2) else 90f * (1f - segOf(ms, hit, hit + 1100f))
            val starAlpha = segOf(ms, 0f, 600f)
            for (s in stars) {
                val p = (s.r0 + warp * s.speed * 0.5f) % 1f
                val d = p * p * maxR + 6f
                val len = streak * p * s.speed
                val x = cx + cos(s.angle) * d
                val y = cy + sin(s.angle) * d
                val x0 = cx + cos(s.angle) * (d - len)
                val y0 = cy + sin(s.angle) * (d - len)
                val a = (p * starAlpha * 0.9f).coerceIn(0f, 1f)
                val col = if (s.size > 2.4f) GOLD else Color.White
                drawLine(col.copy(alpha = a), Offset(x0, y0), Offset(x, y), strokeWidth = s.size * (0.5f + p))
            }

            val glow = if (ms < hit) 0.15f + 0.5f * segOf(ms, 1500f, hit) else 0.65f - 0.2f * segOf(ms, hit, hit + 1500f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x77E5A00D), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = maxR * 0.7f,
                ),
                alpha = glow,
            )

            val fp = ease(segOf(ms, 1800f, hit))
            val fa = fp * (1f - segOf(ms, hit, hit + 900f))
            if (fa > 0f) {
                val w = size.width * (0.15f + 0.95f * fp)
                val flare = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color(0xFFFFE9B0), Color.Transparent),
                    startX = cx - w / 2f,
                    endX = cx + w / 2f,
                )
                drawRect(flare, Offset(cx - w / 2f, cy - 3f), Size(w, 6f), alpha = fa)
                drawRect(flare, Offset(cx - w / 2f, cy - 24f), Size(w, 48f), alpha = fa * 0.25f)
            }

            val rp = segOf(ms, hit, hit + 1300f)
            if (rp > 0f && rp < 1f) {
                drawCircle(
                    color = GOLD,
                    radius = maxR * ease(rp),
                    center = Offset(cx, cy),
                    alpha = (1f - rp) * 0.8f,
                    style = Stroke(width = 6f * (1f - rp) + 1f),
                )
            }

            if (ms >= hit) {
                val flash = 0.6f * (1f - segOf(ms, hit, hit + 450f))
                if (flash > 0f) drawRect(Color.White, alpha = flash)
            }

            val bar = size.height * 0.11f * ease(segOf(ms, 0f, 900f))
            drawRect(Color.Black, Offset(0f, 0f), Size(size.width, bar))
            drawRect(Color.Black, Offset(0f, size.height - bar), Size(size.width, bar))
        }

        TitleBlock(
            alpha = segOf(ms, hit, hit + 120f),
            scale = if (ms < hit) 1.2f else 1f + 0.18f * (1f - ease(segOf(ms, hit, hit + 900f))) + 0.05f * segOf(ms, hit, 6800f),
            spacing = 14f - 6f * ease(segOf(ms, hit, hit + 1200f)),
            weight = FontWeight.Black,
            sweepPx = -900f + 2400f * segOf(ms, 3500f, 4700f),
            glow = 1f,
            line = ease(segOf(ms, 3300f, 4300f)),
            tagAlpha = segOf(ms, 3700f, 4500f),
        )
    }
}

// ------------------------------------------------------------------ 2. Spotlight

@Composable
private fun SpotlightContent(ms: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val conv = ease(segOf(ms, 2200f, 3000f))
            val beamA = (0.35f + 0.65f * segOf(ms, 0f, 700f)) * (1f - 0.6f * segOf(ms, 3800f, 4600f))

            for (side in 0..1) {
                val dir = if (side == 0) 1f else -1f
                val apexX = if (side == 0) w * 0.1f else w * 0.9f
                val sweepX = cx + dir * w * 0.42f * sin(ms / 2800f * 2f * PI.toFloat() * 0.75f)
                val tx = lerpF(sweepX, cx, conv)
                val ty = lerpF(h * 0.05f, cy, conv)
                val bw = lerpF(w * 0.07f, w * 0.16f, conv)
                val path = Path().apply {
                    moveTo(apexX - 8f, h)
                    lineTo(apexX + 8f, h)
                    lineTo(tx + bw, ty)
                    lineTo(tx - bw, ty)
                    close()
                }
                drawPath(
                    path,
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xFFFFF1CC)), startY = ty, endY = h),
                    alpha = beamA * 0.35f,
                )
            }

            val halo = 0.7f * ease(segOf(ms, 2600f, 3600f))
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x66E5A00D), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = hypot(cx, cy) * 0.6f,
                ),
                alpha = halo,
            )
            if (ms >= 2800f) {
                val flash = 0.25f * (1f - segOf(ms, 2800f, 3300f))
                if (flash > 0f) drawRect(Color.White, alpha = flash)
            }
        }

        TitleBlock(
            alpha = ease(segOf(ms, 2800f, 3600f)),
            scale = 1.06f - 0.06f * ease(segOf(ms, 2800f, 4000f)),
            spacing = 12f - 4f * ease(segOf(ms, 2800f, 4200f)),
            weight = FontWeight.Bold,
            sweepPx = -900f + 2400f * segOf(ms, 3500f, 4500f),
            glow = ease(segOf(ms, 2800f, 3600f)),
            line = ease(segOf(ms, 3400f, 4400f)),
            tagAlpha = segOf(ms, 3800f, 4600f),
        )
    }
}

// ------------------------------------------------------------------ 3. Marquee

@Composable
private fun MarqueeContent(ms: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val left = w * 0.08f
            val right = w * 0.92f
            val top = h * 0.27f
            val bottom = h * 0.73f
            val gap = 26f * (w / 960f)
            val pts = ArrayList<Offset>()
            val nx = ((right - left) / gap).toInt()
            val ny = ((bottom - top) / gap).toInt()
            for (i in 0 until nx) pts.add(Offset(left + (right - left) * i / nx, top))
            for (i in 0 until ny) pts.add(Offset(right, top + (bottom - top) * i / ny))
            for (i in 0 until nx) pts.add(Offset(right - (right - left) * i / nx, bottom))
            for (i in 0 until ny) pts.add(Offset(left, bottom - (bottom - top) * i / ny))

            val shown = segOf(ms, 0f, 1200f)
            val n = pts.size
            for (i in 0 until n) {
                if (i.toFloat() / n > shown) continue
                val b = 0.35f + 0.65f * ((sin(i * 0.55f - ms * 0.011f) + 1f) / 2f)
                drawCircle(GOLD, radius = 10f * (w / 960f), center = pts[i], alpha = b * 0.28f)
                drawCircle(colorLerp(GOLD, Color.White, b), radius = 4.5f * (w / 960f), center = pts[i], alpha = 0.55f + 0.45f * b)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                INTRO_TITLE.forEachIndexed { i, ch ->
                    if (ch == ' ') {
                        Spacer(Modifier.width(26.dp))
                    } else {
                        val t0 = 700f + i * 130f
                        val a = ease(segOf(ms, t0, t0 + 300f))
                        val flash = 1f - segOf(ms, t0, t0 + 500f)
                        Text(
                            ch.toString(),
                            style = TextStyle(shadow = Shadow(GOLD.copy(alpha = 0.6f * a), Offset.Zero, 30f)),
                            color = colorLerp(GOLD, Color.White, flash),
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .alpha(a)
                                .graphicsLayer { scaleX = 1f + 0.25f * flash; scaleY = 1f + 0.25f * flash },
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                INTRO_TAGLINE,
                fontSize = 15.sp,
                letterSpacing = 5.sp,
                color = Pal.Dim,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alpha(segOf(ms, 2800f, 3600f)),
            )
        }
    }
}

// ------------------------------------------------------------------ 4. Curtain rise

@Composable
private fun CurtainContent(ms: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x66E5A00D), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = hypot(cx, cy) * 0.7f,
                ),
                alpha = 0.8f * ease(segOf(ms, 1200f, 3200f)),
            )
        }

        TitleBlock(
            alpha = ease(segOf(ms, 1800f, 3400f)),
            scale = 1.04f - 0.04f * ease(segOf(ms, 1800f, 4200f)),
            spacing = 12f - 4f * ease(segOf(ms, 1800f, 4200f)),
            weight = FontWeight.Bold,
            sweepPx = -900f + 2400f * segOf(ms, 3600f, 4800f),
            glow = ease(segOf(ms, 1800f, 3400f)),
            line = ease(segOf(ms, 3000f, 4000f)),
            tagAlpha = segOf(ms, 3600f, 4400f),
        )

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val e = ease(segOf(ms, 800f, 3200f))
            val panelW = w / 2f * (1f - 0.82f * e)
            val n = 16
            val stripW = panelW / n
            val dark = Color(0xFF4A0C14)
            val light = Color(0xFF9A1F2E)
            for (i in 0 until n) {
                val k = (sin(i * 1.2f + ms * 0.0012f) + 1f) / 2f * 0.85f
                val col = colorLerp(dark, light, k)
                drawRect(col, Offset(i * stripW, 0f), Size(stripW + 1f, h))
                drawRect(col, Offset(w - panelW + i * stripW, 0f), Size(stripW + 1f, h))
            }
            val valance = h * 0.09f
            drawRect(Color(0xFF3A0A10), Offset(0f, 0f), Size(w, valance))
            drawRect(GOLD, Offset(0f, valance), Size(w, 3f), alpha = 0.8f)
        }
    }
}

// ------------------------------------------------------------------ 5. Starlight

private class Dot(val x: Float, val y: Float, val dx: Float, val dy: Float, val phase: Float, val sp: Float, val size: Float)

@Composable
private fun StarlightContent(ms: Float) {
    val dots = remember {
        val r = Random(11)
        List(90) {
            Dot(
                x = r.nextFloat(),
                y = r.nextFloat(),
                dx = r.nextFloat() * 0.6f + 0.2f,
                dy = r.nextFloat() * 0.6f + 0.2f,
                phase = r.nextFloat() * 6.28f,
                sp = 0.6f + r.nextFloat() * 1.2f,
                size = 1.2f + r.nextFloat() * 2.2f,
            )
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val fadeIn = segOf(ms, 0f, 1200f)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x55E5A00D), Color.Transparent),
                    center = Offset(w / 2f, h / 2f),
                    radius = hypot(w / 2f, h / 2f) * 0.6f,
                ),
                alpha = (0.3f + 0.1f * sin(ms * 0.0015f)) * segOf(ms, 500f, 2500f),
            )
            for (d in dots) {
                val px = (((d.x + ms * d.dx * 0.00002f) % 1f) + 1f) % 1f
                val py = (((d.y - ms * d.dy * 0.00003f) % 1f) + 1f) % 1f
                val tw = 0.35f + 0.65f * ((sin(ms * 0.002f * d.sp + d.phase) + 1f) / 2f)
                drawCircle(Color.White, radius = d.size, center = Offset(px * w, py * h), alpha = tw * fadeIn)
            }
        }

        TitleBlock(
            alpha = ease(segOf(ms, 900f, 3200f)),
            scale = 1.02f - 0.02f * ease(segOf(ms, 900f, 3600f)),
            spacing = 26f - 16f * ease(segOf(ms, 900f, 3600f)),
            weight = FontWeight.Light,
            sweepPx = -900f + 2400f * segOf(ms, 3200f, 4400f),
            glow = 0.6f * ease(segOf(ms, 900f, 3200f)),
            line = ease(segOf(ms, 2600f, 3800f)),
            tagAlpha = segOf(ms, 3200f, 4000f),
        )
    }
}

// ------------------------------------------------------------------ sound

private const val SR = 44100.0

/** Renders [seconds] of mono audio from [gen] (time in seconds -> sample in -1..1). */
private fun synth(seconds: Double, gen: (Double) -> Double): AudioTrack? = try {
    val n = (SR * seconds).toInt()
    val buf = ShortArray(n)
    for (i in 0 until n) {
        val t = i / SR
        val tail = minOf(1.0, (seconds - t) / 0.3)
        buf[i] = (gen(t).coerceIn(-1.0, 1.0) * tail * 0.6 * Short.MAX_VALUE).toInt().toShort()
    }
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR.toInt())
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(buf.size * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(buf, 0, buf.size)
    track
} catch (e: Exception) {
    null
}

private fun tone(f: Double, t: Double): Double = sin(2 * PI * f * t)

/** Rumbling riser into a deep boom, impact burst, chord pad and chime at 3.0s. */
private fun blockbusterGen(): (Double) -> Double {
    val rnd = Random(3)
    var ph1 = 0.0
    var ph2 = 0.0
    var lp = 0.0
    return { t ->
        var v = 0.0
        if (t < 3.0) {
            val r = (t / 3.0).pow(2.0)
            ph1 += 2 * PI * (40.0 + 80.0 * r) / SR
            ph2 += 2 * PI * (300.0 + 1100.0 * r) / SR
            lp += 0.08 * ((rnd.nextDouble() * 2 - 1) - lp)
            v += r * (0.55 * sin(ph1) + 0.10 * sin(ph2) + 0.5 * lp)
        } else {
            val ct = t - 3.0
            v += exp(-ct * 1.5) * 0.9 * tone(45.0, ct)
            v += exp(-ct * 2.5) * 0.4 * tone(90.0, ct)
            if (ct < 0.12) v += (1 - ct / 0.12) * 0.5 * (rnd.nextDouble() * 2 - 1)
            v += exp(-ct * 0.6) * (0.16 * tone(220.0, ct) + 0.12 * tone(277.2, ct) + 0.12 * tone(329.6, ct))
            if (ct >= 0.3) {
                val c2 = ct - 0.3
                v += exp(-c2 * 2.0) * (0.25 * tone(880.0, c2) + 0.14 * tone(1320.0, c2))
            }
        }
        v
    }
}

/** Two airy whooshes that converge, then a soft thump, chime and warm pad at 2.8s. */
private fun spotlightGen(): (Double) -> Double {
    val rnd = Random(5)
    var lp = 0.0
    var ph = 0.0
    return { t ->
        var v = 0.0
        val noise = rnd.nextDouble() * 2 - 1
        if (t < 2.8) {
            val r = t / 2.8
            val env = r * r
            lp += (0.03 + 0.10 * r) * (noise - lp)
            ph += 2 * PI * (200.0 + 400.0 * r) / SR
            v += env * (2.2 * lp + 0.12 * sin(ph))
        } else {
            val ct = t - 2.8
            v += exp(-ct * 5.0) * 0.8 * tone(60.0, ct)
            if (ct < 0.25) v += (1 - ct / 0.25) * 0.2 * noise
            v += exp(-ct * 1.6) * (0.22 * tone(660.0, ct) + 0.12 * tone(990.0, ct))
            v += exp(-ct * 0.8) * (0.12 * tone(220.0, ct) + 0.1 * tone(277.2, ct) + 0.1 * tone(330.0, ct))
        }
        v
    }
}

/** One bright pluck per letter climbing a scale, then a warm chord as the last letter lands. */
private fun marqueeGen(): (Double) -> Double {
    val notes = doubleArrayOf(392.0, 440.0, 523.3, 587.3, 659.3, 784.0, 880.0, 987.8, 0.0, 1046.5, 1174.7)
    return { t ->
        var v = 0.0
        for (i in notes.indices) {
            if (notes[i] == 0.0) continue
            val dt = t - (0.7 + i * 0.13)
            if (dt in 0.0..1.0) {
                v += exp(-dt * 6.0) * (0.28 * tone(notes[i], dt) + 0.10 * tone(notes[i] * 2, dt))
            }
        }
        if (t >= 2.4) {
            val ct = t - 2.4
            val swell = (1 - exp(-ct * 6.0)) * exp(-ct * 0.7)
            v += swell * 0.12 * (tone(220.0, ct) + tone(277.2, ct) + tone(330.0, ct) + tone(440.0, ct))
            v += exp(-ct * 2.0) * 0.15 * tone(1318.5, ct)
        }
        v
    }
}

/** Low drone, a velvet whoosh as the curtains part, then a slow warm chord. */
private fun curtainGen(): (Double) -> Double {
    val rnd = Random(9)
    var lp = 0.0
    return { t ->
        var v = 0.0
        val drone = minOf(1.0, t / 2.0) * exp(-maxOf(0.0, t - 4.5) * 0.6)
        v += drone * 0.28 * (tone(55.0, t) + 0.5 * tone(82.4, t))
        val wp = ((t - 0.8) / 2.4).coerceIn(0.0, 1.0)
        lp += 0.05 * ((rnd.nextDouble() * 2 - 1) - lp)
        v += sin(PI * wp).pow(2.0) * 1.8 * lp
        if (t >= 1.8) {
            val ct = t - 1.8
            val swell = (1 - exp(-ct * 1.5)) * exp(-ct * 0.35)
            v += swell * 0.13 * (tone(146.8, ct) + tone(185.0, ct) + tone(220.0, ct) + tone(293.7, ct))
        }
        if (t >= 3.0) v += exp(-(t - 3.0) * 2.0) * 0.15 * tone(587.3, t - 3.0)
        v
    }
}

/** A soft airy pad that swells and fades, with a few distant twinkling chimes. */
private fun starlightGen(): (Double) -> Double {
    val chimeAt = doubleArrayOf(1.2, 2.1, 3.0, 3.8)
    val chimeHz = doubleArrayOf(1568.0, 1319.0, 1760.0, 1175.0)
    return { t ->
        val env = sin(PI * (t / 5.2).coerceIn(0.0, 1.0)).pow(2.0)
        var v = env * 0.12 * (tone(261.6, t) + tone(329.6, t) + tone(392.0, t) + tone(493.9, t))
        for (i in chimeAt.indices) {
            val dt = t - chimeAt[i]
            if (dt in 0.0..1.5) v += exp(-dt * 3.0) * (0.14 * tone(chimeHz[i], dt) + 0.05 * tone(chimeHz[i] * 2, dt))
        }
        v
    }
}
