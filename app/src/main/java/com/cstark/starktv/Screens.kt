package com.cstark.starktv

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.Normalizer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable

// ---------------------------------------------------------------- sign in

@Composable
fun LoginScreen(api: PlexApi, prefs: Prefs, onDone: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val (id, c) = api.createPin()
                code = c
                var tries = 0
                while (tries < 150) {
                    delay(2000)
                    tries++
                    val tok = api.pollPin(id)
                    if (tok != null) {
                        prefs.accountToken = tok
                        val (name, cands) = api.discoverServer(tok)
                        prefs.serverName = name
                        prefs.candidates = cands
                        onDone()
                        return@LaunchedEffect
                    }
                }
            } catch (e: Exception) {
                err = e.message
                delay(3000)
            }
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("STARSKY TV", color = Pal.Accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("On your phone or computer, go to", fontSize = 20.sp, color = Pal.Dim)
        Text("plex.tv/link", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("and enter this code:", fontSize = 20.sp, color = Pal.Dim)
        Text(code ?: "…", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = Pal.Accent)
        if (err != null) Text(err ?: "", color = Color(0xFFFF6B6B))
    }
}

// ---------------------------------------------------------------- boot

@Composable
fun BootScreen(
    api: PlexApi,
    prefs: Prefs,
    onLogin: () -> Unit,
    onProfiles: () -> Unit,
    onReady: () -> Unit,
    onSignOut: () -> Unit,
) {
    var status by remember { mutableStateOf("Connecting to your Plex server…") }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        status = "Connecting to your Plex server…"
        if (prefs.accountToken == null) { onLogin(); return@LaunchedEffect }
        if (prefs.userToken == null) { onProfiles(); return@LaunchedEffect }
        try {
            var cands = prefs.candidates
            if (cands.isEmpty()) {
                val (name, found) = api.discoverServer(prefs.accountToken!!)
                prefs.serverName = name
                prefs.candidates = found
                cands = found
            }
            api.connect((listOfNotNull(prefs.lastBase) + cands).distinct())
            prefs.lastBase = api.base
            api.sections()
            onReady()
        } catch (e: Exception) {
            status = if (e.message?.contains("HTTP 401") == true) {
                "Plex rejected this profile (401). Pick another profile or sign out."
            } else {
                e.message ?: "Couldn't connect"
            }
            failed = true
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(status, fontSize = 20.sp, color = Pal.Dim)
        if (failed) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("Retry") { attempt++ }
                Chip("Switch profile") { prefs.userToken = null; attempt++ }
                Chip("Sign out", onClick = onSignOut)
            }
        }
    }
}

// ---------------------------------------------------------------- profiles

@Composable
fun ProfilesScreen(api: PlexApi, prefs: Prefs, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<Profile>?>(null) }
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        val list = try { api.homeUsers(prefs.accountToken ?: "") } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) {
            // No Plex Home set up: just use the account itself.
            prefs.userToken = prefs.accountToken
            prefs.serverToken = null
            prefs.userName = "Me"
            prefs.restricted = false
            onDone()
        } else {
            profiles = list
        }
    }
    LaunchedEffect(profiles) {
        if (profiles != null) runCatching { first.requestFocus() }
    }

    fun choose(p: Profile, pin: String?) {
        scope.launch {
            try {
                val tok = api.switchProfile(prefs.accountToken ?: "", p.uuid, pin)
                prefs.userToken = tok
                prefs.serverToken = api.serverAccessToken(tok)
                prefs.userName = p.title
                prefs.restricted = p.kid && !p.admin
                onDone()
            } catch (e: Exception) {
                error = if (p.protected && (e.message?.contains("401") == true || e.message?.contains("403") == true)) "Wrong PIN, try again" else (e.message ?: "Couldn't switch profile")
            }
        }
    }

    val list = profiles
    val pinProfile = pinFor
    if (list == null) { Centered("Loading profiles…"); return }

    if (pinProfile != null) {
        BackHandler { pinFor = null; error = null }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PinPad(
                name = pinProfile.title,
                error = error,
                onSubmit = { choose(pinProfile, it) },
                onCancel = { pinFor = null; error = null },
            )
        }
        return
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Who's watching?", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = tvDims().side),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            list.forEachIndexed { i, p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Focusable(
                        Modifier.size(140.dp),
                        if (i == 0) first else null,
                        1.1f,
                        onClick = {
                            error = null
                            if (p.protected) pinFor = p else choose(p, null)
                        },
                    ) {
                        AsyncImage(
                            model = p.thumb,
                            contentDescription = p.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(Pal.Card),
                        )
                    }
                    Text(p.title, modifier = Modifier.padding(top = 10.dp), fontSize = 18.sp)
                    if (p.protected) Text("PIN", fontSize = 12.sp, color = Pal.Dim)
                }
            }
        }
        if (error != null) Text(error ?: "", color = Color(0xFFFF6B6B), modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun PinPad(name: String, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    fun press(k: String) {
        if (k == "⌫") {
            pin = pin.dropLast(1)
        } else if (k == "OK") {
            onSubmit(pin)
        } else if (pin.length < 4) {
            pin += k
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Enter PIN for $name", fontSize = 22.sp)
        Text(if (pin.isEmpty()) "—" else "●".repeat(pin.length), fontSize = 32.sp)
        if (error != null) Text(error, color = Color(0xFFFF6B6B))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        keys.chunked(3).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                r.forEach { k ->
                    Chip(k, modifier = Modifier.size(84.dp, 52.dp), fill = true) { press(k) }
                }
            }
        }
        Chip("Cancel", onClick = onCancel)
    }
}

