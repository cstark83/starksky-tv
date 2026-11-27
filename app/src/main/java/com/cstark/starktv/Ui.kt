package com.cstark.starktv

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min

val LocalAccent = compositionLocalOf { Color(0xFFE5A00D) }

/**
 * Layout sizes from the real screen (dp), so they fit any TV, not just one. [side] and [bottom] are the overscan-safe
 * margins: 5% of the screen width / height (bottom at least 32dp); nothing is drawn closer to the TV edge than that
 * (backgrounds, the player and the weather page excepted). [posterH] is the standard 2:3 poster height: at most 225dp
 * (the old fixed size) and at most 40% of the screen height (216dp on a 1080p SHIELD, which is 960x540dp).
 */
class TvDims(val screenW: Dp, val screenH: Dp) {
    val side: Dp = screenW * 0.05f
    val bottom: Dp = max(32.dp, screenH * 0.05f)
    val posterH: Dp = min(225.dp, screenH * 0.40f)
    val posterW: Dp get() = posterW(posterH)
    /** Grid column width for posters of [posterH]: the card plus the 16dp gap. */
    val gridCell: Dp get() = posterW + 16.dp
}

fun posterW(h: Dp): Dp = h * 2f / 3f

@Composable
fun tvDims(): TvDims {
    val c = LocalConfiguration.current
    return remember(c.screenWidthDp, c.screenHeightDp) { TvDims(c.screenWidthDp.dp, c.screenHeightDp.dp) }
}

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
    /** Scale animation length in ms (null = the default spring). Chips use 120 ms. */
    scaleMs: Int? = null,
    /** Posters/cards: lift by this much and glow in the accent color when focused. */
    lift: Dp = 0.dp,
    /** Called with true/false whenever focus arrives or leaves. */
    onFocusChange: ((Boolean) -> Unit)? = null,
    /** Drawn dimmer (a neighbour in the same row is focused). */
    dimmed: Boolean = false,
    content: @Composable BoxScope.(Boolean) -> Unit,
) {
    val accent = LocalAccent.current
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // hold-OK: timed from the first key-down with a coroutine (key repeats on the SHIELD remote are not relied on)
    var okDown by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(
        if (focused) scaleOnFocus else 1f,
        if (scaleMs != null) tween(scaleMs) else spring(),
        label = "focusScale",
    )
    val shape = RoundedCornerShape(10.dp)
    // focused on top of its neighbours (a grown chip or poster is never drawn under the next one)
    val liftPx = with(LocalDensity.current) { lift.toPx() }
    val glow = lift > 0.dp
    val up by animateFloatAsState(if (focused && glow) 1f else 0f, tween(150), label = "lift")
    val dimA by animateFloatAsState(if (dimmed && !focused) 0.6f else 1f, tween(150), label = "dim")
    var m = modifier.zIndex(if (focused) 1f else 0f).graphicsLayer {
        scaleX = scale; scaleY = scale
        alpha = dimA
        if (glow) {
            translationY = -liftPx * up
            shadowElevation = 22.dp.toPx() * up
            this.shape = RoundedCornerShape(10.dp)
            ambientShadowColor = accent
            spotShadowColor = accent
        }
    }
    if (fr != null) m = m.focusRequester(fr)
    Box(
        m.onFocusChanged {
            if (focused != it.isFocused) onFocusChange?.invoke(it.isFocused)
            focused = it.isFocused
            if (!it.isFocused) {
                // focus left (e.g. into the options menu): forget the press, its key-up goes elsewhere
                holdJob?.cancel(); holdJob = null; okDown = false
            }
            if (it.isFocused) onFocus?.invoke()
        }
            .clip(shape)
            .border(if (focused) 4.dp else 0.dp, if (focused) accent else Color.Transparent, shape)
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

/** Unfocused, unselected chip fill: dark enough to read on bright artwork. */
val ChipIdle = Color(0xB3202028)

@Composable
fun Chip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    fr: FocusRequester? = null,
    compact: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current
    // Focused: about 1.2x (120 ms) with bold text; layout size never changes. Very wide chips (Settings lines) grow at most
    // ~48dp in total, so they don't run off the screen edge.
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val maxGrow = with(density) { 48.dp.toPx() }
    val grow = if (widthPx > 0) minOf(1.2f, 1f + maxGrow / widthPx) else 1.2f
    Focusable(modifier.onSizeChanged { widthPx = it.width }, fr, grow, onClick, onFocus, onLongPress, scaleMs = 120) { f ->
        Box(
            (if (fill) Modifier.fillMaxSize() else Modifier)
                .background(if (f) Color.White else if (selected) accent else ChipIdle)
                .padding(horizontal = if (compact) 8.dp else 20.dp, vertical = if (compact) 8.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (f || selected) Color.Black else Color.White,
                fontWeight = if (f) FontWeight.Bold else FontWeight.Normal,
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

/**
 * Poster (2:3) or landscape (16:9) card with its title under it. [posterH] is the 2:3 height (default: [TvDims.posterH]);
 * landscape cards are 70% of that tall. With [revealLabel] (default) focusing a card also scrolls its title line into
 * view, so grids and rows never leave the label under the bottom edge; Home rows pass false (they snap whole rows).
 */
/** One row / grid of posters: the focused one stays bright, its neighbours are dimmed slightly. */
class DimGroup { var focused by mutableStateOf<String?>(null) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    api: PlexApi,
    item: Item,
    landscape: Boolean,
    fr: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    posterH: Dp = tvDims().posterH,
    revealLabel: Boolean = true,
    dim: DimGroup? = null,
    onClick: () -> Unit,
) {
    val h = if (landscape) posterH * 0.70f else posterH
    val w = if (landscape) h * 16f / 9f else posterW(posterH)
    val reveal = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val me = item.ratingKey
    Column(Modifier.width(w).bringIntoViewRequester(reveal)) {
        Focusable(
            Modifier.size(w, h), fr, 1.08f, onClick,
            onFocus = {
                onFocus?.invoke()
                if (revealLabel) scope.launch { delay(30); reveal.bringIntoView() }
            },
            onLongPress = onLongPress,
            lift = 4.dp,
            onFocusChange = { has -> if (dim != null) { if (has) dim.focused = me else if (dim.focused == me) dim.focused = null } },
            dimmed = dim?.focused != null && dim.focused != me,
        ) { f ->
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
            if (item.shownProgress > 0f) {
                Progress(item.shownProgress, Modifier.align(Alignment.BottomCenter))
            }
            // focused: title, year, rating, runtime (episode: SxEy and its title) over the bottom of the poster; no layout shift
            if (f) {
                val l1 = if (item.type == "episode") "${item.epCode} · ${item.title}" else item.label
                val l2 = listOfNotNull(
                    if (item.type == "episode") item.label else item.year.takeIf { it > 0 }?.toString(),
                    item.contentRating,
                    item.duration.takeIf { it > 0 && (item.type == "movie" || item.type == "episode") }?.let { fmtRuntime(it) },
                ).joinToString(" · ")
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = if (item.shownProgress > 0f) 9.dp else 6.dp),
                ) {
                    Text(l1, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                    if (l2.isNotBlank()) Text(l2, fontSize = 11.sp, color = Color(0xFFDDDDDD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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

/**
 * One hold-OK menu for a screen (Library grid, title page). `open(...)` shows [HoldMenu] with the given options; picking
 * one closes the menu first and then runs it. On close (Back, or an option that stays on this screen) focus goes back to
 * [returnTo], the card or button that was held, so the grid/list position and the A-Z index keep working.
 */
class HoldMenuState {
    var title by mutableStateOf("")
        private set
    var options by mutableStateOf<List<Pair<String, () -> Unit>>?>(null)
        private set
    private var returnTo: FocusRequester? = null
    var restore by mutableStateOf(0)
        private set
    /** Bumped on every open, so a menu opened from another menu's option (a confirmation) starts fresh. */
    var serial by mutableStateOf(0)
        private set
    fun open(title: String, options: List<Pair<String, () -> Unit>>, returnTo: FocusRequester?) {
        this.title = title; this.returnTo = returnTo; this.options = options; serial++
    }
    /** "Mark all 10 episodes of Season 2 as watched?" No / Yes, focus on No. */
    fun confirm(question: String, returnTo: FocusRequester?, onYes: () -> Unit) {
        open(question, listOf("No" to {}, "Yes" to onYes), returnTo)
    }
    fun close() { options = null; restore++ }
    fun refocus() { returnTo?.let { r -> runCatching { r.requestFocus() } } }
}

@Composable
fun rememberHoldMenu() = remember { HoldMenuState() }

/** Draws the menu of [s] when it is open; place it last in a full-screen Box so it covers the screen. */
@Composable
fun HoldMenuHost(s: HoldMenuState) {
    // back to the held card/button, unless the option opened another menu (a confirmation) in the meantime
    LaunchedEffect(s.restore) { if (s.restore > 0) { delay(50); if (s.options == null) s.refocus() } }
    val opts = s.options ?: return
    key(s.serial) {
        HoldMenu(s.title, opts.map { (label, act) -> label to { s.close(); act() } }, onDismiss = { s.close() }, selected = 0)
    }
}

/**
 * Back for the whole app. Compose turns a Back key that no handler consumed into a focus "Exit" (FocusDirection.Exit):
 * with focus inside a LazyRow/LazyColumn/LazyVerticalGrid or a focusGroup that move succeeds, the key-down is swallowed and the
 * activity never sees Back, so no BackHandler ran (title pages, library grids, Search, Settings, Discover did nothing).
 * This bubble-phase handler sits above every screen: when a BackHandler is enabled, the first Back key-down goes to it and
 * is consumed, so it never reaches the focus system (key repeats from holding Back are swallowed, so one press = one page).
 * The key-up is left alone; the activity ignores it because it never saw the key-down. Preview handlers still run first,
 * so an open HoldMenu and the player's next-episode countdown keep consuming Back themselves. With no BackHandler enabled
 * (Home, first tab) it does nothing and Back behaves as before. Stateless on purpose (recomposition can't lose a flag).
 */
fun Modifier.backToBackHandlers(dispatcher: () -> OnBackPressedDispatcher?): Modifier = onKeyEvent { e ->
    if (e.key != Key.Back || e.type != KeyEventType.KeyDown) return@onKeyEvent false
    val d = dispatcher()
    if (d == null || !d.hasEnabledCallbacks()) return@onKeyEvent false
    if (e.nativeKeyEvent.repeatCount == 0) d.onBackPressed()
    true
}

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

/**
 * Where focus goes when you come Back (build 24). Screens record their area in [restoreTo] when it gets focus
 * ("home", "lib:<section>", "discover", "weather", "chip:Search", "settings:switch"); App's pop() copies it into [pending]
 * for the screen being returned to, and that screen puts focus back on the exact item it remembers and clears [pending].
 */
object NavMemory {
    var restoreTo: String? = null
    var pending: String? = null
    /** Last focused Home poster: "<row title>|<ratingKey>". */
    var homeKey: String? = null
    /** Library grids: last focused index, scroll state and sort per section ("<section>|<sort>" / section key). */
    val libTarget = HashMap<String, Int>()
    val libGrid = HashMap<String, LazyGridState>()
    val libSort = HashMap<String, String>()

    fun take(target: String): Boolean = if (pending == target) { pending = null; true } else false

    fun clear() {
        restoreTo = null; pending = null; homeKey = null
        libTarget.clear(); libGrid.clear(); libSort.clear()
    }
}

/** Focus [fr] once its item is laid out (a lazy list composes it a frame after scrolling). True when it worked. */
suspend fun focusSoon(fr: FocusRequester, tries: Int = 15): Boolean {
    repeat(tries) {
        if (runCatching { fr.requestFocus() }.isSuccess) return true
        delay(40)
    }
    return false
}

/** "1 season", "3 seasons". */
fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"
