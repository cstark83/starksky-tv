package com.cstark.starktv

import android.net.Uri
import androidx.compose.foundation.background
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

    LaunchedEffect(playerView) { playerView?.requestFocus() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val i = info
        if (i != null && i.introStart >= 0 && i.introEnd > i.introStart && posMs >= i.introStart && posMs < i.introEnd) {
            Box(Modifier.align(Alignment.BottomEnd).padding(48.dp, 48.dp, 48.dp, 110.dp)) {
                Chip("Skip Intro", fr = introFocus) {
                    player.seekTo(i.introEnd)
                    playerView?.requestFocus()
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