// ---------------------------------------------------------------- main (tabs)

data class HomeRow(
    val title: String,
    val items: List<Item>,
    val landscape: Boolean,
    val playDirect: Boolean,
    /** true = titles that may not be in your library yet (the Watchlist) */
    val external: Boolean = false,
)

class HomeData {
    var sections by mutableStateOf<List<Section>>(emptyList())
    var rows by mutableStateOf<List<HomeRow>?>(null)
    var error by mutableStateOf<String?>(null)
    var tab by mutableIntStateOf(0)
    var selected by mutableStateOf<Item?>(null)
    var selectedDeck by mutableStateOf(false)
    var selectedExternal by mutableStateOf(false)
    var discover by mutableStateOf<List<Pair<String, List<Media>>>?>(null)
    var discoverError by mutableStateOf<String?>(null)
    val discoverTab: Int get() = sections.size + 1
    val settingsTab: Int get() = sections.size + 2
    val listState = LazyListState()
    /** Scroll position of each Home row (by title), kept so Back can put focus on the poster you came from. */
    val rowStates = HashMap<String, LazyListState>()
    /** The Discover tab chip: Up from Discover's own chips goes there. */
    val discoverChipFr = FocusRequester()
    /** Time of the last successful loadHome (0 = not yet); MainScreen copies it into prefs.lastSync. */
    var lastSync by mutableLongStateOf(0L)

    fun reset() {
        sections = emptyList()
        rows = null
        error = null
        tab = 0
        selected = null
        discover = null
        discoverError = null
        rowStates.clear()
        NavMemory.clear()
    }
}

/** Most items in the Home "Continue Watching" row (in progress, one per show, most recently watched first). */
const val CONTINUE_WATCHING_MAX = 15

/** Loads the Home rows. Returns false when Plex couldn't be reached (the old rows, if any, stay on screen). */
suspend fun loadHome(api: PlexApi, d: HomeData): Boolean {
    try {
        val secs = api.sections()
        d.sections = secs
        val rows = mutableListOf<HomeRow>()

        // Plex's "on deck" mixes things you're mid-way through with the next unwatched episodes.
        // Split them, keep one entry per show, and cap both rows so they stay short.
        val deck = api.onDeck().distinctBy { it.ratingKey }
        val cont = deck
            .filter { it.resumable }   // same rule as every Resume label / progress bar
            .distinctBy { it.showKey }
            .take(CONTINUE_WATCHING_MAX)
        val contKeys = cont.map { it.showKey }.toSet()
        val next = deck
            .filter { it.viewOffset == 0L && it.showKey !in contKeys }
            .distinctBy { it.showKey }
            .take(12)
        if (cont.isNotEmpty()) rows += HomeRow("Continue Watching", cont, true, true)
        if (next.isNotEmpty()) rows += HomeRow("Next Up", next, true, true)

        // Plex Watchlist: the same list every Plex app shows for this profile
        val wl = api.watchlist().take(30)
        if (wl.isNotEmpty()) rows += HomeRow("Watchlist", wl, false, false, external = true)

        var tvDone = false
        var movDone = false
        val added = mutableListOf<Item>()
        for (s in secs) {
            val items = api.recent(s).distinctBy { it.ratingKey }
            if (items.isEmpty()) continue
            added += items
            val title = when {
                s.type == "show" && !tvDone -> { tvDone = true; "New TV" }
                s.type == "movie" && !movDone -> { movDone = true; "New Movies" }
                else -> "New in ${s.title}"
            }
            rows += HomeRow(title, items, false, false)
        }

        // Recently Added: the newest movies and shows across every library this profile can see (the server token, so a
        // kid profile only gets what its restrictions allow, like every other row), after the Watchlist
        val newest = added.sortedByDescending { it.addedAt }.distinctBy { it.showKey }.take(15)
        if (newest.isNotEmpty()) {
            val at = rows.indexOfFirst { it.external }.let { if (it >= 0) it + 1 else rows.count { r -> r.playDirect } }
            rows.add(at, HomeRow("Recently Added", newest, false, false))
        }

        // Recently watched: newest first across all libraries, minus anything already up top.
        val shown = (cont + next).map { it.showKey }.toSet()
        val watched = mutableListOf<Item>()
        for (s in secs) {
            try { watched += api.recentlyWatched(s) } catch (_: Exception) { }
        }
        val recent = watched
            .sortedByDescending { it.lastViewedAt }
            .distinctBy { it.ratingKey }
            .filter { it.showKey !in shown }
            .take(15)
        if (recent.isNotEmpty()) rows += HomeRow("Recently Watched", recent, false, false)

        d.rows = rows
        d.error = null
        d.lastSync = System.currentTimeMillis()
        return true
    } catch (e: Exception) {
        if (d.rows == null) d.error = RETRY_MSG
        return false
    }
}

const val RETRY_MSG = "Can't reach Plex, retrying…"

/** Wait before retry number [n] (1-based) after Plex couldn't be reached: 10 s for the first 5 tries, then 30 s. */
fun retryDelayMs(n: Int) = if (n <= 5) 10_000L else 30_000L

