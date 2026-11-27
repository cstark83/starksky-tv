package com.cstark.starktv

import androidx.annotation.OptIn
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Seconds the "Next episode in …" overlay counts down before playing the next episode. */
private const val NEXT_COUNTDOWN_S = 10

/** With no Plex credits marker, the countdown starts this long before the end. */
private const val NO_CREDITS_LEAD_MS = 30_000L
/** Rewind step: one tap of Left/Rewind and the control bar rewind button (user asked 15 s). */
private const val REWIND_MS = 15_000L
/** Fast-forward step for one tap of Right/Fast-forward (control bar keeps ExoPlayer's 15 s). */
private const val FORWARD_MS = 10_000L
/** Held Left/Right: bigger steps, a few per second. */
private const val HELD_SEEK_MS = 30_000L

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
    onHome: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { ExoPlayer.Builder(ctx).setSeekBackIncrementMs(REWIND_MS).build() }
    var info by remember { mutableStateOf<PlayInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    val introFocus = remember { FocusRequester() }
    // Media3 MediaSession: exists while this player screen is open (playing or paused), so the system (and the Tower
    // auto-updater) can see the playback state. Plain in-process session: no service, no background playback.
    var session by remember { mutableStateOf<MediaSession?>(null) }

    // Next-episode countdown: null = not showing, else seconds left. Used once per playthrough.
    var countdown by remember { mutableStateOf<Int?>(null) }
    var countdownUsed by remember { mutableStateOf(false) }
    var countdownCancelled by remember { mutableStateOf(false) }
    // The countdown reached 0 before this episode really ended: keep playing, switch at STATE_ENDED (or OK = play now).
    var countdownDone by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var scrobbled by remember { mutableStateOf(false) }
    // Resume rewind: a resume starts [prefs.resumeRewind] s before the saved Plex position. Until playback passes that
    // saved position again (or you seek), no progress is sent to Plex, so Plex keeps the old resume point and stopping
    // early doesn't move it back by the rewind every time. 0 = not holding (normal saving).
    val rs = remember { object { var holdUntil = 0L } }
    fun saveAllowed(pos: Long) = rs.holdUntil <= 0L || pos >= rs.holdUntil

    // Sleep timer (0 = off) and the hold-OK menu.
    var sleepEndAt by remember { mutableLongStateOf(0L) }
    var sleepLeftMin by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    // Control bar (PlayerControls.kt): shown 4 s after the last key, always while paused / ended (like the stock bar).
    var controlsVisible by remember { mutableStateOf(false) }
    var hideAt by remember { mutableLongStateOf(0L) }
    var barFocused by remember { mutableStateOf(false) }
    var ctlMenuOpen by remember { mutableStateOf(false) }
    val playFr = remember { FocusRequester() }

    // Track memory: flags read inside the player listener (plain fields, not Compose state).
    val trk = remember { object { var programmatic = false; var userPicked = false; var applied = false } }

    // true once the player was released early by ON_STOP (Home button, TV off, SHIELD asleep)
    var released by remember { mutableStateOf(false) }

    // Debug log of every start / stop / end / leave with position and duration (PlayLog, files/playlog.txt + logcat).
    fun plog(event: String, pos: Long = runCatching { player.currentPosition }.getOrDefault(-1L)) {
        val dur = runCatching { player.duration }.getOrDefault(C.TIME_UNSET)
        val cur = info?.item
        val what = when {
            cur == null -> if (req.url != null) "trailer" else "key=${req.key}"
            cur.type == "episode" -> "${cur.label} ${cur.epCode} (${cur.ratingKey})"
            else -> "${cur.label} (${cur.ratingKey})"
        }
        PlayLog.add(ctx, "$event | pos ${fmtMs(pos)} / player ${fmtMs(dur)} / plex ${fmtMs(cur?.duration ?: 0L)} | $what")
    }

    // Watched mark (Watched.kt queue: logged with its HTTP status, retried until Plex takes it, even after a restart).
    // Rule: the end of the file, the next-episode countdown (credits reached), passing 90% while playing, or leaving past 90%
    // ALWAYS marks it watched, once per playthrough.
    fun markDone(why: String) {
        val i = info ?: return
        if (scrobbled || req.url != null) return
        scrobbled = true
        plog("mark watched: $why")
        val it0 = i.item
        val label = if (it0.type == "episode") "${it0.label} ${it0.epCode}" else it0.label
        CoroutineScope(Dispatchers.IO).launch { api.scrobble(it0.ratingKey, label, why) }
    }

    // Tell Plex where we stopped (and mark watched past 90%), then release the session and the player.
    DisposableEffect(Unit) {
        onDispose {
            val i = info
            if (!released) plog("leave player (closed: Back, next episode, end or Home)")
            if (i != null && !released) {
                val pos = player.currentPosition
                val dur = i.item.duration
                val done = dur > 0 && pos > dur * 0.9
                val save = saveAllowed(pos)
                if (!save) plog("position not saved: still before the saved resume point ${fmtMs(rs.holdUntil)}", pos)
                CoroutineScope(Dispatchers.IO).launch {
                    if (save) api.timeline(i.item, "stopped", pos)
                }
                if (done) markDone("left the player past 90% (${pos * 100 / dur}%)")
            }
            session?.release()
            session = null
            if (!released) player.release()
        }
    }

    // App left the screen (Home button, another app, TV off, SHIELD asleep) = ON_STOP. Not ON_PAUSE, so volume popups,
    // system dialogs and the notification shade don't stop playback. Pause, report the position to Plex at once (same
    // timeline call as the normal progress sync, so the resume point is right), release the session and the player right
    // away (no audio, no session, nothing left in the background), then leave the player like Back does, so coming back
    // lands on the screen it was opened from (the title page reloads and Resume starts from the saved spot).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_STOP && !released) {
                plog("ON_STOP: app left the screen (Home, other app, TV off), saving position and closing the player")
                released = true
                sleepEndAt = 0L
                countdown = null
                player.pause()
                val i = info
                val pos = player.currentPosition
                val dur = i?.item?.duration ?: 0L
                val done = dur > 0 && pos > dur * 0.9
                if (i != null) {
                    val save = saveAllowed(pos)
                    if (!save) plog("position not saved: still before the saved resume point ${fmtMs(rs.holdUntil)}", pos)
                    CoroutineScope(Dispatchers.IO).launch {
                        if (save) api.timeline(i.item, "stopped", pos)
                    }
                    if (done) markDone("app left the screen past 90% (${pos * 100 / dur}%)")
                }
                session?.release()
                session = null
                player.release()
                onExit()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun setParams(p: TrackSelectionParameters) {
        trk.programmatic = true
        player.trackSelectionParameters = p
        trk.programmatic = false
    }

    fun playNext(why: String) {
        val i = info ?: return
        val n = i.nextKey ?: return
        plog("next episode: $why")
        countdown = null
        countdownDone = false
        // reached the credits: count it as watched even when the credits are longer than the last 10%
        markDone("next episode ($why)")
        onNext(n)
    }

    fun startCountdown(why: String) {
        if (countdownUsed || info?.nextKey == null) return
        plog("countdown start ($why)")
        markDone("next-episode countdown started ($why)")
        countdownUsed = true
        countdown = NEXT_COUNTDOWN_S
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    plog("STATE_ENDED (the file really ended)")
                    markDone("end of the file")
                    val n = info?.nextKey
                    when {
                        n == null -> onExit()
                        countdownCancelled -> Unit // you cancelled the countdown: stay on this episode
                        countdownDone -> playNext("episode ended after the countdown")
                        countdown == null -> startCountdown("end reached, no countdown yet") // very short credits
                        // still counting: the countdown effect switches when it reaches 0
                    }
                } else if (playbackState == Player.STATE_BUFFERING && player.duration > 0 &&
                    player.duration - player.currentPosition < 60_000L
                ) {
                    plog("buffering near the end")
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    lastSeekAt = System.currentTimeMillis()
                }
                // You moved on purpose (rewind/forward, scrub, Skip Intro): from here the real position is saved again.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && rs.holdUntil > 0L) {
                    plog("seek during resume rewind: progress saving to Plex on again", newPosition.positionMs)
                    rs.holdUntil = 0L
                }
            }

            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                if (!trk.programmatic) trk.userPicked = true // picked in the player's audio/subtitle menu
            }

            override fun onTracksChanged(tracks: Tracks) {
                val item = info?.item ?: return
                if (item.type != "episode") return // movies: nothing saved, nothing applied
                val show = item.grandparentRatingKey ?: return
                if (tracks.groups.isEmpty()) return
                if (!trk.applied) {
                    trk.applied = true
                    val p = applySavedTracks(player.trackSelectionParameters, tracks, prefs.trackChoice(show))
                    if (p != null) setParams(p)
                    return
                }
                if (trk.userPicked) {
                    trk.userPicked = false
                    prefs.setTrackChoice(show, describeTracks(tracks, player.trackSelectionParameters))
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                plog("ERROR ${e.errorCodeName}")
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
            setParams(
                player.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage("en")
                    .setPreferredTextLanguage("en")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subsDefault)
                    .build(),
            )
            val mi = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build()
            // Resume rewind: only a library item resumed from a saved position (offset > 0). Fresh plays, Start over,
            // the next-episode auto-advance (all offset 0) and trailers (url) start exactly where asked.
            val start = if (req.url == null && req.offset > 0L) resumeStartMs(req.offset, prefs.resumeRewind) else req.offset
            if (req.url == null && req.offset > 0L) {
                rs.holdUntil = req.offset
                plog("resume: saved offset ${fmtMs(req.offset)}, rewind ${prefs.resumeRewind} s, start at ${fmtMs(start)}", start)
            }
            player.setMediaItem(mi, start)
            plog("start at ${fmtMs(start)}", start)
            player.prepare()
            player.playWhenReady = true
            if (session == null) {
                session = MediaSession.Builder(ctx, player).setId("starktv-${System.currentTimeMillis()}").build()
            }
        } catch (e: Exception) {
            error = e.message ?: "Couldn't start playback"
        }
    }

    // Position tick (for Skip Intro and the next-episode countdown) + progress reporting every ~10s.
    LaunchedEffect(info) {
        val i = info ?: return@LaunchedEffect
        var tick = 0
        while (true) {
            delay(500)
            posMs = player.currentPosition
            tick++
            if (rs.holdUntil > 0L && posMs >= rs.holdUntil) {
                plog("passed the saved resume point ${fmtMs(rs.holdUntil)}: progress saving to Plex on again", posMs)
                rs.holdUntil = 0L
            }
            if (tick % 20 == 1 && player.isPlaying && saveAllowed(posMs)) api.timeline(i.item, "playing", posMs)
            // passed 90% while really playing (not just landed there by a seek): watched now, not only when leaving
            val d90 = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: i.item.duration
            if (!scrobbled && d90 > 0 && posMs > d90 * 0.9 && player.isPlaying && System.currentTimeMillis() - lastSeekAt > 3000) {
                markDone("passed 90% while playing (${posMs * 100 / d90}%)")
            }
            // Credits reached by playing (not while you're seeking around): start the countdown once.
            val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: i.item.duration
            val at = if (i.creditsStart > 0) i.creditsStart else dur - NO_CREDITS_LEAD_MS
            if (i.nextKey != null && !countdownUsed && dur > 0 && at > 0 && posMs >= at && player.isPlaying &&
                System.currentTimeMillis() - lastSeekAt > 1500
            ) {
                startCountdown(if (i.creditsStart > 0) "Plex credits marker at ${fmtMs(i.creditsStart)}" else "30 s before the end")
            }
        }
    }

    // Countdown: one second at a time. At 0 the next episode starts only if this one has really ended (STATE_ENDED);
    // otherwise the credits keep playing and the switch happens at the end (the listener above). OK = play now.
    val counting = countdown != null
    LaunchedEffect(counting) {
        if (!counting) return@LaunchedEffect
        while ((countdown ?: return@LaunchedEffect) > 0) {
            delay(1000)
            countdown = countdown?.minus(1)
        }
        if (player.playbackState == Player.STATE_ENDED) {
            playNext("countdown finished after the end")
        } else {
            countdownDone = true
            plog("countdown at 0, still playing: waiting for the real end")
        }
    }

    // Sleep timer: tick each second; at the end fade out, pause, save the position to Plex, go Home.
    LaunchedEffect(sleepEndAt) {
        if (sleepEndAt == 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < sleepEndAt) {
            sleepLeftMin = (((sleepEndAt - System.currentTimeMillis()) + 59_999) / 60_000).toInt()
            delay(1000)
        }
        for (v in 20 downTo 0) {
            player.volume = v / 20f
            delay(150)
        }
        player.pause()
        val i = info
        val pos = player.currentPosition
        plog("sleep timer ended: paused, going Home", pos)
        if (i != null && saveAllowed(pos)) api.timeline(i.item, "stopped", pos)
        player.volume = 1f
        sleepEndAt = 0L
        onHome()
    }

    // The screen itself holds focus so the remote always works. PlayerView only takes focus while
    // you're moving around its control bar (Up/Down opens it); when the bar hides, focus comes back here.
    val rootFocus = remember { FocusRequester() }
    var skipFocused by remember { mutableStateOf(false) }
    var heldKey by remember { mutableStateOf<Key?>(null) }
    var lastRepeatSeek by remember { mutableLongStateOf(0L) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var okSwallowUp by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(menuOpen) {
        heldKey = null // the menu took the key-up, so don't treat the next OK as a repeat
        if (!menuOpen) runCatching { rootFocus.requestFocus() }
    }

    fun showControls() { controlsVisible = true; hideAt = System.currentTimeMillis() + 4000 } // (re)starts the 4 s auto-hide
    fun seekBy(ms: Long) {
        val dur = player.duration
        var to = (player.currentPosition + ms).coerceAtLeast(0L)
        if (dur != C.TIME_UNSET && dur > 0) to = to.coerceAtMost(dur)
        player.seekTo(to)
        lastSeekAt = System.currentTimeMillis()
        showControls()
    }
    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        showControls()
    }

    val i = info
    val showSkip = i != null && i.introStart >= 0 && i.introEnd > i.introStart && posMs >= i.introStart && posMs < i.introEnd
    // Control bar visibility: always shown while paused or ended, else hidden 4 s after the last key (not while a menu is open).
    LaunchedEffect(Unit) {
        while (!released) {
            delay(200)
            val st = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE)
            val hold = st == Player.STATE_ENDED || (st != Player.STATE_IDLE && !player.playWhenReady)
            val now = System.currentTimeMillis()
            if (hold) {
                controlsVisible = true; hideAt = now + 4000
            } else if (controlsVisible && !ctlMenuOpen && !menuOpen && now > hideAt) {
                val had = barFocused
                controlsVisible = false
                if (had) { delay(50); runCatching { rootFocus.requestFocus() } }
            }
        }
    }

    // Skip Intro vanishing while focused would leave nothing focused: take focus back.
    LaunchedEffect(showSkip) { if (!showSkip && !menuOpen) runCatching { rootFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (controlsVisible) hideAt = maxOf(hideAt, System.currentTimeMillis() + 4000) // any key keeps the bar up
                if (menuOpen || ctlMenuOpen) return@onPreviewKeyEvent false // the sleep-timer / track menus handle their own keys
                val k = e.key
                // Next-episode countdown: OK = play now, Back = cancel (and stay; this Back never leaves the player).
                if (countdown != null) {
                    if (isOkKey(k)) {
                        if (e.type == KeyEventType.KeyDown) { okSwallowUp = true; playNext("OK = play now") }
                        return@onPreviewKeyEvent true
                    }
                    if (k == Key.Back) {
                        if (e.type == KeyEventType.KeyUp) {
                            countdown = null; countdownDone = false; countdownCancelled = true
                            plog("countdown cancelled with Back, keeps playing")
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                val dpad = isOkKey(k) ||
                    k == Key.DirectionLeft || k == Key.DirectionRight || k == Key.DirectionUp || k == Key.DirectionDown
                val media = k == Key.MediaPlayPause || k == Key.MediaPlay || k == Key.MediaPause ||
                    k == Key.MediaRewind || k == Key.MediaFastForward
                if (!dpad && !media) return@onPreviewKeyEvent false // Back, volume, etc. untouched
                // Control bar open with focus in it: let its buttons (CC, settings, seek bar) take the D-pad.
                if (dpad && barFocused) return@onPreviewKeyEvent false
                // OK on Skip Intro presses the chip, not play/pause.
                val ok = isOkKey(k)
                if (ok && skipFocused) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val repeat = heldKey == k
                        heldKey = k
                        when (k) {
                            // OK: a quick press pauses/resumes on release; held for HOLD_OK_MS it opens the sleep-timer menu
                            // (timed from the first key-down, not from key repeats).
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (!repeat) {
                                holdJob?.cancel()
                                holdJob = scope.launch {
                                    delay(HOLD_OK_MS)
                                    holdJob = null
                                    menuOpen = true
                                }
                            }
                            Key.MediaPlayPause -> if (!repeat) togglePlay()
                            Key.MediaPlay -> { player.play(); showControls() }
                            Key.MediaPause -> { player.pause(); showControls() }
                            Key.DirectionLeft, Key.MediaRewind, Key.DirectionRight, Key.MediaFastForward -> {
                                val back = k == Key.DirectionLeft || k == Key.MediaRewind
                                val now = System.currentTimeMillis()
                                if (!repeat) {
                                    seekBy(if (back) -REWIND_MS else FORWARD_MS)
                                } else if (now - lastRepeatSeek >= 250) {
                                    // held down: bigger 30 s steps, a few per second
                                    lastRepeatSeek = now
                                    seekBy(if (back) -HELD_SEEK_MS else HELD_SEEK_MS)
                                }
                            }
                            Key.DirectionUp, Key.DirectionDown -> {
                                showControls()
                                scope.launch { focusSoon(playFr) } // into the control bar, on play/pause
                            }
                        }
                    }
                    KeyEventType.KeyUp -> {
                        if (heldKey == k) heldKey = null
                        if (ok) {
                            val j = holdJob
                            if (okSwallowUp) {
                                okSwallowUp = false
                            } else if (j != null && j.isActive) {
                                j.cancel() // released before 3 s: a normal OK
                                holdJob = null
                                togglePlay()
                            }
                        }
                    }
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
                    useController = false // own control bar below (PlayerControls.kt): video + subtitles only here
                    keepScreenOn = true
                    isFocusable = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible && !released) {
            PlayerControls(
                player = player,
                playFr = playFr,
                hasNext = info?.nextKey != null,
                onSeekBy = { seekBy(it) },
                onSeekTo = { player.seekTo(it); lastSeekAt = System.currentTimeMillis(); showControls() },
                onPlayPause = {
                    if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0L); player.play(); showControls() } else togglePlay()
                },
                onNext = { playNext("next button on the control bar") },
                onBarFocus = { barFocused = it },
                onMenu = { ctlMenuOpen = it; showControls() },
                log = { plog(it) },
            )
        }

        // Sleep timer: shown with the control bar while a timer is set.
        if (sleepEndAt > 0L && controlsVisible) {
            Text(
                "Sleep timer: $sleepLeftMin min",
                fontSize = 16.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        if (showSkip) {
            Box(Modifier.align(Alignment.BottomEnd).padding(48.dp, 48.dp, 48.dp, if (controlsVisible) 250.dp else 110.dp).onFocusChanged { skipFocused = it.hasFocus }) {
                Chip("Skip Intro", fr = introFocus) {
                    player.seekTo(i.introEnd)
                    lastSeekAt = System.currentTimeMillis()
                    runCatching { rootFocus.requestFocus() }
                }
            }
            LaunchedEffect(Unit) { if (!menuOpen) runCatching { introFocus.requestFocus() } }
        }

        // Next-episode countdown: not focusable, keys are handled above.
        val cd = countdown
        val nx = i?.next
        if (cd != null && i != null) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(48.dp, 48.dp, 48.dp, if (controlsVisible) 250.dp else 110.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                val what = if (nx != null) "${nx.grandparentTitle ?: i.item.label} ${nx.epCode}" else "next episode"
                Text(
                    if (countdownDone) "Next episode when this one ends: $what" else "Next episode in ${cd}s: $what",
                    fontSize = 18.sp, color = Color.White,
                )
                Text("OK = play now  ·  Back = cancel", fontSize = 14.sp, color = Pal.Dim)
            }
        }

        val e = error
        if (e != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$e\n\nPress Back to return", fontSize = 20.sp, color = Color.White)
            }
        }

        if (menuOpen) {
            val choose = { min: Int ->
                sleepEndAt = if (min == 0) 0L else System.currentTimeMillis() + min * 60_000L
                sleepLeftMin = min
                menuOpen = false
            }
            val cur = when {
                sleepEndAt == 0L -> 0
                else -> -1
            }
            HoldMenu(
                title = if (sleepEndAt > 0L) "Sleep timer ($sleepLeftMin min left)" else "Sleep timer",
                options = listOf(
                    "Off" to { choose(0) },
                    "30 min" to { choose(30) },
                    "60 min" to { choose(60) },
                    "90 min" to { choose(90) },
                    "Cancel" to { menuOpen = false },
                ),
                onDismiss = { menuOpen = false },
                selected = cur,
            )
        }
    }
}

