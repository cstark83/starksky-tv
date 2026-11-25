package com.cstark.starktv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

/** What the title page knows about a title: enough to find a trailer. */
data class TrailerReq(
    val title: String,
    val year: Int,
    val type: String,
    /** Plex extras on this server (local trailers), part keys. */
    val localParts: List<String>,
    /** Plex discover id (from the plex:// guid), null = look it up. */
    val discoverKey: String?,
    val tmdbId: String?,
    val imdbId: String?,
)

/** Per source: resolve and start playing within this time, then try the next one. */
private const val SOURCE_TIMEOUT_MS = 8_000L
/** Resolved YouTube stream URLs expire (about 6 h); keep them for 4 h. */
private const val YT_CACHE_MS = 4 * 3600_000L
private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

private fun tlog(ctx: Context, msg: String) = PlayLog.add(ctx, "trailer: $msg")
private fun hostOf(url: String) = runCatching { Uri.parse(url).host }.getOrNull() ?: "?"

/** One thing to play: how to build it, plus where it comes from (for the log). */
private class Playable(val label: String, val host: String, val build: (DataSource.Factory) -> MediaSource)

// ---------------------------------------------------------------- YouTube (TMDB video id -> stream URLs, in the app)

/** NewPipeExtractor's HTTP client (OkHttp). */
private object NpDownloader : Downloader() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body).header("User-Agent", DESKTOP_UA)
        for ((k, vs) in request.headers()) {
            b.removeHeader(k)
            vs.forEach { b.addHeader(k, it) }
        }
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

private object YouTube {
    /** kind: dash | merge | hls | progressive. */
    data class Resolved(val kind: String, val url: String, val audioUrl: String? = null, val height: Int = 0)

    private val cache = HashMap<String, Pair<Long, Resolved>>()
    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(NpDownloader)
                ready = true
            }
        }
    }

    @Synchronized fun cached(id: String): Resolved? =
        cache[id]?.takeIf { System.currentTimeMillis() - it.first < YT_CACHE_MS }?.second

    @Synchronized fun drop(id: String) { cache.remove(id) }

    /** Blocking network call (run on IO): best stream up to 1080p. */
    fun resolve(id: String): Resolved {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        val r = pick(info) ?: throw IllegalStateException("no playable YouTube stream")
        synchronized(this) { cache[id] = System.currentTimeMillis() to r }
        return r
    }

    private fun pick(info: StreamInfo): Resolved? {
        val dash = info.dashMpdUrl
        if (!dash.isNullOrBlank()) return Resolved("dash", dash)

        val vids = info.videoOnlyStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..1080
        }
        val v = vids.filter { it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc") == true }.maxByOrNull { it.height }
            ?: vids.filter { it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.height }
            ?: vids.maxByOrNull { it.height }
        val auds = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL)
        }
        val a = auds.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: auds.maxByOrNull { it.averageBitrate }
        if (v != null && a != null) return Resolved("merge", v.content, a.content, v.height)

        val hls = info.hlsUrl
        if (!hls.isNullOrBlank()) return Resolved("hls", hls)

        val prog = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height <= 1080 }
            .maxByOrNull { it.height }
        return prog?.let { Resolved("progressive", it.content, height = it.height) }
    }

    /** googlevideo wants the headers of the client the URL was made for (as NewPipe's own player sends them). */
    @OptIn(UnstableApi::class)
    fun dataSource(upstream: DataSource.Factory): DataSource.Factory = ResolvingDataSource.Factory(upstream) { spec ->
        val url = spec.uri.toString()
        val h = HashMap<String, String>()
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            h["Origin"] = "https://www.youtube.com"
            h["Referer"] = "https://www.youtube.com/"
        }
        h["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else DESKTOP_UA
        spec.withAdditionalHeaders(h)
    }

    @OptIn(UnstableApi::class)
    fun playable(r: Resolved): Playable = Playable("YouTube ${r.kind}${if (r.height > 0) " ${r.height}p" else ""}", hostOf(r.url)) { base ->
        val ds = dataSource(base)
        when (r.kind) {
            "dash" -> DashMediaSource.Factory(ds).createMediaSource(MediaItem.Builder().setUri(r.url).setMimeType(MimeTypes.APPLICATION_MPD).build())
            "hls" -> HlsMediaSource.Factory(ds).createMediaSource(MediaItem.Builder().setUri(r.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            "merge" -> MergingMediaSource(
                ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(r.url)),
                ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(r.audioUrl!!)),
            )
            else -> ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(r.url))
        }
    }
}

// ---------------------------------------------------------------- the source chain

/** A source: name for the log, and a resolver that returns what to play (null = it has nothing). */
private class Source(val name: String, val resolve: suspend () -> Playable?, val retry: (suspend () -> Playable?)? = null)