@Composable
fun MainScreen(
    api: PlexApi,
    prefs: Prefs,
    home: HomeData,
    refresh: Int,
    onDetail: (String) -> Unit,
    onPlay: (Item) -> Unit,
    onPreviewIntro: () -> Unit,
    onNowPlaying: () -> Unit,
    ov: OverseerrApi,
    onRequest: (Media) -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onWeather: () -> Unit,
    onYtVideo: (YtVideo) -> Unit,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Updater.check(ctx) }
    // Re-read the kid flag of the current profile (profiles picked before this build, or changed on plex.tv)
    LaunchedEffect(Unit) {
        val acct = prefs.accountToken
        val me = prefs.userName
        if (acct != null && me != null && me != "Me") {
            val p = try { api.homeUsers(acct).firstOrNull { it.title == me } } catch (e: Exception) { null }
            if (p != null) prefs.restricted = p.kid && !p.admin
        }
    }
    LaunchedEffect(refresh) {
        // Plex not reachable on load: "Can't reach Plex, retrying…" and try again (10 s, after 5 tries 30 s) until it works
        var n = 0
        while (!loadHome(api, home) && home.rows == null) delay(retryDelayMs(++n))
        // keep in step with Plex (other devices, other apps) while this screen is open
        while (true) {
            delay(90_000)
            loadHome(api, home)
        }
    }
    LaunchedEffect(home.lastSync) { if (home.lastSync > 0) prefs.lastSync = home.lastSync }
    // a watched/unwatched mark reached Plex (hand mark, player, or a queued retry): Continue Watching, Next Up and
    // Recently Watched reload so marked items leave or appear
    LaunchedEffect(MarkQueue.version) { if (MarkQueue.version > 0) loadHome(api, home) }
    // "Updated to build N": shown ~4 s after an update (not on the very first launch), then the new number is saved
    var updatedTo by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val cur = Updater.current(ctx)
        val last = prefs.lastBuild
        val firstEver = last == 0 && prefs.accountToken == null
        if (!firstEver && cur > last) {
            updatedTo = cur
            delay(4000)
            updatedTo = 0
        }
        prefs.lastBuild = cur
    }
    // Back from a tab (library, Discover, Settings): Home, with focus on the poster you were last on
    BackHandler(enabled = home.tab != 0) { home.tab = 0; NavMemory.pending = "home" }
    LaunchedEffect(home.tab, home.rows != null, refresh) {
        if (home.tab == home.discoverTab && home.rows != null && home.discover == null) {
            loadDiscover(api, ov, home)
        }
    }
    val accent = rememberAccent(api, if (home.tab == 0) home.selected else null)

    CompositionLocalProvider(LocalAccent provides accent) {
        Box(Modifier.fillMaxSize()) {
            if (home.tab == 0) HeroBackground(api, home.selected)
            Column(Modifier.fillMaxSize()) {
                // Spotify: Starsky's own Now Playing screen (it offers to open the Spotify app only when nothing is playing)
                TopBar(prefs, home, onSearch, onSwitchProfile, onWeather, onNowPlaying)
                // bottom safe margin (TV overscan) for every tab; Home sizes its rows from what is left
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = tvDims().bottom)) {
                    val t = home.tab
                    when {
                        t == 0 -> HomeScreen(api, home, ov, onDetail, onPlay, onNowPlaying, onRequest)
                        t <= home.sections.size -> {
                            val sec = home.sections[t - 1]
                            // YouTube tab: in-app YouTube (search + trending), Plex's YouTube library one press away.
                            // Kid profiles keep only the Plex library (online YouTube has no content rating).
                            if (sec.title.equals("YouTube", ignoreCase = true) && !prefs.restricted) {
                                YouTubeTab(onYtVideo) { LibraryScreen(api, sec, refresh, onPlay) { onDetail(it.showKey) } }
                            } else {
                                LibraryScreen(api, sec, refresh, onPlay) { onDetail(it.showKey) }
                            }
                        }
                        t == home.discoverTab -> DiscoverScreen(home, ov, onRequest)
                        else -> SettingsScreen(api, prefs, ov, onPreviewIntro, onSync, onSwitchProfile, onSignOut)
                    }
                }
            }
            // "Update" on the top bar: full-screen progress; Back cancels (and stops a download)
            if (Updater.nowOpen) {
                BackHandler { Updater.cancelUpdateNow() }
                UpdatePanel()
            }
            if (updatedTo > 0) {
                // plain text, not focusable: never takes focus from Home
                Text(
                    "Updated to build $updatedTo",
                    fontSize = 16.sp,
                    color = Color.Black,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(32.dp)
                        .background(LocalAccent.current, RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val AZ = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** Index letter for a title: A–Z from its Plex sort title (accents stripped), "#" for digits and symbols. */
private fun azLetter(item: Item): Char {
    val s = (item.titleSort ?: item.title).trim()
    if (s.isEmpty()) return '#'
    val c = Normalizer.normalize(s.substring(0, 1), Normalizer.Form.NFD)[0].uppercaseChar()
    return if (c in 'A'..'Z') c else '#'
}

@Composable
fun LibraryScreen(api: PlexApi, section: Section, refresh: Int, onPlay: (Item) -> Unit, onOpen: (Item) -> Unit) {
    // Back from a title opened here: same sort, same scroll position, focus on the poster you opened (NavMemory)
    val area = "lib:${section.key}"
    val restoring = remember(section.key) { NavMemory.pending == area }
    var sort by rememberSaveable(section.key) {
        mutableStateOf(if (restoring) NavMemory.libSort[section.key] ?: "titleSort:asc" else "titleSort:asc")
    }
    val memKey = "${section.key}|$sort"
    var items by remember(section.key, sort) { mutableStateOf<List<Item>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = remember(section.key, sort) {
        (if (restoring) NavMemory.libGrid[memKey] else null) ?: LazyGridState().also { NavMemory.libGrid[memKey] = it }
    }
    // The grid item focus returns to when you press Left from the A–Z column (last focused, or last jumped to).
    var target by remember(section.key, sort) { mutableIntStateOf(if (restoring) NavMemory.libTarget[memKey] ?: 0 else 0) }
    val targetFr = remember { FocusRequester() }
    // hold OK on a poster: options menu (same HoldMenu / HOLD_OK_MS as Home); focus returns to the poster (targetFr) on close
    val menu = rememberHoldMenu()
    var wlKeys by remember(section.key) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(section.key) { wlKeys = api.watchlist().map { it.ratingKey }.toSet() }
    fun setSeen(item: Item, watched: Boolean) {
        // update the card in place at once (no reload), so the grid keeps its scroll position and focus; the mark queue
        // (Watched.kt) gets it to Plex, retrying if needed
        items = items?.map { if (it.ratingKey == item.ratingKey) it.copy(viewCount = if (watched) maxOf(1, it.viewCount) else 0, viewOffset = 0L) else it }
        scope.launch {
            val what = if (item.type == "episode") "${item.label} ${item.epCode}" else item.label
            markToast(ctx, api.setWatched(item.ratingKey, watched, what), what, watched)
        }
    }
    /** Shows (and seasons): asks first, with the episode count, focus on No. */
    fun confirmSeen(item: Item, watched: Boolean) {
        scope.launch {
            val n = api.leafCount(item.ratingKey)
            val all = if (n > 0) "all ${plural(n, "episode")} of" else "every episode of"
            menu.confirm("Mark $all ${item.label} as ${if (watched) "watched" else "unwatched"}?", targetFr) { setSeen(item, watched) }
        }
    }
    fun openMenu(item: Item) {
        val playable = item.type == "movie" || item.type == "episode"
        val dkey = item.guid?.takeIf { it.startsWith("plex://") }?.substringAfterLast("/")
        val opts = buildList<Pair<String, () -> Unit>> {
            if (playable) {
                add((if (item.resumable) "Resume" else "Play") to { onPlay(item) })
                add("Details" to { onOpen(item) })
            } else {
                add("Open" to { onOpen(item) })
            }
            if (playable) {
                add((if (item.watchedDone) "Mark unwatched" else "Mark watched") to { setSeen(item, !item.watchedDone) })
            } else {
                add("Mark show watched" to { confirmSeen(item, true) })
                add("Mark show unwatched" to { confirmSeen(item, false) })
            }
            if (dkey != null) {
                val on = dkey in wlKeys
                add((if (on) "Remove from Watchlist" else "Add to Watchlist") to {
                    scope.launch { if (api.setWatchlist(dkey, !on)) wlKeys = if (on) wlKeys - dkey else wlKeys + dkey }
                    Unit
                })
            }
        }
        menu.open(item.label, opts, targetFr)
    }

    LaunchedEffect(section.key, sort, refresh) {
        // same retry as Home: 10 s, after 5 tries 30 s, until the library loads (leaving the tab or Back stops it)
        var n = 0
        while (true) {
            try { items = api.library(section, sort); err = null; break } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (items == null) err = RETRY_MSG
                delay(retryDelayMs(++n))
            }
        }
    }

    LaunchedEffect(items != null) {
        val list = items
        if (restoring && list != null && NavMemory.take(area) && target in list.indices) {
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == target }) gridState.scrollToItem(target)
            focusSoon(targetFr)
        }
    }

    val tv = tvDims()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = tv.side, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("A–Z", sort == "titleSort:asc") { sort = "titleSort:asc"; NavMemory.libSort[section.key] = sort }
            Chip("Recently Added", sort == "addedAt:desc") { sort = "addedAt:desc"; NavMemory.libSort[section.key] = sort }
        }
        val list = items
        if (list == null) {
            Centered(err ?: "Loading…")
        } else {
            // The whole library is loaded in one go, so letter positions come straight from the list.
            val showAz = sort == "titleSort:asc" && list.isNotEmpty()
            val firstIndex = remember(list) {
                val m = HashMap<Char, Int>()
                list.forEachIndexed { i, it -> m.putIfAbsent(azLetter(it), i) }
                m
            }
            val current = if (showAz) list.getOrNull(target)?.let { azLetter(it) } else null
            val libDim = remember(section.key) { DimGroup() }
            Row(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    modifier = Modifier.weight(1f),
                    columns = GridCells.Adaptive(tv.gridCell),
                    // with the index, 28dp of the right padding moves into the letter column, so posters keep their size.
                    // The grid ends at the bottom safe margin (MainScreen); focusing a poster also scrolls its title into view.
                    contentPadding = PaddingValues(start = tv.side, end = if (showAz) maxOf(tv.side - 28.dp, 8.dp) else tv.side, top = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    gridItemsIndexed(list) { i, item ->
                        val dim = libDim
                        Box(if (i == target) Modifier.focusRequester(targetFr) else Modifier) {
                            PosterCard(
                                api, item, false,
                                onFocus = {
                                    target = i
                                    NavMemory.libTarget[memKey] = i
                                    NavMemory.libSort[section.key] = sort
                                    NavMemory.restoreTo = area
                                },
                                onLongPress = { target = i; openMenu(item) },
                                dim = dim,
                            ) { onOpen(item) }
                        }
                    }
                }
                if (showAz) {
                    AzIndex(firstIndex, current, targetFr) { idx ->
                        target = idx
                        scope.launch { gridState.scrollToItem(idx) }
                    }
                }
            }
        }
    }
    HoldMenuHost(menu)
    }
}

