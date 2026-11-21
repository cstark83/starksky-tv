package com.cstark.starktv

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Direct-plays the original file (no transcoding). Embedded and sidecar subtitles + audio tracks
 * are available from the player's settings/CC button. Reports progress back to Plex so resume
 * points stay in sync with every other Plex app.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    api: PlexApi,
    prefs: Prefs,
    req: Screen.Play,
    onNext: (String) -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var info by remember { mutableStateOf<PlayInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val introFocus = remember { FocusRequester() }

    // Tell Plex where we stopped (and mark watched past 90%), then release the player.
    DisposableEffect(Unit) {
        onDispose {
            val i = info
            if (i != null) {
                val pos = player.currentPosition
                val dur = i.item.duration
                val done = dur > 0 && pos > dur * 0.9
                CoroutineScope(Dispatchers.IO).launch {
                    api.timeline(i.item, "stopped", pos)
                    if (done) api.scrobble(i.item.ratingKey)
                }
            }
            player.release()
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val n = info?.nextKey
                    if (n != null) onNext(n) else onExit()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Playback error: ${e.errorCodeName}"
                // Keep the last failure so it can be read later in Settings.
                val cur = info?.item
                val title = when {
                    cur == null -> if (req.url != null) "Trailer" else "Unknown title"
                    cur.type == "episode" -> "${cur.label} ${cur.epCode}"
                    else -> cur.label
                }
                val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
                prefs.lastPlayError = listOfNotNull(time, title, e.errorCodeName, e.message?.take(200)).joinToString("  ·  ")
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(req) {
        try {
            val url: String
            val subs: List<MediaItem.SubtitleConfiguration>
            if (req.url != null) {
                url = req.url
                subs = emptyList()
            } else {
                val pi = api.playInfo(req.key)
                info = pi
                url = api.media(pi.partKey)
                subs = pi.subs.mapNotNull { s ->
                    val mime = when (s.codec.lowercase()) {
                        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                        else -> null
                    }
                    if (mime == null) {
                        null
                    } else {
                        MediaItem.SubtitleConfiguration.Builder(Uri.parse(api.media(s.key)))
                            .setMimeType(mime)
                            .setLanguage(s.lang)
                            .setLabel(s.label)
                            .build()
                    }
                }
            }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage("en")
                .setPreferredTextLanguage("en")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subsDefault)
                .build()
            val mi = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build()
            player.setMediaItem(mi, req.offset)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            error = e.message ?: "Couldn't start playback"
        }
    }

    // Position tick (for Skip Intro) + progress reporting every ~10s.
    LaunchedEffect(info) {
        val i = info ?: return@LaunchedEffect
        var tick = 0
        while (true) {
            delay(500)
            posMs = player.currentPosition
            tick++
            if (tick % 20 == 1 && player.isPlaying) api.timeline(i.item, "playing", posMs)
        }
    }

    // The screen itself holds focus so the remote always works. PlayerView only takes focus while
    // you're moving around its control bar (Up/Down opens it); when the bar hides, focus comes back here.
    val rootFocus = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }
    var heldKey by remember { mutableStateOf<Key?>(null) }
    var lastRepeatSeek by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    fun showControls() { playerView?.showController() } // also restarts the 4 s auto-hide timer
    fun seekBy(ms: Long) {
        val dur = player.duration
        var to = (player.currentPosition + ms).coerceAtLeast(0L)
        if (dur != C.TIME_UNSET && dur > 0) to = to.coerceAtMost(dur)
        player.seekTo(to)
        showControls()
    }
    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        showControls()
    }

    val i = info
    val showSkip = i != null && i.introStart >= 0 && i.introEnd > i.introStart && posMs >= i.introStart && posMs < i.introEnd
    // Skip Intro vanishing while focused would leave nothing focused: take focus back.
    LaunchedEffect(showSkip) { if (!showSkip) runCatching { rootFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                val k = e.key
                val dpad = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter ||
                    k == Key.DirectionLeft || k == Key.DirectionRight || k == Key.DirectionUp || k == Key.DirectionDown
                val media = k == Key.MediaPlayPause || k == Key.MediaPlay || k == Key.MediaPause ||
                    k == Key.MediaRewind || k == Key.MediaFastForward
                if (!dpad && !media) return@onPreviewKeyEvent false // Back, volume, etc. untouched
                // Control bar open with focus in it: let its buttons (CC, settings, seek bar) take the D-pad.
                if (dpad && playerView?.hasFocus() == true) return@onPreviewKeyEvent false
                // OK on Skip Intro presses the chip, not play/pause.
                val ok = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter
                if (ok && skipFocused) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val repeat = heldKey == k
                        heldKey = k
                        when (k) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> if (!repeat) togglePlay()
                            Key.MediaPlay -> { player.play(); showControls() }
                            Key.MediaPause -> { player.pause(); showControls() }
                            Key.DirectionLeft, Key.MediaRewind, Key.DirectionRight, Key.MediaFastForward -> {
                                val back = k == Key.DirectionLeft || k == Key.MediaRewind
                                val now = System.currentTimeMillis()
                                if (!repeat) {
                                    seekBy(if (back) -10_000L else 10_000L)
                                } else if (now - lastRepeatSeek >= 250) {
                                    // held down: bigger 30 s steps, a few per second
                                    lastRepeatSeek = now
                                    seekBy(if (back) -30_000L else 30_000L)
                                }
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                showControls()
                                playerView?.requestFocus() // into the control bar's buttons
                            }
                        }
                    }
                    KeyEventType.KeyUp -> if (heldKey == k) heldKey = null
                }
                true
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { c ->
                val exo = player
                PlayerView(c).apply {
                    this.player = exo
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowSubtitleButton(true)
                    keepScreenOn = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v ->
                            if (v != android.view.View.VISIBLE && hasFocus()) runCatching { rootFocus.requestFocus() }
                        },
                    )
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (showSkip && i != null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(48.dp, 48.dp, 48.dp, 110.dp).onFocusChanged { skipFocused = it.hasFocus }) {
                Chip("Skip Intro", fr = introFocus) {
                    player.seekTo(i.introEnd)
                    runCatching { rootFocus.requestFocus() }
                }
            }
            LaunchedEffect(Unit) { runCatching { introFocus.requestFocus() } }
        }

        val e = error
        if (e != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$e\n\nPress Back to return", fontSize = 20.sp, color = Color.White)
            }
        }
    }
}