@OptIn(UnstableApi::class)
private fun sources(ctx: Context, api: PlexApi, req: TrailerReq): List<Source> {
    val out = mutableListOf<Source>()
    // a. Plex extras on this server (profile's server token in the URL; the server redirects to the video host)
    req.localParts.take(2).forEachIndexed { i, part ->
        out += Source("Plex extra ${i + 1}", {
            val url = api.media(part)
            Playable("Plex extra", hostOf(url)) { ds -> DefaultMediaSourceFactory(ds).createMediaSource(MediaItem.fromUri(url)) }
        })
    }
    // b. Plex's own trailer for the title (discover provider, HLS hosted by Plex; user token only to Plex hosts)
    out += Source("Plex trailer", {
        val key = req.discoverKey ?: api.discoverKeyFor(req.title, req.year, if (req.type == "show") "tv" else "movie", req.tmdbId)
        val url = key?.let { api.providerTrailers(it).firstOrNull() }
        url?.let { u ->
            Playable("Plex trailer", hostOf(u)) { ds ->
                HlsMediaSource.Factory(ds).createMediaSource(MediaItem.Builder().setUri(u).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            }
        }
    })
    // c. TMDB video id -> YouTube stream resolved in the app (cached 4 h; a failed cached URL is re-resolved once)
    var ytId: String? = null
    var fromCache = false
    out += Source(
        "YouTube (TMDB)",
        {
            val id = Tmdb.trailerYoutubeId(ctx, req) ?: throw IllegalStateException("TMDB has no trailer")
            ytId = id
            val c = YouTube.cached(id)
            fromCache = c != null
            YouTube.playable(c ?: withContext(Dispatchers.IO) { YouTube.resolve(id) })
        },
        retry = {
            val id = ytId
            if (id == null || !fromCache) null else {
                YouTube.drop(id)
                YouTube.playable(withContext(Dispatchers.IO) { YouTube.resolve(id) })
            }
        },
    )
    return out
}

/** Why a playback attempt failed: HTTP status + host when there is one. */
@OptIn(UnstableApi::class)
private fun describe(e: PlaybackException): String {
    var c: Throwable? = e
    while (c != null) {
        if (c is HttpDataSource.InvalidResponseCodeException) return "HTTP ${c.responseCode} from ${c.dataSpec.uri.host}"
        if (c is HttpDataSource.HttpDataSourceException) return "${e.errorCodeName} (${c.dataSpec.uri.host})"
        c = c.cause
    }
    return e.errorCodeName
}

/** Plays [p]; returns null once it is really playing (STATE_READY), else the reason it failed or timed out. */
@OptIn(UnstableApi::class)
private suspend fun tryPlay(player: ExoPlayer, dsf: DataSource.Factory, p: Playable): String? {
    val res = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
            val l = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && cont.isActive) { player.removeListener(this); cont.resume(null) }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (cont.isActive) { player.removeListener(this); cont.resume(describe(error)) }
                }
            }
            player.addListener(l)
            cont.invokeOnCancellation { player.removeListener(l) }
            try {
                player.setMediaSource(p.build(dsf))
                player.prepare()
                player.playWhenReady = true
            } catch (e: Exception) {
                player.removeListener(l)
                if (cont.isActive) cont.resume(e.message ?: e.javaClass.simpleName)
            }
        }
    } ?: "timed out after ${SOURCE_TIMEOUT_MS / 1000} s"
    if (res != null) { player.stop(); player.clearMediaItems() }
    return res
}

// ---------------------------------------------------------------- screen

/**
 * Trailer player: tries Plex extras, Plex's own trailer, then YouTube (via TMDB, resolved in the app), about 8 s each,
 * and plays the first that works with normal controls. Never opens another app. Nothing is written to Plex (no
 * timeline, no watched). Back = previous screen (the app's Back handler).
 */
@OptIn(UnstableApi::class)
@Composable
fun TrailerScreen(api: PlexApi, req: TrailerReq) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val dsf = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // Plex sends http -> https redirects to the video host
            .setUserAgent("Starsky TV")
            .setConnectTimeoutMs(SOURCE_TIMEOUT_MS.toInt())
            .setReadTimeoutMs(SOURCE_TIMEOUT_MS.toInt())
    }
    var status by remember { mutableStateOf("Finding the trailer…") }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    val retryFr = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(tries) {
        loading = true
        failed = null
        val reasons = mutableListOf<String>()
        var ok = false
        tlog(ctx, "start for ${req.title} (${req.year}), local extras ${req.localParts.size}")
        for (s in sources(ctx, api, req)) {
            status = "Trying ${s.name}…"
            suspend fun attempt(resolver: suspend () -> Playable?, what: String): Boolean {
                val p = try {
                    withTimeoutOrNull(SOURCE_TIMEOUT_MS) { resolver() } ?: run { reasons += "$what: nothing found"; tlog(ctx, "$what: nothing found / timed out"); return false }
                } catch (e: Exception) {
                    val m = e.message?.take(120) ?: e.javaClass.simpleName
                    reasons += "$what: $m"; tlog(ctx, "$what: resolve failed: $m"); return false
                }
                val r = tryPlay(player, dsf, p)
                return if (r == null) {
                    tlog(ctx, "$what: playing ${p.label} from ${p.host}"); true
                } else {
                    reasons += "$what: $r"; tlog(ctx, "$what: ${p.label} from ${p.host} failed: $r"); false
                }
            }
            if (attempt(s.resolve, s.name)) { ok = true; break }
            val again = s.retry
            if (again != null && attempt(again, "${s.name} (re-resolved)")) { ok = true; break }
        }
        loading = false
        if (ok) {
            view?.requestFocus()
        } else {
            failed = reasons.joinToString("  ·  ").ifBlank { "no trailer source" }
            tlog(ctx, "none worked for ${req.title}: $failed")
        }
    }
    LaunchedEffect(failed) { if (failed != null) focusSoon(retryFr) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (failed == null) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = true
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        view = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (loading) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(56.dp), color = Color.White)
                Text(status, fontSize = 18.sp, color = Pal.Dim)
            }
        }
        val f = failed
        if (f != null) {
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Trailer isn't available", fontSize = 30.sp, color = Color.White)
                Chip("Retry", fr = retryFr) { tries++ }
                Text(f, fontSize = 13.sp, color = Pal.Dim, textAlign = TextAlign.Center)
            }
        }
    }
}