/** "#", A–Z down the right edge. Focusing (or OK on) a letter jumps the grid; letters with no titles are skipped. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AzIndex(firstIndex: Map<Char, Int>, current: Char?, back: FocusRequester, onJump: (Int) -> Unit) {
    val accent = LocalAccent.current
    val frs = remember { List(AZ.length) { FocusRequester() } }
    val curPos = if (current != null && firstIndex.containsKey(current)) AZ.indexOf(current) else -1
    Column(
        Modifier
            .width(28.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            // coming in from the grid lands on the current letter, not whichever letter is nearest
            .focusProperties { enter = { if (curPos >= 0) frs[curPos] else FocusRequester.Default } }
            .focusGroup(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AZ.forEachIndexed { i, ch ->
            val idx = firstIndex[ch]
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .weight(1f, fill = false)
                    .height(16.dp)
                    .fillMaxWidth()
                    .zIndex(if (focused) 1f else 0f)
                    .focusRequester(frs[i])
                    .focusProperties {
                        canFocus = idx != null
                        left = back
                    }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused && idx != null) onJump(idx)
                    }
                    .clickable(enabled = idx != null) { if (idx != null) onJump(idx) },
                contentAlignment = Alignment.Center,
            ) {
                // 27 letters must fit the column, so unfocused letters stay small; the focused one is drawn 20sp in a solid
                // accent circle that overflows its slot (layout unchanged)
                if (focused) {
                    Box(
                        Modifier.wrapContentSize(unbounded = true).zIndex(1f).size(34.dp)
                            .background(accent, CircleShape).border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(ch.toString(), fontSize = 20.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF101010))
                    }
                } else {
                    Text(
                        ch.toString(),
                        fontSize = 14.sp,
                        lineHeight = 14.sp,
                        fontWeight = if (ch == current) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            ch == current -> accent
                            idx != null -> Pal.Dim
                            else -> Pal.Dim.copy(alpha = 0.3f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    api: PlexApi,
    prefs: Prefs,
    ov: OverseerrApi,
    onPreviewIntro: () -> Unit,
    onSync: () -> Unit,
    onSwitch: () -> Unit,
    onSignOut: () -> Unit,
) {
    var subs by remember { mutableStateOf(prefs.subsDefault) }
    var introStyle by remember { mutableIntStateOf(prefs.introStyle) }
    var celsius by remember { mutableStateOf(prefs.celsius) }
    var daily by remember { mutableStateOf(prefs.introDaily) }
    var rewind by remember { mutableIntStateOf(prefs.resumeRewind) }
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = tvDims().side, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Profile: ${prefs.userName ?: "—"}", fontSize = 18.sp)
        Text("Server: ${prefs.serverName ?: "—"}  (${api.base})", fontSize = 14.sp, color = Pal.Dim)
        Chip("Subtitles on by default: ${if (subs) "On" else "Off"}") {
            subs = !subs
            prefs.subsDefault = subs
        }
        Chip("Resume rewind: ${rewindLabel(rewind)}") {
            rewind = RESUME_REWIND_CHOICES[(RESUME_REWIND_CHOICES.indexOf(rewind) + 1) % RESUME_REWIND_CHOICES.size]
            prefs.resumeRewind = rewind
        }
        Chip("Temperature: ${if (celsius) "°C" else "°F"}") {
            celsius = !celsius
            prefs.celsius = celsius
        }
        val musicOk = hasMusicAccess(ctx)
        Chip(if (musicOk) "Spotify now playing access: allowed" else "Spotify now playing access: NOT allowed — open settings") {
            openAccessSettings(ctx)
        }
        Text(
            if (musicOk) "Starsky can show and control what Spotify plays (Spotify button on the top bar)."
            else "Turn it on: Settings > Apps > Special app access > Notification access > Starsky TV. If the TV has no such screen, from Tower run: adb shell cmd notification allow_listener com.cstark.starktv/com.cstark.starktv.MusicListener",
            fontSize = 14.sp,
            color = Pal.Dim,
        )
        Chip("Opening: ${if (introStyle == INTRO_OFF) "Off" else INTRO_NAMES[introStyle]}") {
            introStyle = (introStyle + 1) % (INTRO_OFF + 1)
            prefs.introStyle = introStyle
        }
        if (introStyle != INTRO_OFF) {
            Chip("Opening plays: ${if (daily) "First launch each day" else "Every launch"}") {
                daily = !daily
                prefs.introDaily = daily
            }
            Chip("Play opening", onClick = onPreviewIntro)
        }
        Chip("Sync from Plex now", onClick = onSync)
        val synced = prefs.lastSync
        Text(
            "Last synced: " + if (synced > 0) SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(synced)) else "never",
            fontSize = 14.sp,
            color = Pal.Dim,
        )
        Text(
            "Auto-syncs when the app opens, whenever you come back to this screen from a title or the player, and every 90 seconds while it's open.",
            fontSize = 14.sp,
            color = Pal.Dim,
        )
        val scope = rememberCoroutineScope()
        if (Updater.newBuild > 0) {
            Chip("Install update (build ${Updater.newBuild})", selected = true) { scope.launch { Updater.install(ctx) } }
        } else {
            Chip("Check for updates") { scope.launch { Updater.check(ctx) } }
        }
        if (Updater.status.isNotBlank()) Text(Updater.status, fontSize = 14.sp, color = Pal.Dim)
        Text("Last update check: ${prefs.lastUpdateCheck ?: "never"}", fontSize = 14.sp, color = Pal.Dim)
        Text("Current build: ${Updater.current(ctx)}", fontSize = 14.sp, color = Pal.Dim)
        Text("Last playback error: ${prefs.lastPlayError ?: "None"}", fontSize = 14.sp, color = Pal.Dim)
        var crash by remember { mutableStateOf(prefs.lastCrash) }
        Text("Last crash: ${crash?.lineSequence()?.firstOrNull() ?: "None"}", fontSize = 14.sp, color = Pal.Dim)
        if (crash != null) Chip("Clear crash log") { prefs.lastCrash = null; crash = null }
        // Watch history backup (Tower watch ledger, status.json on the LAN): short timeout, loaded in the background
        var backup by remember { mutableStateOf("Watch history backup: checking…") }
        LaunchedEffect(Unit) { backup = watchBackupLine() }
        Text(backup, fontSize = 14.sp, color = Pal.Dim)
        var forgot by remember { mutableStateOf<String?>(null) }
        Chip("Forget track choices") {
            val n = prefs.forgetTracks()
            forgot = if (n == 0) "No saved audio/subtitle choices." else "Forgot $n saved audio/subtitle choice${if (n == 1) "" else "s"}."
        }
        Text(
            forgot ?: "Audio and subtitle picks are remembered per show (per profile) and used on its next episodes.",
            fontSize = 14.sp,
            color = Pal.Dim,
        )
        LaunchedEffect(Unit) { ov.ensure() }
        Text("Requests (Overseerr): ${ov.status}", fontSize = 14.sp, color = Pal.Dim)
        Text(
            if (Tmdb.key(ctx).isNotBlank()) "YouTube trailers (TMDB): on" else "YouTube trailers (TMDB): off — add a key to tmdb_key.txt",
            fontSize = 14.sp,
            color = Pal.Dim,
        )
        // Back from the profile picker opened here: focus returns to this button
        val switchFr = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (NavMemory.take("settings:switch")) focusSoon(switchFr) }
        Chip("Switch profile", fr = switchFr) { NavMemory.restoreTo = "settings:switch"; onSwitch() }
        Chip("Sign out", onClick = onSignOut)
    }
}

// ---------------------------------------------------------------- details

fun nextUp(eps: List<Item>): Item? =
    eps.firstOrNull { it.resumable } ?: eps.firstOrNull { it.viewCount == 0 }

@Composable
fun DetailScreen(
    api: PlexApi,
    key: String,
    refresh: Int,
    onPlay: (Item, Boolean) -> Unit,
    onTrailer: (TrailerReq) -> Unit,
    onOpen: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Resume rewind (Settings), so the Resume label shows where playback will really start
    val rewindS = remember { Prefs(ctx).resumeRewind }
    var reload by remember { mutableIntStateOf(0) }
    var wlKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var data by remember { mutableStateOf<DetailData?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val playFocus = remember { FocusRequester() }
    // hold OK on the Play button, a season or an episode: options menu (same HoldMenu / HOLD_OK_MS as Home)
    val menu = rememberHoldMenu()

    LaunchedEffect(key, refresh, reload) {
        try {
            val d = api.detail(key)
            data = d
            if (season == Int.MIN_VALUE) {
                season = nextUp(d.episodes)?.parentIndex ?: d.episodes.firstOrNull()?.parentIndex ?: 0
            }
        } catch (e: Exception) {
            err = e.message
        }
    }

    val d = data
    if (d == null) { Centered(err ?: "Loading…"); return }
    val detailList = rememberLazyListState()
    LaunchedEffect(d.item.ratingKey) {
        runCatching { playFocus.requestFocus() }
        // focusing Play scrolls the page a little (TV focus pivot); open at the top so the title is fully visible
        delay(120)
        detailList.scrollToItem(0)
    }
    LaunchedEffect(d.item.ratingKey) {
        wlKeys = api.watchlist().map { it.ratingKey }.toSet()
    }
    // More like this (Plex related hubs), movies and shows only; the row is left out when Plex has nothing
    var related by remember { mutableStateOf<List<Item>>(emptyList()) }
    LaunchedEffect(d.item.ratingKey) {
        if (d.item.type == "movie" || d.item.type == "show") related = api.related(d.item.ratingKey)
    }
    /**
     * Hand mark: the page shows it at once (episodes in [covers] and the title itself), then Plex gets it through the mark
     * queue (Watched.kt: retried until it works, ledger told), a short message confirms, and the page reloads from Plex.
     * Unwatched also clears the resume point (Plex's unscrobble does that).
     */
    fun mark(ratingKey: String, watched: Boolean, what: String, covers: (Item) -> Boolean) {
        fun upd(x: Item) = x.copy(viewCount = if (watched) maxOf(1, x.viewCount) else 0, viewOffset = 0L)
        data = data?.let { dd ->
            dd.copy(
                item = if (dd.item.ratingKey == ratingKey) upd(dd.item) else dd.item,
                episodes = dd.episodes.map { if (covers(it)) upd(it) else it },
            )
        }
        scope.launch {
            val ok = api.setWatched(ratingKey, watched, what)
            markToast(ctx, ok, what, watched)
            reload++
        }
    }
    /** Season or show: asks first ("Mark all 10 episodes of Season 2 as watched?" No / Yes, focus on No). */
    fun confirmMark(ratingKey: String, watched: Boolean, what: String, count: Int, fr: FocusRequester?, covers: (Item) -> Boolean) {
        val n = if (count > 0) "all ${plural(count, "episode")} of" else "every episode of"
        menu.confirm("Mark $n $what as ${if (watched) "watched" else "unwatched"}?", fr) { mark(ratingKey, watched, what, covers) }
    }

    val top = d.item
    val isShow = top.type == "show"
    val target: Item? = if (isShow) (nextUp(d.episodes) ?: d.episodes.firstOrNull()) else top
    val resume = target != null && target.resumable
    val seasons = d.episodes.map { it.parentIndex }.distinct().sorted()
    val dkey = top.guid?.takeIf { it.startsWith("plex://") }?.substringAfterLast("/")
    fun toggleWatchlist() {
        if (dkey == null) return
        val on = dkey in wlKeys
        scope.launch { if (api.setWatchlist(dkey, !on)) wlKeys = if (on) wlKeys - dkey else wlKeys + dkey }
    }
    // Play button: play / start over / watched state of what it plays and of the whole title / Watchlist
    fun titleMenu() {
        val t = target ?: return
        val code = if (isShow) " ${t.epCode}" else ""
        val opts = buildList<Pair<String, () -> Unit>> {
            add((if (resume) "Resume$code" else "Play$code") to { onPlay(t, true) })
            if (resume) add("Start over" to { onPlay(t, false) })
            if (isShow) {
                add((if (t.watchedDone) "Mark${code} unwatched" else "Mark${code} watched") to {
                    mark(t.ratingKey, !t.watchedDone, "${top.title}$code") { it.ratingKey == t.ratingKey }
                })
                val n = d.episodes.size
                if (!d.episodes.all { it.watchedDone }) add("Mark show watched" to { confirmMark(top.ratingKey, true, top.title, n, playFocus) { true } })
                if (d.episodes.any { it.viewCount > 0 || it.viewOffset > 0 }) add("Mark show unwatched" to { confirmMark(top.ratingKey, false, top.title, n, playFocus) { true } })
            } else {
                add((if (top.watchedDone) "Mark unwatched" else "Mark watched") to { mark(top.ratingKey, !top.watchedDone, top.title) { false } })
            }
            if (dkey != null) add((if (dkey in wlKeys) "Remove from Watchlist" else "Add to Watchlist") to { toggleWatchlist() })
        }
        menu.open(top.title, opts, playFocus)
    }
    fun seasonMenu(sn: Int, fr: FocusRequester) {
        val inSeason = d.episodes.filter { it.parentIndex == sn }
        val sk = inSeason.firstOrNull()?.parentRatingKey ?: return
        val name = if (sn == 0) "Specials" else "Season $sn"
        val opts = buildList<Pair<String, () -> Unit>> {
            nextUp(inSeason)?.let { e -> add((if (e.resumable) "Resume ${e.epCode}" else "Play ${e.epCode}") to { onPlay(e, true) }) }
            val what = "${top.title} $name"
            if (!inSeason.all { it.watchedDone }) add("Mark season watched" to { confirmMark(sk, true, what, inSeason.size, fr) { it.parentIndex == sn } })
            if (inSeason.any { it.viewCount > 0 || it.viewOffset > 0 }) add("Mark season unwatched" to { confirmMark(sk, false, what, inSeason.size, fr) { it.parentIndex == sn } })
        }
        menu.open("${top.title} · $name", opts, fr)
    }
    fun episodeMenu(ep: Item, fr: FocusRequester) {
        val opts = buildList<Pair<String, () -> Unit>> {
            add((if (ep.resumable) "Resume" else "Play") to { onPlay(ep, true) })
            if (ep.resumable) add("Start over" to { onPlay(ep, false) })
            add((if (ep.watchedDone) "Mark unwatched" else "Mark watched") to {
                mark(ep.ratingKey, !ep.watchedDone, "${top.title} ${ep.epCode}") { it.ratingKey == ep.ratingKey }
            })
            if (dkey != null) add((if (dkey in wlKeys) "Remove show from Watchlist" else "Add show to Watchlist") to { toggleWatchlist() })
        }
        menu.open("${ep.epCode} · ${ep.title}", opts, fr)
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = api.img(top.art, 1280, 720),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.22f),
        )
        val tv = tvDims()
        LazyColumn(
            state = detailList,
            // the list ends at the bottom safe margin, so a focused episode row is never under the TV edge
            modifier = Modifier.padding(bottom = tv.bottom),
            contentPadding = PaddingValues(start = tv.side, end = tv.side, top = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    AsyncImage(
                        model = api.img(top.thumb, 440, 660),
                        contentDescription = top.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Pal.Card),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(top.title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                        val meta = listOfNotNull(
                            top.year.takeIf { it > 0 }?.toString(),
                            top.contentRating,
                            top.duration.takeIf { it > 0 }?.let { fmtRuntime(it) },
                            if (isShow && seasons.isNotEmpty()) plural(seasons.count { it > 0 }, "season") else null,
                        ).joinToString("  ·  ")
                        if (meta.isNotEmpty()) Text(meta, fontSize = 16.sp, color = Pal.Dim)
                        Text(top.summary, fontSize = 16.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (target != null) {
                                val code = if (isShow) " ${target.epCode}" else ""
                                Chip(
                                    if (resume) "▶ Resume$code  ${fmtTime(resumeStartMs(target.viewOffset, rewindS))}" else "▶ Play$code",
                                    fr = playFocus,
                                    onLongPress = { titleMenu() },
                                ) { onPlay(target, true) }
                                if (resume) Chip("Start over") { onPlay(target, false) }
                            }
                            // Trailer: always in the app's own player (Plex extras -> Plex trailer -> YouTube via TMDB)
                            if (d.trailers.isNotEmpty() || dkey != null || d.tmdbId != null || d.imdbId != null) {
                                Chip("Trailer") {
                                    onTrailer(TrailerReq(top.title, top.year, top.type, d.trailers.map { it.partKey }, dkey, d.tmdbId, d.imdbId))
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (isShow) {
                                val allWatched = d.episodes.isNotEmpty() && d.episodes.all { it.watchedDone }
                                val showFr = remember { FocusRequester() }
                                Chip(if (allWatched) "Mark show unwatched" else "Mark show watched", fr = showFr) {
                                    confirmMark(top.ratingKey, !allWatched, top.title, d.episodes.size, showFr) { true }
                                }
                            } else {
                                val seen = top.watchedDone
                                Chip(if (seen) "Mark unwatched" else "Mark watched") { mark(top.ratingKey, !seen, top.title) { false } }
                            }
                            if (dkey != null) {
                                Chip(if (dkey in wlKeys) "Remove from Watchlist" else "Add to Watchlist") { toggleWatchlist() }
                            }
                        }
                    }
                }
            }
            if (isShow && seasons.isNotEmpty()) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        items(seasons) { sn ->
                            val fr = remember { FocusRequester() }
                            Chip(if (sn == 0) "Specials" else "Season $sn", season == sn, fr = fr, onLongPress = { seasonMenu(sn, fr) }) { season = sn }
                        }
                        item {
                            val inSeason = d.episodes.filter { it.parentIndex == season }
                            val seasonKey = inSeason.firstOrNull()?.parentRatingKey
                            val seasonSeen = inSeason.isNotEmpty() && inSeason.all { it.watchedDone }
                            if (seasonKey != null) {
                                val sfr = remember { FocusRequester() }
                                val sname = if (season == 0) "Specials" else "Season $season"
                                val sn = season
                                Chip(if (seasonSeen) "Mark season unwatched" else "Mark season watched", fr = sfr) {
                                    confirmMark(seasonKey, !seasonSeen, "${top.title} $sname", inSeason.size, sfr) { it.parentIndex == sn }
                                }
                            }
                        }
                    }
                }
                items(d.episodes.filter { it.parentIndex == season }, key = { it.ratingKey }) { ep ->
                    val fr = remember { FocusRequester() }
                    EpisodeRow(api, ep, fr, onClick = { onPlay(ep, true) }, onLongPress = { episodeMenu(ep, fr) }, onToggle = {
                        mark(ep.ratingKey, !ep.watchedDone, "${top.title} ${ep.epCode}") { it.ratingKey == ep.ratingKey }
                    })
                }
            }
            if (related.isNotEmpty()) {
                item(key = "related") {
                    Column(Modifier.padding(top = 24.dp)) {
                        Text("More like this", fontSize = 18.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 10.dp))
                        val dim = remember { DimGroup() }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            items(related, key = { it.ratingKey }) { r ->
                                PosterCard(api, r, false, posterH = tv.posterH * 0.8f, dim = dim) { onOpen(r.ratingKey) }
                            }
                        }
                    }
                }
            }
        }
        HoldMenuHost(menu)
    }
}

