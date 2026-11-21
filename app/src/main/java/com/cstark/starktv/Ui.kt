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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyEvent
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
    val scale by animateFloatAsState(if (focused) scaleOnFocus else 1f, label = "focusScale")
    val shape = RoundedCornerShape(10.dp)
    var m = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
    if (fr != null) m = m.focusRequester(fr)
    Box(
        m.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocus?.invoke()
        }
            .clip(shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
            .onPreviewKeyEvent { e ->
                val k = e.nativeKeyEvent.keyCode
                if (onLongPress == null || (k != android.view.KeyEvent.KEYCODE_DPAD_CENTER && k != android.view.KeyEvent.KEYCODE_ENTER)) {
                    false
                } else if (e.type == KeyEventType.KeyDown) {
                    if (e.nativeKeyEvent.repeatCount > 0 && !longFired) {
                        longFired = true
                        onLongPress()
                    }
                    true
                } else if (e.type == KeyEventType.KeyUp) {
                    if (longFired) longFired = false else onClick()
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
                .padding(horizontal = if (compact) 10.dp else 20.dp, vertical = if (compact) 8.dp else 10.dp),
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
