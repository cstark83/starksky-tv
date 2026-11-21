package com.cstark.starktv

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- Spotify launcher

private val SPOTIFY_PACKAGES = listOf("com.spotify.tv.android", "com.spotify.music")

fun openSpotify(ctx: Context) {
    val pm = ctx.packageManager
    for (p in SPOTIFY_PACKAGES) {
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p)
        if (i != null) {
            try {
                ctx.startActivity(i)
                return
            } catch (e: Exception) {
                // try the next package
            }
        }
    }
    Toast.makeText(ctx, "Spotify isn't installed on this device", Toast.LENGTH_LONG).show()
}

fun openAccessSettings(ctx: Context) {
    val tries = listOf(
        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
        android.content.Intent("android.settings.NOTIFICATION_LISTENER_SETTINGS"),
    )
    for (i in tries) {
        try {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        } catch (e: Exception) {
            // try the next one
        }
    }
    Toast.makeText(ctx, "This device has no notification access screen", Toast.LENGTH_LONG).show()
}

fun hasMusicAccess(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

// ---------------------------------------------------------------- now playing

object NowPlaying {
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var album by mutableStateOf<String?>(null)
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)
    var durationMs by mutableLongStateOf(0L)

    private var posBase = 0L
    private var posTime = 0L
    private var speed = 1f
    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null
    private var listenerName: ComponentName? = null
    private var appContext: Context? = null

    val active: Boolean get() = !title.isNullOrBlank()

    fun positionMs(): Long {
        val p = if (playing) posBase + ((SystemClock.elapsedRealtime() - posTime) * speed).toLong() else posBase
        return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }

    fun play() { controller?.transportControls?.play() }
    fun pause() { controller?.transportControls?.pause() }
    fun toggle() { if (playing) pause() else play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMetadata(metadata) }
        override fun onPlaybackStateChanged(state: PlaybackState?) { readState(state) }
        override fun onSessionDestroyed() { clear() }
    }

    fun attach(ctx: Context, listener: ComponentName) {
        appContext = ctx.applicationContext
        listenerName = listener
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, listener)
            pick(m.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // access not granted
        }
    }

    fun detach() {
        try { manager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        controller?.unregisterCallback(callback)
        controller = null
        clear()
    }

    private fun clear() {
        title = null; artist = null; album = null; art = null; playing = false; durationMs = 0L
    }

    private fun pick(list: List<MediaController>?) {
        val own = appContext?.packageName
        val music = (list ?: emptyList()).filter { c ->
            c.packageName != own && !(c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "").isBlank()
        }
        val best = music.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: music.firstOrNull { it.packageName in SPOTIFY_PACKAGES }
            ?: music.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken && best != null) return
        controller?.unregisterCallback(callback)
        controller = best
        if (best == null) { clear(); return }
        best.registerCallback(callback)
        readMetadata(best.metadata)
        readState(best.playbackState)
    }

    private fun readMetadata(m: MediaMetadata?) {
        if (m == null) { clear(); return }
        title = m.getString(MediaMetadata.METADATA_KEY_TITLE)
        artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        album = m.getString(MediaMetadata.METADATA_KEY_ALBUM)
        durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) {
            art = bmp
        } else {
            val uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI)
            val ctx = appContext
            if (uri != null && ctx != null) {
                Thread {
                    try {
                        val loaded = if (uri.startsWith("http")) {
                            http.newCall(Request.Builder().url(uri).build()).execute().use { r ->
                                r.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
                            }
                        } else {
                            ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                        }
                        if (loaded != null) art = loaded
                    } catch (e: Exception) {
                        // no artwork
                    }
                }.start()
            }
        }
    }

    private fun readState(s: PlaybackState?) {
        if (s == null) { playing = false; return }
        playing = s.state == PlaybackState.STATE_PLAYING
        posBase = s.position
        posTime = s.lastPositionUpdateTime
        speed = if (s.playbackSpeed > 0f) s.playbackSpeed else 1f
    }
}

/** The system binds this once the user has allowed "notification access" for Starksky TV. */
class MusicListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NowPlaying.attach(this, ComponentName(this, MusicListener::class.java))
    }

    override fun onListenerDisconnected() {
        NowPlaying.detach()
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
    .build()

// ---------------------------------------------------------------- synced lyrics (LRCLIB)

data class LyricLine(val timeMs: Long, val text: String)

private val LRC_STAMP = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

fun parseLrc(lrc: String): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    for (raw in lrc.lines()) {
        val stamps = LRC_STAMP.findAll(raw).toList()
        if (stamps.isEmpty()) continue
        val text = raw.substring(stamps.last().range.last + 1).trim()
        for (m in stamps) {
            val min = m.groupValues[1].toLong()
            val sec = m.groupValues[2].toLong()
            val frac = m.groupValues[3]
            val ms = if (frac.isEmpty()) 0L else (frac.padEnd(3, '0').take(3)).toLong()
            out += LyricLine(min * 60000 + sec * 1000 + ms, text)
        }
    }
    return out.sortedBy { it.timeMs }
}

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Returns timed lyrics, or an empty list if LRCLIB has none for this song. */
suspend fun fetchLyrics(artist: String, title: String, album: String?, durationMs: Long): List<LyricLine> =
    withContext(Dispatchers.IO) {
        try {
            val secs = durationMs / 1000
            var url = "https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}"
            if (!album.isNullOrBlank()) url += "&album_name=${enc(album)}"
            if (secs > 0) url += "&duration=$secs"
            http.newCall(Request.Builder().url(url).header("User-Agent", "StarkTV/1.0").build()).execute().use { r ->
                if (r.isSuccessful) {
                    val j = JSONObject(r.body?.string() ?: "{}")
                    val lrc = j.optString("syncedLyrics", "")
                    if (lrc.isNotBlank()) return@withContext parseLrc(lrc)
                }
            }
            val sUrl = "https://lrclib.net/api/search?q=${enc("$artist $title")}"
            http.newCall(Request.Builder().url(sUrl).header("User-Agent", "StarkTV/1.0").build()).execute().use { r ->
                if (r.isSuccessful) {
                    val arr = JSONArray(r.body?.string() ?: "[]")
                    for (i in 0 until arr.length()) {
                        val lrc = arr.getJSONObject(i).optString("syncedLyrics", "")
                        if (lrc.isNotBlank()) return@withContext parseLrc(lrc)
                    }
                }
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