@Composable
fun EpisodeRow(
    api: PlexApi,
    ep: Item,
    fr: FocusRequester? = null,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Focusable(Modifier.weight(1f), fr = fr, scaleOnFocus = 1.02f, onClick = onClick, onLongPress = onLongPress) { f ->
            Row(
                Modifier.fillMaxWidth().background(if (f) LocalAccent.current.copy(alpha = 0.28f).compositeOver(Color(0xFF16161B)) else Color(0xFF16161B)).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(200.dp).height(112.dp)) {
                    AsyncImage(
                        model = api.img(ep.thumb, 400, 224),
                        contentDescription = ep.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Pal.Card),
                    )
                    if (ep.shownProgress > 0f) Progress(ep.shownProgress, Modifier.align(Alignment.BottomCenter))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${ep.index}. ${ep.title}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ep.summary, fontSize = 14.sp, color = Pal.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // In progress (also a rewatch of a watched episode): "Resume", which plays from the resume point like the row.
        // Watched only with no active resume point. Mark watched/unwatched stays in the hold-OK menu.
        if (ep.resumable) Chip("Resume ▶", onClick = onClick)
        else Chip(if (ep.watchedDone) "Watched ✓" else "Mark watched", ep.watchedDone, onClick = onToggle)
    }
}

/** Tower's watch-ledger status (numbers only), served read-only on the LAN. */
const val WATCH_STATUS_URL = "http://192.168.1.8:8479/status.json"

/** "Watch history backup: last export <time>: N items protected, M kept for deleted titles", or that it can't be reached. Never throws. */
suspend fun watchBackupLine(): String = withContext(Dispatchers.IO) {
    try {
        val c = java.net.URL(WATCH_STATUS_URL).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val txt = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val j = org.json.JSONObject(txt)
        val t = j.optLong("ledger_last_ok", 0L)
        val time = if (t > 0) SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(t * 1000)) else "never"
        "Watch history backup: Last export $time: ${plural(j.optInt("items_protected"), "item")} protected, " +
            "${j.optInt("kept_not_in_library")} kept for deleted titles"
    } catch (e: Throwable) {
        "Watch history backup: Can't reach backup status"
    }
}
