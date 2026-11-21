package com.cstark.starktv

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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(api: PlexApi, ov: OverseerrApi, onRequest: (Media) -> Unit, onOpen: (Item) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var status by remember { mutableStateOf(HINT) }
    var requestable by remember { mutableStateOf<List<Media>>(emptyList()) }
    val field = remember { FocusRequester() }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) query = said
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            requestable = emptyList()
            status = HINT
            return@LaunchedEffect
        }
        delay(350)
        status = "Searching…"
        try {
            results = api.search(q)
            requestable = if (ov.ensure()) ov.search(q).filter { it.status < 5 }.take(12) else emptyList()
            status = if (results.isEmpty() && requestable.isEmpty()) "No results for \"$q\"" else ""
        } catch (e: Exception) {
            status = "Search failed: ${e.message}"
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
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
                columns = GridCells.Adaptive(166.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(results, key = { "p" + it.ratingKey }) { item -> PosterCard(api, item, false) { onOpen(item) } }
                if (requestable.isNotEmpty()) {
                    item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) {
                        Text("Not in your library — request it", fontSize = 18.sp, color = Pal.Dim, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(requestable, key = { "m" + it.type + it.id }) { m -> MediaCard(m) { onRequest(m) } }
                }
            }
        }
    }
}
