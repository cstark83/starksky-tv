package com.cstark.starktv

import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTrackNameProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/*
 * The player's own control bar (build 39), drawn in Compose instead of Media3's stock bar, whose focus was a faint
 * see-through grey circle that couldn't be seen on a real TV.
 *  - Dark gradient behind the controls, every button on its own dark circle (55% black), unfocused buttons at 70%.
 *  - Focused = inverted: solid circle in the accent color (white when the accent is too dark), dark icon, 4 dp white ring,
 *    1.2x bigger.
 *  - CC / Audio / Speed buttons show their state under the icon ("English", "Off", "1.25x"); their menus open on the
 *    active row, show full names, scroll with the focused row in view; picking closes the menu and focus returns to the
 *    button. Picks go through trackSelectionParameters like the stock menu did, so the per-show memory still records them.
 *  - Seek bar: 6 dp on a dark track, with a thumb and a white ring while focused; Left/Right move 5% (like the stock bar),
 *    it seeks 1 s after the last press or on OK.
 */

private val CircleDim = Color(0x8C000000)
private val Ink = Color(0xFF101010)
private val LabelDim = Color(0xB3FFFFFF)
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Focus fill: the accent, or white when the accent is too dark to stand out. */
@Composable
fun focusFill(): Color {
    val a = LocalAccent.current
    return if (a.luminance() < 0.3f) Color.White else a
}

private enum class CtlMenu { Captions, Audio, Speed }

private class MenuRow(val name: String, val active: Boolean, val pick: () -> Unit)

