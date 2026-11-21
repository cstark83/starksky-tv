package com.cstark.starktv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

val LocalAccent = compositionLocalOf { Color(0xFFE5A00D) }

/** Posters from your server go through Plex's resizer; ready-made web URLs (Watchlist) are used as they are. */
fun PlexApi.pic(path: String?, w: Int, h: Int): String? =
    if (path != null && path.startsWith("http")) path else img(path, w, h)

object Pal {
    val Bg = Color(0xFF0B0B0F)
    val Card = Color(0xFF1C1C22)
    val Chip = Color(0xFF26262E)
    val Accent = Color(0xFFE5A00D)
    val Dim = Color(0xFFB0B0BA)
}

/** Anything the D-pad can land on: grows slightly and gets a white border when focused. */
@Composable
fun Focusable(
    modifier: Modifier = Modifier,
    fr: FocusRequester? = null,
    scaleOnFocus: Float = 1.06f,
    onClick: () -> Unit,
    onFocus: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable BoxScope.(Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // hold-OK: timed from the first key-down with a coroutine (key repeats on the SHIELD remote are not relied on)
    var okDown by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, label = "focusScale")
    val shape = RoundedCornerShape(10.dp)
    var m = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    if (fr != null) m = m.focusRequester(fr)
    Box(
        m.onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) {
                // focus left (e.g. into the options menu): forget the press, its key-up goes elsewhere
                holdJob?.cancel(); holdJob = null; okDown = false
            }
            if (it.isFocused) onFocus?.invoke()
        }
            .clip(shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
            // Before .clickable: with a long-press action, OK is handled here. A quick press (released before HOLD_OK_MS)
            // clicks on key-up; held for HOLD_OK_MS it opens the menu, and that press's key-up never clicks.
            .onPreviewKeyEvent { e ->
                if (onLongPress == null || !isOkKey(e.key)) {
                    false
                } else if (e.type == KeyEventType.KeyDown) {
                    if (!okDown) {
                        okDown = true
                        longFired = false
                        holdJob = scope.launch {
                            delay(HOLD_OK_MS)
                            longFired = true
                            onLongPress()
                        }
                    }
                    true
                } else if (e.type == KeyEventType.KeyUp) {
                    val j = holdJob
                    holdJob = null
                    okDown = false
                    if (j != null && j.isActive && !longFired) { j.cancel(); onClick() }
                    longFired = false
                    true
                } else {
                    false
                }
            }
            .clickable(onClick = onClick),
    ) {
        this.content(focused)
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    fr: FocusRequester? = null,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    Focusable(modifier, fr, 1.05f, onClick) { f ->
        Box(
            (if (fill) Modifier.fillMaxSize() else Modifier)
                .background(if (f) Color.White else if (selected) accent else Color(0x33FFFFFF))
                .padding(horizontal = if (compact) 8.dp else 20.dp, vertical = if (compact) 8.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (f || selected) Color.Black else Color.White,
                fontSize = if (compact) 14.sp else 16.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
fun Progress(p: Float, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(accent))
    }
}

@Composable
fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 20.sp, color = Pal.Dim)
    }
}

@Composable
fun PosterCard(
    api: PlexApi,
    item: Item,
    landscape: Boolean,
    fr: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val w = if (landscape) 280.dp else 150.dp
    val h = if (landscape) 158.dp else 225.dp
    Column(Modifier.width(w)) {
        Focusable(Modifier.size(w, h), fr, 1.06f, onClick, onFocus, onLongPress) {
            val path = if (landscape) {
                if (item.type == "episode") item.thumb ?: item.art else item.art ?: item.thumb
            } else {
                item.posterPath
            }
            AsyncImage(
                model = api.pic(path, if (landscape) 560 else 300, if (landscape) 316 else 450),
                contentDescription = item.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(Pal.Card),
            )
            if (item.progress > 0f) {
                Progress(item.progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        Text(
            item.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (landscape && item.type == "episode") {
            Text(
                "${item.epCode} · ${item.title}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = Pal.Dim,
            )
        }
    }
}

// ---------------------------------------------------------------- hold-OK menus

/** How long OK/Select must be held to open an options menu (player sleep timer, title options). */
const val HOLD_OK_MS = 3000L

fun isOkKey(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter

/**
 * Menu opened by holding OK for [HOLD_OK_MS]. It is drawn over the screen (same window, so focus never jumps to another
 * window), takes focus on its first option and keeps it there, and stays open until an option is picked or Back is pressed.
 * The OK that is still held down when it opens (its key repeats and the key-up when it is released) is swallowed, so letting
 * go never picks an option: the menu only "arms" after OK is released, or after 600 ms without any OK key event.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HoldMenu(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit, selected: Int = -1) {
    val first = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    var lastOk by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        while (!armed) {
            delay(100)
            if (System.currentTimeMillis() - lastOk > 600) armed = true
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .onPreviewKeyEvent { e ->
                when {
                    isOkKey(e.key) && !armed -> {
                        lastOk = System.currentTimeMillis()
                        if (e.type == KeyEventType.KeyUp) armed = true
                        true
                    }
                    // close on key-up, so the Back key-up can't also leave the screen underneath
                    e.key == Key.Back -> {
                        if (e.type == KeyEventType.KeyUp) onDismiss()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .background(Pal.Card, RoundedCornerShape(16.dp))
                .padding(32.dp)
                .focusProperties { exit = { FocusRequester.Cancel } } // D-pad can't leave the menu
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 22.sp, color = Color.White)
            options.forEachIndexed { i, (label, act) ->
                Chip(label, selected = i == selected, fr = if (i == 0) first else null, onClick = act)
            }
        }
    }
}
