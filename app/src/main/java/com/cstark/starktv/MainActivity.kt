package com.cstark.starktv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize

sealed class Screen {
    data class Intro(val preview: Boolean = false) : Screen()
    object Boot : Screen()
    object Login : Screen()
    object Profiles : Screen()
    object Main : Screen()
    object NowPlaying : Screen()
    object Search : Screen()
    data class Request(val media: Media) : Screen()
    data class Detail(val key: String) : Screen()

    /** Plays a library item (key) from [offset] ms, or a direct [url] (trailers). */
    data class Play(val key: String, val offset: Long = 0L, val url: String? = null) : Screen()
}

/** Lets the activity ask the Compose tree to open Search (remote's search key). */
object Nav {
    var searchRequest by mutableIntStateOf(0)
}

class MainActivity : ComponentActivity() {
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            Nav.searchRequest++
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)
        val api = PlexApi(prefs)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Pal.Bg,
                    surface = Pal.Bg,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    primary = Pal.Accent,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Pal.Bg, contentColor = Color.White) {
                    App(api, prefs)
                }
            }
        }
    }
}

@Composable
fun App(api: PlexApi, prefs: Prefs) {
    val stack = remember {
        val now = System.currentTimeMillis()
        val today = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L
        val showIntro = prefs.introStyle != INTRO_OFF && !(prefs.introDaily && prefs.lastIntroDay == today)
        if (showIntro) prefs.lastIntroDay = today
        val first: Screen = if (showIntro) Screen.Intro() else Screen.Boot
        mutableStateListOf(first)
    }
    var refresh by remember { mutableIntStateOf(0) }
    val home = remember { HomeData() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ov = remember { OverseerrApi(ctx, prefs) }

    fun reset(s: Screen) {
        stack.clear()
        stack.add(s)
    }

    fun pop() {
        if (stack.size > 1) {
            val top = stack.removeAt(stack.lastIndex)
            if (top is Screen.Play) refresh++
        }
    }

    fun switchProfile() {
        prefs.userToken = null
        prefs.serverToken = null
        ov.reset()
        home.reset()
        reset(Screen.Profiles)
    }

    fun signOut() {
        prefs.signOut()
        ov.reset()
        home.reset()
        reset(Screen.Login)
    }

    BackHandler(enabled = stack.size > 1) { pop() }
    LaunchedEffect(Nav.searchRequest) {
        if (Nav.searchRequest > 0 && stack.last() == Screen.Main) stack.add(Screen.Search)
    }

    when (val s = stack.last()) {
        is Screen.Intro -> IntroScreen(prefs.introStyle.let { if (it == INTRO_OFF) 0 else it }) {
            if (s.preview) pop() else reset(Screen.Boot)
        }

        Screen.Boot -> BootScreen(
            api, prefs,
            onLogin = { reset(Screen.Login) },
            onProfiles = { reset(Screen.Profiles) },
            onReady = { reset(Screen.Main) },
            onSignOut = { signOut() },
        )

        Screen.Login -> LoginScreen(api, prefs) { reset(Screen.Profiles) }

        Screen.Profiles -> ProfilesScreen(api, prefs) {
            home.reset()
            reset(Screen.Boot)
        }

        Screen.Main -> MainScreen(
            api, prefs, home, refresh,
            onDetail = { stack.add(Screen.Detail(it)) },
            onPlay = { stack.add(Screen.Play(it.ratingKey, it.viewOffset)) },
            onPreviewIntro = { stack.add(Screen.Intro(true)) },
            onNowPlaying = { stack.add(Screen.NowPlaying) },
            ov = ov,
            onRequest = { stack.add(Screen.Request(it)) },
            onSearch = { stack.add(Screen.Search) },
            onSync = { refresh++ },
            onSwitchProfile = { switchProfile() },
            onSignOut = { signOut() },
        )

        Screen.NowPlaying -> NowPlayingScreen { pop() }

        is Screen.Request -> key(s) { RequestScreen(api, ov, s.media) { pop() } }

        Screen.Search -> SearchScreen(
            api,
            ov,
            onRequest = { stack.add(Screen.Request(it)) },
            onOpen = { stack.add(Screen.Detail(it.showKey)) },
            onClose = { pop() },
        )

        is Screen.Detail -> key(s) {
            DetailScreen(
                api, s.key, refresh,
                onPlay = { item, resume -> stack.add(Screen.Play(item.ratingKey, if (resume) item.viewOffset else 0L)) },
                onTrailer = { t -> stack.add(Screen.Play(key = "", url = api.media(t.partKey))) },
            )
        }

        is Screen.Play -> key(s) {
            PlayerScreen(
                api, prefs, s,
                onNext = { next -> stack[stack.lastIndex] = Screen.Play(next, 0L) },
                onExit = { pop() },
            )
        }
    }
}