@OptIn(UnstableApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PlayerControls(
    player: ExoPlayer,
    playFr: FocusRequester,
    hasNext: Boolean,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onBarFocus: (Boolean) -> Unit,
    onMenu: (Boolean) -> Unit,
    log: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val names = remember { DefaultTrackNameProvider(ctx.resources) }
    val fill = focusFill()

    var pos by remember { mutableLongStateOf(player.currentPosition) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var showPause by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var params by remember { mutableStateOf(player.trackSelectionParameters) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            buffered = player.bufferedPosition
            val st = player.playbackState
            showPause = player.playWhenReady && st != Player.STATE_ENDED && st != Player.STATE_IDLE
            delay(250)
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onTrackSelectionParametersChanged(p: TrackSelectionParameters) { params = p }
            override fun onPlaybackParametersChanged(p: PlaybackParameters) { speed = p.speed }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // ---- tracks
    fun list(type: Int): List<Pair<Tracks.Group, Int>> = tracks.groups.filter { it.type == type }
        .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
    fun selected(type: Int): Format? {
        if (type in params.disabledTrackTypes) return null
        for (g in tracks.groups) {
            if (g.type != type || !g.isSelected) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        }
        return null
    }
    fun fullName(f: Format) = names.getTrackName(f).ifBlank { f.language ?: "Unknown" }
    fun shortName(f: Format?): String {
        if (f == null) return "Off"
        val l = f.language?.takeIf { it.isNotBlank() && it != "und" }
        val d = l?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.takeIf { it.isNotBlank() && it != l }
        return (d ?: fullName(f)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
    fun uniqueNames(rows: List<Pair<Tracks.Group, Int>>): List<String> {
        val raw = rows.map { (g, i) -> fullName(g.getTrackFormat(i)) }
        return raw.mapIndexed { k, n -> if (raw.count { it == n } > 1) "$n (${raw.take(k + 1).count { it == n }})" else n }
    }
    fun spd(s: Float) = (if (s == s.toInt().toFloat()) "${s.toInt()}" else "$s").trimEnd('0') + "x"

    val textSel = selected(C.TRACK_TYPE_TEXT)
    val audioSel = selected(C.TRACK_TYPE_AUDIO)

    var menu by remember { mutableStateOf<CtlMenu?>(null) }
    val ccFr = remember { FocusRequester() }
    val audioFr = remember { FocusRequester() }
    val speedFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    fun openMenu(m: CtlMenu?) { menu = m; onMenu(m != null) }
    fun closeMenu(back: FocusRequester) {
        openMenu(null)
        scope.launch { focusSoon(back) }
    }
    DisposableEffect(Unit) { onDispose { if (menu != null) onMenu(false) } }

    Box(Modifier.fillMaxSize()) {
        // dark gradient behind the control area
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000), Color(0x99000000)))),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, bottom = 32.dp)
                .onFocusChanged { onBarFocus(it.hasFocus) },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f).padding(top = 18.dp), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        CtlButton(androidx.media3.ui.R.drawable.exo_ic_skip_previous, 56.dp, 30.dp) { onSeekTo(0L) }
                        CtlButton(androidx.media3.ui.R.drawable.exo_ic_rewind, 56.dp, 44.dp, badge = "15") { onSeekBy(-15_000L) }
                        CtlButton(
                            if (showPause) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play,
                            76.dp, 44.dp, fr = playFr, onClick = onPlayPause,
                        )
                        CtlButton(androidx.media3.ui.R.drawable.exo_ic_forward, 56.dp, 44.dp, badge = "15") { onSeekBy(15_000L) }
                        CtlButton(androidx.media3.ui.R.drawable.exo_ic_skip_next, 56.dp, 30.dp, enabled = hasNext, onClick = onNext)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CtlButton(
                        if (textSel != null) androidx.media3.ui.R.drawable.exo_ic_subtitle_on else androidx.media3.ui.R.drawable.exo_ic_subtitle_off,
                        52.dp, 30.dp, fr = ccFr,
                        tint = if (textSel != null) fill else Color.White,
                        label = shortName(textSel), labelColor = if (textSel != null) fill else LabelDim,
                    ) { openMenu(CtlMenu.Captions) }
                    CtlButton(
                        androidx.media3.ui.R.drawable.exo_ic_audiotrack, 52.dp, 28.dp, fr = audioFr,
                        label = if (audioSel != null) shortName(audioSel) else "Audio",
                    ) { openMenu(CtlMenu.Audio) }
                    CtlButton(
                        androidx.media3.ui.R.drawable.exo_ic_speed, 52.dp, 28.dp, fr = speedFr,
                        tint = if (speed != 1f) fill else Color.White,
                        label = spd(speed), labelColor = if (speed != 1f) fill else Color.White,
                    ) { openMenu(CtlMenu.Speed) }
                }
            }
            Spacer(Modifier.height(14.dp))
            SeekBar(pos, dur, buffered, fill, onSeekTo, onPlayPause)
        }

        val m = menu
        if (m != null) {
            val back = when (m) { CtlMenu.Captions -> ccFr; CtlMenu.Audio -> audioFr; CtlMenu.Speed -> speedFr }
            fun setParams(p: TrackSelectionParameters, what: String) {
                player.trackSelectionParameters = p // not programmatic: the per-show track memory records it
                log("control bar: $what")
                closeMenu(back)
            }
            val rows: List<MenuRow> = when (m) {
                CtlMenu.Captions -> {
                    val l = list(C.TRACK_TYPE_TEXT)
                    val n = uniqueNames(l)
                    listOf(
                        MenuRow("Off", textSel == null) {
                            setParams(player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(), "subtitles off")
                        },
                    ) + l.mapIndexed { k, (g, i) ->
                        MenuRow(n[k], textSel != null && g.isSelected && g.isTrackSelected(i)) {
                            setParams(player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build(), "subtitles ${n[k]}")
                        }
                    }
                }
                CtlMenu.Audio -> {
                    val l = list(C.TRACK_TYPE_AUDIO)
                    val n = uniqueNames(l)
                    l.mapIndexed { k, (g, i) ->
                        MenuRow(n[k], g.isSelected && g.isTrackSelected(i)) {
                            setParams(player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build(), "audio ${n[k]}")
                        }
                    }
                }
                CtlMenu.Speed -> {
                    val cur = SPEEDS.minBy { kotlin.math.abs(it - speed) }
                    SPEEDS.map { s ->
                        MenuRow(if (s == 1f) "Normal (1x)" else spd(s), s == cur) {
                            player.setPlaybackSpeed(s); log("control bar: speed ${spd(s)}"); closeMenu(back)
                        }
                    }
                }
            }
            val title = when (m) { CtlMenu.Captions -> "Subtitles"; CtlMenu.Audio -> "Audio"; CtlMenu.Speed -> "Speed" }
            TrackMenu(title, rows, fill, onClose = { closeMenu(back) }, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Round control button: dark circle, dim; focused = solid [focusFill] circle, dark icon, white ring, 1.2x. */
@Composable
private fun CtlButton(
    @DrawableRes icon: Int,
    size: Dp,
    iconSize: Dp,
    fr: FocusRequester? = null,
    enabled: Boolean = true,
    tint: Color = Color.White,
    badge: String? = null,
    label: String? = null,
    labelColor: Color = Color.White,
    onClick: () -> Unit,
) {
    var f by remember { mutableStateOf(false) }
    val fill = focusFill()
    val scale by animateFloatAsState(if (f) 1.2f else 1f, label = "ctlScale")
    Column(
        Modifier.width(if (label != null) 96.dp else size),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var m = Modifier.size(size).graphicsLayer {
            scaleX = scale; scaleY = scale
            alpha = if (f) 1f else if (enabled) 0.7f else 0.3f
        }
        if (fr != null) m = m.focusRequester(fr)
        m = m.onFocusChanged { f = it.isFocused }.background(if (f) fill else CircleDim, CircleShape)
        if (f) m = m.border(4.dp, Color.White, CircleShape)
        Box(
            m.clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = if (f) Ink else tint, modifier = Modifier.size(iconSize))
            if (badge != null) {
                Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (f) Ink else tint, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (label != null) {
            Text(
                label, fontSize = 15.sp, fontWeight = if (f) FontWeight.Bold else FontWeight.Normal,
                color = if (f) Color.White else labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** 6 dp bar on a dark track; focused: thicker, white ring, thumb. Left/Right preview 5% steps, seek after 1 s or OK. */
@Composable
private fun SeekBar(pos: Long, dur: Long, buffered: Long, fill: Color, onSeekTo: (Long) -> Unit, onPlayPause: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    fun commit() {
        job?.cancel(); job = null
        scrub?.let { onSeekTo(it) }
        scrub = null
    }
    val shown = scrub ?: pos
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(shown), fontSize = 20.sp, color = if (scrub != null) fill else Color.White, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(86.dp))
        BoxWithConstraints(
            Modifier.weight(1f).height(34.dp)
                .onFocusChanged { f = it.isFocused; if (!it.isFocused && scrub != null) commit() }
                .onPreviewKeyEvent { e ->
                    val k = e.key
                    when {
                        k == Key.DirectionLeft || k == Key.DirectionRight -> {
                            if (e.type == KeyEventType.KeyDown && dur > 0) {
                                val step = (dur / 20).coerceAtLeast(1000L)
                                val base = scrub ?: pos
                                scrub = (base + if (k == Key.DirectionLeft) -step else step).coerceIn(0L, dur)
                                job?.cancel()
                                job = scope.launch { delay(1000); commit() }
                            }
                            true
                        }
                        isOkKey(k) -> {
                            if (e.type == KeyEventType.KeyUp) { if (scrub != null) commit() else onPlayPause() }
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val h = if (f) 10.dp else 6.dp
            val shape = RoundedCornerShape(50)
            val p = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f
            val b = if (dur > 0) (buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
            var track = Modifier.fillMaxWidth().height(h).clip(shape).background(Color(0xCC000000))
            if (f) track = track.border(2.dp, Color.White, shape)
            Box(track) {
                Box(Modifier.fillMaxWidth(b).fillMaxHeight().background(Color(0x55FFFFFF)))
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(fill))
            }
            if (f) {
                Box(
                    Modifier.offset(x = maxWidth * p - 12.dp).size(24.dp)
                        .background(fill, CircleShape).border(3.dp, Color.White, CircleShape),
                )
            }
        }
        Text(clock(dur), fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(86.dp).padding(start = 14.dp))
    }
}

/** Captions / audio / speed list: opens on the active row, full names, focused row kept in view. Back closes it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TrackMenu(title: String, rows: List<MenuRow>, fill: Color, onClose: () -> Unit, modifier: Modifier) {
    val active = rows.indexOfFirst { it.active }.coerceAtLeast(0)
    val frs = remember(rows.size) { List(rows.size) { FocusRequester() } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (active - 2).coerceAtLeast(0))
    LaunchedEffect(Unit) { if (frs.isNotEmpty()) focusSoon(frs[active]) }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000))
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onClose(); true } else false
            },
    ) {
        Column(
            modifier.padding(end = 48.dp, bottom = 150.dp).widthIn(min = 340.dp, max = 640.dp).heightIn(max = maxH)
                .background(Color(0xF2141418), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(16.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            Text(title, fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, bottom = 10.dp))
            if (rows.size <= 1 && title == "Subtitles") {
                Text("No subtitles in this file", fontSize = 16.sp, color = LabelDim, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
            }
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(rows) { i, r ->
                    var f by remember { mutableStateOf(false) }
                    val shape = RoundedCornerShape(10.dp)
                    var m = Modifier.fillMaxWidth().focusRequester(frs[i]).onFocusChanged { f = it.isFocused }
                        .background(if (f) fill else Color.Transparent, shape)
                    if (f) m = m.border(3.dp, Color.White, shape)
                    Row(
                        m.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = r.pick)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(36.dp)) {
                            if (r.active) {
                                Icon(painterResource(androidx.media3.ui.R.drawable.exo_ic_check), null,
                                    tint = if (f) Ink else fill, modifier = Modifier.size(26.dp))
                            }
                        }
                        Text(
                            r.name, fontSize = 20.sp,
                            fontWeight = if (f || r.active) FontWeight.Bold else FontWeight.Normal,
                            color = when { f -> Ink; r.active -> fill; else -> Color.White },
                        )
                    }
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L)) / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
