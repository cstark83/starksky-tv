package com.cstark.starktv

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
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
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 48.dp),
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
    }
}

suspend fun loadHome(api: PlexApi, d: HomeData) {
    try {
        val secs = api.sections()
        d.sections = secs
        val rows = mutableListOf<HomeRow>()

        // Plex's "on deck" mixes things you're mid-way through with the next unwatched episodes.
        // Split them, keep one entry per show, and cap both rows so they stay short.
        val deck = api.onDeck().distinctBy { it.ratingKey }
        val cont = deck
            .filter { it.viewOffset > 0 && it.progress < 0.95f }
            .distinctBy { it.showKey }
            .take(10)
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
        for (s in secs) {
            val items = api.recent(s).distinctBy { it.ratingKey }
            if (items.isEmpty()) continue
            val title = when {
                s.type == "show" && !tvDone -> { tvDone = true; "New TV" }
                s.type == "movie" && !movDone -> { movDone = true; "New Movies" }
                else -> "New in ${s.title}"
            }
            rows += HomeRow(title, items, false, false)
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
    } catch (e: Exception) {
        if (d.rows == null) d.error = e.message ?: "Couldn't load your library"
    }
}

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
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Updater.check(ctx) }
    LaunchedEffect(refresh) {
        loadHome(api, home)
        // keep in step with Plex (other devices, other apps) while this screen is open
        while (true) {
            delay(90_000)
            loadHome(api, home)
        }
    }
    LaunchedEffect(home.lastSync) { if (home.lastSync > 0) prefs.lastSync = home.lastSync }
    BackHandler(enabled = home.tab != 0) { home.tab = 0 }
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
                TopBar(prefs, home, onSearch, onSwitchProfile) { openSpotify(ctx) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val t = home.tab
                    when {
                        t == 0 -> HomeScreen(api, home, ov, onDetail, onPlay, onNowPlaying, onRequest)
                        t <= home.sections.size -> LibraryScreen(api, home.sections[t - 1], refresh) { onDetail(it.showKey) }
                        t == home.discoverTab -> DiscoverScreen(home, ov, onRequest)
                        else -> SettingsScreen(api, prefs, ov, onPreviewIntro, onSync, onSwitchProfile, onSignOut)
                    }
                }
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
fun LibraryScreen(api: PlexApi, section: Section, refresh: Int, onOpen: (Item) -> Unit) {
    var sort by rememberSaveable(section.key) { mutableStateOf("titleSort:asc") }
    var items by remember(section.key, sort) { mutableStateOf<List<Item>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val gridState = remember(section.key, sort) { LazyGridState() }
    // The grid item focus returns to when you press Left from the A–Z column (last focused, or last jumped to).
    var target by remember(section.key, sort) { mutableIntStateOf(0) }
    val targetFr = remember { FocusRequester() }

    LaunchedEffect(section.key, sort, refresh) {
        try { items = api.library(section, sort); err = null } catch (e: Exception) { err = e.message }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 48.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Chip("A–Z", sort == "titleSort:asc") { sort = "titleSort:asc" }
            Chip("Recently Added", sort == "addedAt:desc") { sort = "addedAt:desc" }
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
            Row(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    modifier = Modifier.weight(1f),
                    columns = GridCells.Adaptive(166.dp),
                    // with the index, 28dp of the right padding moves into the letter column, so posters keep their size
                    contentPadding = PaddingValues(start = 48.dp, end = if (showAz) 20.dp else 48.dp, top = 20.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    gridItemsIndexed(list) { i, item ->
                        Box(if (i == target) Modifier.focusRequester(targetFr) else Modifier) {
                            PosterCard(api, item, false, onFocus = { target = i }) { onOpen(item) }
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
                Text(
                    ch.toString(),
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = if (focused || ch == current) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        focused -> Color.Black
                        ch == current -> accent
                        idx != null -> Pal.Dim
                        else -> Pal.Dim.copy(alpha = 0.3f)
                    },
                    modifier = Modifier
                        .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
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
    val ctx = LocalContext.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Profile: ${prefs.userName ?: "—"}", fontSize = 18.sp)
        Text("Server: ${prefs.serverName ?: "—"}  (${api.base})", fontSize = 14.sp, color = Pal.Dim)
        Chip("Subtitles on by default: ${if (subs) "On" else "Off"}") {
            subs = !subs
            prefs.subsDefault = subs
        }
        Chip("Temperature: ${if (celsius) "°C" else "°F"}") {
            celsius = !celsius
            prefs.celsius = celsius
        }
        Chip(if (hasMusicAccess(ctx)) "Now playing (Spotify): connected" else "Allow now playing (Spotify)") {
            openAccessSettings(ctx)
        }
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
        Text("Last playback error: ${prefs.lastPlayError ?: "None"}", fontSize = 14.sp, color = Pal.Dim)
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
            if (Tmdb.key(ctx).isNotBlank()) "Trailer backup (TMDB): on" else "Trailer backup (TMDB): off — add a key to tmdb_key.txt",
            fontSize = 14.sp,
            color = Pal.Dim,
        )
        Chip("Switch profile", onClick = onSwitch)
        Chip("Sign out", onClick = onSignOut)
    }
}

// ---------------------------------------------------------------- details

fun nextUp(eps: List<Item>): Item? =
    eps.firstOrNull { it.viewOffset > 0 } ?: eps.firstOrNull { it.viewCount == 0 }

@Composable
fun DetailScreen(
    api: PlexApi,
    key: String,
    refresh: Int,
    onPlay: (Item, Boolean) -> Unit,
    onTrailer: (Trailer) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var backupTrailer by remember { mutableStateOf<String?>(null) }
    var wlKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var data by remember { mutableStateOf<DetailData?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val playFocus = remember { FocusRequester() }

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
    LaunchedEffect(d.item.ratingKey) { runCatching { playFocus.requestFocus() } }
    LaunchedEffect(d.item.ratingKey) {
        backupTrailer = if (d.trailers.isEmpty()) Tmdb.trailerYoutubeId(ctx, d) else null
        wlKeys = api.watchlist().map { it.ratingKey }.toSet()
    }
    fun mark(ratingKey: String, watched: Boolean) {
        scope.launch {
            api.setWatched(ratingKey, watched)
            reload++
        }
    }

    val top = d.item
    val isShow = top.type == "show"
    val target: Item? = if (isShow) (nextUp(d.episodes) ?: d.episodes.firstOrNull()) else top
    val resume = target != null && target.viewOffset > 0
    val seasons = d.episodes.map { it.parentIndex }.distinct().sorted()

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = api.img(top.art, 1280, 720),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.22f),
        )
        LazyColumn(
            contentPadding = PaddingValues(48.dp),
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
                            if (isShow && seasons.isNotEmpty()) "${seasons.count { it > 0 }} seasons" else null,
                        ).joinToString("  ·  ")
                        if (meta.isNotEmpty()) Text(meta, fontSize = 16.sp, color = Pal.Dim)
                        Text(top.summary, fontSize = 16.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (target != null) {
                                val code = if (isShow) " ${target.epCode}" else ""
                                Chip(
                                    if (resume) "▶ Resume$code  ${fmtTime(target.viewOffset)}" else "▶ Play$code",
                                    fr = playFocus,
                                ) { onPlay(target, true) }
                                if (resume) Chip("Start over") { onPlay(target, false) }
                            }
                            if (d.trailers.isNotEmpty()) {
                                Chip("Trailer") { onTrailer(d.trailers.first()) }
                            } else if (backupTrailer != null) {
                                Chip("Trailer (YouTube)") { openYoutube(ctx, backupTrailer!!) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (isShow) {
                                val allWatched = d.episodes.isNotEmpty() && d.episodes.all { it.viewCount > 0 }
                                Chip(if (allWatched) "Mark show unwatched" else "Mark show watched") { mark(top.ratingKey, !allWatched) }
                            } else {
                                val seen = top.viewCount > 0
                                Chip(if (seen) "Mark unwatched" else "Mark watched") { mark(top.ratingKey, !seen) }
                            }
                            val dkey = top.guid?.takeIf { it.startsWith("plex://") }?.substringAfterLast("/")
                            if (dkey != null) {
                                val on = dkey in wlKeys
                                Chip(if (on) "Remove from Watchlist" else "Add to Watchlist") {
                                    scope.launch {
                                        if (api.setWatchlist(dkey, !on)) wlKeys = if (on) wlKeys - dkey else wlKeys + dkey
                                    }
                                }
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
                            Chip(if (sn == 0) "Specials" else "Season $sn", season == sn) { season = sn }
                        }
                        item {
                            val inSeason = d.episodes.filter { it.parentIndex == season }
                            val seasonKey = inSeason.firstOrNull()?.parentRatingKey
                            val seasonSeen = inSeason.isNotEmpty() && inSeason.all { it.viewCount > 0 }
                            if (seasonKey != null) {
                                Chip(if (seasonSeen) "Mark season unwatched" else "Mark season watched") { mark(seasonKey, !seasonSeen) }
                            }
                        }
                    }
                }
                items(d.episodes.filter { it.parentIndex == season }, key = { it.ratingKey }) { ep ->
                    EpisodeRow(api, ep, onClick = { onPlay(ep, true) }, onToggle = { mark(ep.ratingKey, ep.viewCount == 0) })
                }
            }
        }
    }
}

@Composable
fun EpisodeRow(api: PlexApi, ep: Item, onClick: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Focusable(Modifier.weight(1f), scaleOnFocus = 1.02f, onClick = onClick) { f ->
            Row(
                Modifier.fillMaxWidth().background(if (f) Color(0xFF2A2A33) else Color(0xFF16161B)).padding(8.dp),
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
                    if (ep.progress > 0f) Progress(ep.progress, Modifier.align(Alignment.BottomCenter))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${ep.index}. ${ep.title}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ep.summary, fontSize = 14.sp, color = Pal.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Chip(if (ep.viewCount > 0) "Watched ✓" else "Mark watched", ep.viewCount > 0, onClick = onToggle)
    }
}
