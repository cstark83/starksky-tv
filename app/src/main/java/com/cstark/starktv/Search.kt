package com.cstark.starktv

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.border
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val HINT = "Type or speak to search your libraries"

/** Search state kept while a title opened from the results is on screen, so Back returns to the same results. */
private object SearchMem {
    var query = ""
    var searched = ""
    var results: List<Item> = emptyList()
    var requestable: List<Media> = emptyList()
    var status = HINT
    var grid = LazyGridState()
    var focusKey: String? = null

    fun clear() {
        query = ""; searched = ""; results = emptyList(); requestable = emptyList(); status = HINT
        grid = LazyGridState(); focusKey = null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(api: PlexApi, ov: OverseerrApi, onRequest: (Media) -> Unit, onOpen: (Item) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val accent = LocalAccent.current
    // Back from a title opened here: same query, results and scroll, focus on the result you opened
    val returning = remember { NavMemory.take("search").also { if (!it) SearchMem.clear() } }
    val restoreKey = remember { if (returning) SearchMem.focusKey else null }
    var query by remember { mutableStateOf(SearchMem.query) }
    var results by remember { mutableStateOf(SearchMem.results) }
    var status by remember { mutableStateOf(SearchMem.status) }
    var requestable by remember { mutableStateOf(SearchMem.requestable) }
    val field = remember { FocusRequester() }
    val firstFr = remember { FocusRequester() }
    val restoreFr = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    // keyboard closed before the results came in: move to the first result when they arrive
    var wantResults by remember { mutableStateOf(false) }
    val hasResults = results.isNotEmpty() || (requestable.isNotEmpty() && !ov.restricted)

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) { query = said; wantResults = true }
    }

    LaunchedEffect(query) {
        SearchMem.query = query
        val q = query.trim()
        if (q == SearchMem.searched && returning) return@LaunchedEffect // results already on screen
        if (q.length < 2) {
            results = emptyList()
            requestable = emptyList()
            status = HINT
            SearchMem.results = results; SearchMem.requestable = requestable; SearchMem.status = status; SearchMem.searched = ""
            return@LaunchedEffect
        }
        delay(350)
        status = "Searching…"
        try {
            results = api.search(q)
            requestable = if (ov.ensure()) ov.search(q).filter { it.status < 5 }.take(12) else emptyList()
            status = if (results.isEmpty() && requestable.isEmpty()) "No results for \"$q\"" else ""
            SearchMem.searched = q
        } catch (e: Exception) {
            status = "Search failed: ${e.message}"
        }
        SearchMem.results = results; SearchMem.requestable = requestable; SearchMem.status = status
    }
    LaunchedEffect(Unit) {
        if (returning && restoreKey != null && focusSoon(restoreFr)) return@LaunchedEffect
        if (returning && hasResults && focusSoon(firstFr)) return@LaunchedEffect
        runCatching { field.requestFocus() }
    }
    // On-screen keyboard closed (Back, or its search key): focus the first result, or stay on the box if there are none.
    LaunchedEffect(Unit) {
        var shown = false
        while (true) {
            val now = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (shown && !now) {
                if (results.isNotEmpty() || (requestable.isNotEmpty() && !ov.restricted)) {
                    if (!focusSoon(firstFr)) runCatching { field.requestFocus() }
                } else {
                    wantResults = true
                    runCatching { field.requestFocus() }
                }
            }
            shown = now
            delay(200)
        }
    }
    LaunchedEffect(results, requestable) {
        if (wantResults && fieldFocused && (results.isNotEmpty() || (requestable.isNotEmpty() && !ov.restricted))) {
            wantResults = false
            focusSoon(firstFr)
        }
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        val tv = tvDims()
        // bottom: the safe margin, so the grid (and a focused poster's title) ends above the TV edge
        Column(Modifier.fillMaxSize().padding(start = tv.side, end = tv.side, top = 24.dp, bottom = tv.bottom), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { kb?.hide() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(field)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        // Down from the box (keyboard closed) goes into the results
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && hasResults) {
                                runCatching { firstFr.requestFocus() }
                                true
                            } else {
                                false
                            }
                        }
                        .onKeyEvent {
                            if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionCenter || it.key == Key.Enter)) {
                                kb?.show()
                                true
                            } else {
                                false
                            }
                        },
                    decorationBox = { inner ->
                        Box(
                            Modifier
                                .background(if (fieldFocused) accent.copy(alpha = 0.22f).compositeOver(Color(0xFF1A1A20)) else Color(0x22FFFFFF), RoundedCornerShape(10.dp))
                                .border(if (fieldFocused) 4.dp else 0.dp, if (fieldFocused) accent else Color.Transparent, RoundedCornerShape(10.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            if (query.isEmpty()) Text("Search movies and shows", color = Pal.Dim, fontSize = 24.sp)
                            inner()
                        }
                    },
                )
                Chip("Speak") {
                    try {
                        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Starsky TV")
                        speech.launch(i)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Voice search isn't available on this device", Toast.LENGTH_LONG).show()
                    }
                }
                Chip("Close", onClick = onClose)
            }
            if (status.isNotEmpty()) Text(status, fontSize = 18.sp, color = Pal.Dim)
            LazyVerticalGrid(
                state = SearchMem.grid,
                columns = GridCells.Adaptive(tv.gridCell),
                contentPadding = PaddingValues(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(results, key = { _, it -> "p" + it.ratingKey }) { i, item ->
                    val k = "p" + item.ratingKey
                    PosterCard(
                        api, item, false,
                        fr = if (k == restoreKey) restoreFr else if (i == 0) firstFr else null,
                        onFocus = { SearchMem.focusKey = k },
                    ) { onOpen(item) }
                }
                if (requestable.isNotEmpty() && !ov.restricted) { // kid profiles: no requests
                    item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) {
                        Text("Not in your library — request it", fontSize = 18.sp, color = Pal.Dim, modifier = Modifier.padding(top = 8.dp))
                    }
                    itemsIndexed(requestable, key = { _, it -> "m" + it.type + it.id }) { i, m ->
                        val k = "m" + m.type + m.id
                        Box(Modifier.onFocusChanged { if (it.hasFocus) SearchMem.focusKey = k }) {
                            MediaCard(m, if (k == restoreKey) restoreFr else if (i == 0 && results.isEmpty()) firstFr else null) { onRequest(m) }
                        }
                    }
                }
            }
        }
    }
}