// ---------------------------------------------------------------- remembered audio / subtitle choice (per show)

@OptIn(UnstableApi::class)
private fun lang(s: String?): String? = s?.let { Util.normalizeLanguageCode(it) }?.lowercase()?.substringBefore('-')

private fun isForced(f: androidx.media3.common.Format) =
    (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 || (f.label ?: "").contains("forced", ignoreCase = true)

private fun isSdh(f: androidx.media3.common.Format) =
    (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 || (f.label ?: "").contains("SDH", ignoreCase = true)

/** "a=<lang>;t=off" or "a=<lang>;t=<lang>,f=0|1,s=0|1" from what is playing now. */
private fun describeTracks(tracks: Tracks, p: TrackSelectionParameters): String {
    fun selected(type: Int): androidx.media3.common.Format? {
        for (g in tracks.groups) {
            if (g.type != type || !g.isSelected) continue
            for (t in 0 until g.length) if (g.isTrackSelected(t)) return g.getTrackFormat(t)
        }
        return null
    }
    val a = lang(selected(C.TRACK_TYPE_AUDIO)?.language) ?: ""
    val tf = selected(C.TRACK_TYPE_TEXT)
    val off = C.TRACK_TYPE_TEXT in p.disabledTrackTypes || tf == null ||
        // the player's subtitle "Off" keeps only forced subtitles; a forced track playing then still means "off"
        ((p.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 && isForced(tf))
    val t = if (off) "off" else "${lang(tf!!.language) ?: ""},f=${if (isForced(tf)) 1 else 0},s=${if (isSdh(tf)) 1 else 0}"
    return "a=$a;t=$t"
}

/** New parameters that select the saved choice, or null when nothing is saved / nothing matches (= default behavior). */
private fun applySavedTracks(p: TrackSelectionParameters, tracks: Tracks, saved: String?): TrackSelectionParameters? {
    if (saved.isNullOrBlank()) return null
    val parts = saved.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val b = p.buildUpon()
    var changed = false
    val a = parts["a"].orEmpty()
    if (a.isNotEmpty()) {
        loop@ for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (t in 0 until g.length) if (g.isTrackSupported(t) && lang(g.getTrackFormat(t).language) == a) {
                b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, t)); changed = true; break@loop
            }
        }
    }
    val t = parts["t"].orEmpty()
    if (t == "off") {
        b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) // never auto-enable
        changed = true
    } else if (t.isNotEmpty()) {
        val tl = t.substringBefore(',')
        val forced = t.contains("f=1"); val sdh = t.contains("s=1")
        var best: Pair<Tracks.Group, Int>? = null
        var bestScore = -1
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                if (!g.isTrackSupported(i) || lang(f.language) != tl) continue
                val score = (if (isForced(f) == forced) 2 else 0) + (if (isSdh(f) == sdh) 1 else 0)
                if (score > bestScore) { best = g to i; bestScore = score }
            }
        }
        best?.let { (g, i) ->
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            changed = true
        }
    }
    return if (changed) b.build() else null
}

// ---------------------------------------------------------------- playback debug log

private fun fmtMs(ms: Long): String {
    if (ms == C.TIME_UNSET || ms < 0) return "?"
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d.%01d", s / 3600, s / 60 % 60, s % 60, ms % 1000 / 100)
}

/**
 * Last 300 playback events (start, countdown, STATE_ENDED, next episode, Back/ON_STOP/sleep-timer leave, errors) with
 * position and duration: files/playlog.txt in the app's own storage (debug build: adb shell run-as com.cstark.starktv
 * cat files/playlog.txt) and logcat tag StarskyPlay.
 */
object PlayLog {
    fun add(ctx: android.content.Context, msg: String) {
        val line = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        android.util.Log.i("StarskyPlay", line)
        runCatching {
            val f = File(ctx.filesDir, "playlog.txt")
            val old = if (f.exists()) f.readLines().takeLast(299) else emptyList()
            f.writeText((old + line).joinToString("\n") + "\n")
        }
    }
}
