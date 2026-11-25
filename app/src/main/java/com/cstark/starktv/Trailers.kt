package com.cstark.starktv

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Collections
import java.util.concurrent.TimeUnit
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

/** Plex sources: "started" = STATE_READY within 20 s; past 20 s keep waiting (up to 45 s) only while bytes keep arriving. */
private const val PLEX_START_MS = 20_000L
private const val PLEX_MAX_MS = 45_000L
/** No new bytes for this long (after the start time) = stalled. */
private const val STALL_MS = 3_000L
/** Looking up a Plex source (discover search, extras list). */
private const val PLEX_RESOLVE_MS = 10_000L
/** The whole in-app YouTube step (TMDB list + NewPipe extraction + start), over up to [YT_MAX_IDS] videos. */
private const val YT_STEP_MS = 25_000L
private const val YT_MAX_IDS = 5
/** Tower backup (yt-dlp, scripts/starsky-update-now/server.php -> trailer-resolver container), per request. */
private const val TOWER_MS = 20_000L
private const val TOWER_TRAILER_URL = "http://192.168.1.8:8480/trailer?yt="
/** Resolved YouTube stream URLs expire (about 6 h); keep them for 4 h. */
private const val YT_CACHE_MS = 4 * 3600_000L
private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

private fun tlog(ctx: Context, msg: String) = PlayLog.add(ctx, "trailer: $msg")
private fun hostOf(url: String) = runCatching { Uri.parse(url).host }.getOrNull() ?: "?"
private fun now() = SystemClock.elapsedRealtime()

/** One thing to play: how to build it, plus where it comes from (for the log). */
private class Playable(val label: String, val host: String, val build: (DataSource.Factory) -> MediaSource)

/** Video ids that failed this app session (Retry and later presses skip them). */
private val failedYt: MutableSet<String> = Collections.synchronizedSet(HashSet())

// ---------------------------------------------------------------- NewPipeExtractor (in-app YouTube)

/** NewPipeExtractor's HTTP client: OkHttp, 15 s timeouts, browser User-Agent, en-US. Logs each request's time (no query). */
private object NpDownloader : Downloader() {
    @Volatile var sink: ((String) -> Unit)? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NpRequest): NpResponse {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
        for ((k, vs) in request.headers()) {
            b.removeHeader(k)
            vs.forEach { b.addHeader(k, it) }
        }
        val t0 = now()
        val u = Uri.parse(request.url())
        try {
            http.newCall(b.build()).execute().use { r ->
                sink?.invoke("np $method ${u.host}${u.path?.take(60) ?: ""} -> ${r.code} in ${now() - t0} ms")
                if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
                return NpResponse(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        } catch (e: java.io.IOException) {
            sink?.invoke("np $method ${u.host}${u.path?.take(60) ?: ""} failed after ${now() - t0} ms: ${e.message?.take(80)}")
            throw e
        }
    }
}

private object YouTube {
    /** kind: dash | merge | hls | progressive. */
    data class Resolved(val kind: String, val url: String, val audioUrl: String? = null, val height: Int = 0)

    private val cache = HashMap<String, Pair<Long, Resolved>>()
    @Volatile private var ready = false

    fun init() {
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
        h["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else BROWSER_UA
        spec.withAdditionalHeaders(h)
    }

    @OptIn(UnstableApi::class)
    fun playable(r: Resolved): Playable = Playable("in-app ${r.kind}${if (r.height > 0) " ${r.height}p" else ""}", hostOf(r.url)) { base ->
        val ds = dataSource(base)
        when (r.kind) {
            "dash" -> DashMediaSource.Factory(ds).createMediaSource(MediaItem.Builder().setUri(r.url).setMimeType(MimeTypes.APPLICATION_MPD).build())
            "hls" -> hls(ds, r.url)
            "merge" -> MergingMediaSource(progressive(ds, r.url), progressive(ds, r.audioUrl!!))
            else -> progressive(ds, r.url)
        }
    }
}

/** Start the extractor once, in the background, when the app starts (not on the first trailer press). */
fun initTrailerExtractor() {
    Thread({ runCatching { YouTube.init() } }, "newpipe-init").start()
}

@OptIn(UnstableApi::class)
private fun hls(ds: DataSource.Factory, url: String): MediaSource =
    HlsMediaSource.Factory(ds).createMediaSource(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())

@OptIn(UnstableApi::class)
private fun progressive(ds: DataSource.Factory, url: String): MediaSource =
    ProgressiveMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(url))

/** Extraction said the video itself can't be played (UNPLAYABLE, removed, private, age/region restricted, ...). */
private fun unplayable(e: Throwable): Boolean {
    if (e is ContentNotAvailableException) return true
    val m = (e.message ?: "").lowercase()
    return listOf("unplayable", "not available", "unavailable", "age", "region", "country", "private", "removed").any { m.contains(it) }
}

// ---------------------------------------------------------------- Tower backup (yt-dlp)

private val towerHttp = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(TOWER_MS, TimeUnit.MILLISECONDS)
    .callTimeout(TOWER_MS, TimeUnit.MILLISECONDS)
    .build()

/** Asks Tower for direct stream URLs of [id]: (merged 1080p, muxed) playables, or throws with Tower's reason. */
@OptIn(UnstableApi::class)
private suspend fun towerPlayables(id: String): List<Playable> = withContext(Dispatchers.IO) {
    val req = okhttp3.Request.Builder().url(TOWER_TRAILER_URL + id).header("User-Agent", "Starsky TV").build()
    val j = towerHttp.newCall(req).execute().use { r ->
        val body = r.body?.string() ?: ""
        val o = runCatching { JSONObject(body) }.getOrNull() ?: throw IllegalStateException("Tower HTTP ${r.code}")
        if (!o.optBoolean("ok")) throw IllegalStateException("Tower: ${o.optString("error", "HTTP ${r.code}").take(120)}")
        o
    }
    val ua = j.optJSONObject("headers")?.optString("User-Agent")?.takeIf { it.isNotBlank() } ?: BROWSER_UA
    val headers = HashMap<String, String>().apply {
        put("User-Agent", ua)
        j.optJSONObject("headers")?.let { h -> for (k in listOf("Referer", "Origin")) h.optString(k).takeIf { it.isNotBlank() }?.let { put(k, it) } }
    }
    fun ds(base: DataSource.Factory): DataSource.Factory = ResolvingDataSource.Factory(base) { spec: DataSpec -> spec.withAdditionalHeaders(headers) }
    val out = mutableListOf<Playable>()
    val v = j.optString("video_url"); val a = j.optString("audio_url")
    if (v.isNotBlank() && a.isNotBlank()) {
        val vk = j.optString("video_kind")
        out += Playable("Tower yt-dlp merge ${j.optInt("video_height")}p", hostOf(v)) { base ->
            val d = ds(base)
            MergingMediaSource(if (vk == "hls") hls(d, v) else progressive(d, v), progressive(d, a))
        }
    }
    val m = j.optString("url")
    if (m.isNotBlank()) {
        val k = j.optString("kind")
        out += Playable("Tower yt-dlp $k ${j.optInt("height")}p", hostOf(m)) { base -> val d = ds(base); if (k == "hls") hls(d, m) else progressive(d, m) }
    }
    if (out.isEmpty()) throw IllegalStateException("Tower returned no stream")
    out
}

// ---------------------------------------------------------------- playing one candidate

/** Counts bytes the player downloads (to tell "still buffering" from "stuck"). */
@OptIn(UnstableApi::class)
private class ByteCounter : TransferListener {
    @Volatile var bytes = 0L
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { bytes += bytesTransferred }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}

private class PlayResult(val ok: Boolean, val reason: String, val ms: Long, val timeout: Boolean)

private fun stateName(s: Int) = when (s) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "$s"
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

/**
 * Plays [p] and waits until the player reaches STATE_READY (= started). Fails on a player error, or once [startMs] has
 * passed and no bytes arrived for [STALL_MS], or at [maxMs]. Runs on the main thread (player calls), polling every 200 ms.
 */
@OptIn(UnstableApi::class)
private suspend fun tryPlay(player: ExoPlayer, dsf: DataSource.Factory, counter: ByteCounter, p: Playable, startMs: Long, maxMs: Long): PlayResult {
    var err: String? = null
    val l = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { err = describe(error) }
    }
    val t0 = now()
    player.addListener(l)
    try {
        try {
            player.setMediaSource(p.build(dsf))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            return PlayResult(false, e.message ?: e.javaClass.simpleName, now() - t0, false)
        }
        var lastBytes = counter.bytes
        var lastGrowth = t0
        while (true) {
            val t = now()
            if (player.playbackState == Player.STATE_READY) return PlayResult(true, "READY", t - t0, false)
            err?.let { return PlayResult(false, it, t - t0, false) }
            if (counter.bytes > lastBytes) { lastBytes = counter.bytes; lastGrowth = t }
            val el = t - t0
            if (el >= maxMs || (el >= startMs && t - lastGrowth > STALL_MS)) {
                return PlayResult(
                    false,
                    "not started after ${el / 1000} s (state ${stateName(player.playbackState)}, ${lastBytes / 1024} KB in, last bytes ${(t - lastGrowth) / 1000} s ago)",
                    el,
                    true,
                )
            }
            delay(200)
        }
    } finally {
        player.removeListener(l)
        if (player.playbackState != Player.STATE_READY) { player.stop(); player.clearMediaItems() }
    }
}

// ---------------------------------------------------------------- screen

/**
 * Trailer player: Plex extras, Plex's own trailer, then YouTube (TMDB videos, resolved in the app, Tower's yt-dlp as the
 * backup), and plays the first that starts, with normal controls. Never opens another app. Nothing is written to Plex
 * (no timeline, no watched). Back = previous screen (the app's Back handler). Every phase is timed in the playback log.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrailerScreen(api: PlexApi, req: TrailerReq) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val counter = remember { ByteCounter() }
    val dsf = remember {
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // Plex sends http -> https redirects to the video host
            .setUserAgent("Starsky TV")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setTransferListener(counter)
    }
    var status by remember { mutableStateOf("Finding trailer…") }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    val retryFr = remember { FocusRequester() }
    DisposableEffect(Unit) {
        NpDownloader.sink = { tlog(ctx, it) }
        onDispose {
            NpDownloader.sink = null
            player.release()
        }
    }

    LaunchedEffect(tries) {
        loading = true
        failed = null
        val reasons = mutableListOf<String>()
        val all0 = now()
        tlog(ctx, "start for ${req.title} (${req.year}), local extras ${req.localParts.size}")

        suspend fun play(src: String, p: Playable, startMs: Long, maxMs: Long): PlayResult {
            val r = tryPlay(player, dsf, counter, p, startMs, maxMs)
            if (r.ok) {
                tlog(ctx, "phase player-ready $src ${p.label} from ${p.host}: ${r.ms} ms")
            } else {
                tlog(ctx, "phase player $src ${p.label} from ${p.host} failed after ${r.ms} ms: ${r.reason}")
                reasons += "$src: ${r.reason}"
            }
            return r
        }

        // a + b: Plex extras on the server, then Plex's own trailer
        suspend fun plexSource(name: String, resolver: suspend () -> Playable?): Boolean {
            status = "Loading $name…"
            val t0 = now()
            val p = try {
                withTimeoutOrNull(PLEX_RESOLVE_MS) { resolver() }
            } catch (e: Exception) {
                tlog(ctx, "phase resolve $name failed after ${now() - t0} ms: ${e.message?.take(120)}")
                reasons += "$name: ${e.message?.take(80)}"
                return false
            }
            if (p == null) {
                tlog(ctx, "phase resolve $name: nothing found (${now() - t0} ms)")
                reasons += "$name: nothing found"
                return false
            }
            tlog(ctx, "phase resolve $name: ${now() - t0} ms")
            return play(name, p, PLEX_START_MS, PLEX_MAX_MS).ok
        }

        var ok = false
        for ((i, part) in req.localParts.take(2).withIndex()) {
            val found = plexSource("Plex extra ${i + 1}") {
                val url = api.media(part)
                Playable("Plex extra", hostOf(url)) { ds -> DefaultMediaSourceFactory(ds).createMediaSource(MediaItem.fromUri(url)) }
            }
            if (found) { ok = true; break }
        }
        if (!ok) {
            ok = plexSource("Plex trailer") {
                val key = req.discoverKey ?: api.discoverKeyFor(req.title, req.year, if (req.type == "show") "tv" else "movie", req.tmdbId)
                key?.let { api.providerTrailers(it).firstOrNull() }?.let { u -> Playable("Plex trailer", hostOf(u)) { ds -> hls(ds, u) } }
            }
        }

        // c: YouTube. TMDB videos (ranked), each: in-app NewPipe (25 s for the whole in-app step), Tower yt-dlp as backup.
        if (!ok) {
            status = "Finding trailer…"
            val y0 = now()
            val ids = try {
                withTimeoutOrNull(10_000L) { Tmdb.trailerYoutubeIds(ctx, req) } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            tlog(ctx, "phase tmdb-videos: ${ids.size} videos in ${now() - y0} ms")
            val todo = ids.filter { it !in failedYt }.take(YT_MAX_IDS)
            if (ids.isEmpty()) reasons += "YouTube: TMDB has no trailer"
            else if (todo.isEmpty()) reasons += "YouTube: the TMDB videos already failed in this session"
            for (id in todo) {
                var badVideo = false
                val left = YT_STEP_MS - (now() - y0)
                if (left > 2_000L) {
                    // in-app extraction, within what is left of the 25 s step
                    val e0 = now()
                    val cachedHit = YouTube.cached(id)
                    val res = try {
                        cachedHit ?: withTimeoutOrNull(left) { withContext(Dispatchers.IO) { YouTube.resolve(id) } }
                    } catch (e: Exception) {
                        val m = e.message?.take(120) ?: e.javaClass.simpleName
                        badVideo = unplayable(e)
                        tlog(ctx, "phase newpipe-extract $id failed after ${now() - e0} ms: $m${if (badVideo) " (video not playable)" else ""}")
                        reasons += "YouTube $id: $m"
                        null
                    }
                    if (res != null) {
                        tlog(ctx, "phase newpipe-extract $id: ${if (cachedHit != null) "cached" else "${now() - e0} ms"} -> ${res.kind} ${res.height}p")
                        val b0 = now()
                        val p = YouTube.playable(res)
                        tlog(ctx, "phase build-source $id: ${now() - b0} ms")
                        val rem = (YT_STEP_MS - (now() - y0)).coerceAtLeast(8_000L)
                        val r = play("YouTube $id", p, rem.coerceAtMost(15_000L), rem)
                        if (r.ok) { ok = true; break }
                        // a cached link that really failed (not a timeout): resolve again once
                        if (cachedHit != null && !r.timeout) {
                            YouTube.drop(id)
                            val again = runCatching { withTimeoutOrNull(15_000L) { withContext(Dispatchers.IO) { YouTube.resolve(id) } } }.getOrNull()
                            if (again != null && play("YouTube $id (re-resolved)", YouTube.playable(again), 15_000L, 20_000L).ok) { ok = true; break }
                        }
                    } else if (!badVideo && now() - e0 >= left - 100) {
                        tlog(ctx, "phase newpipe-extract $id: timed out after ${now() - e0} ms")
                        reasons += "YouTube $id: in-app timed out"
                    }
                } else {
                    tlog(ctx, "in-app YouTube step used its 25 s; Tower only for $id")
                }
                if (badVideo) { failedYt += id; continue }
                // Tower backup (yt-dlp) for this id
                status = "Finding trailer (Tower)…"
                val t0 = now()
                val tower = try {
                    towerPlayables(id)
                } catch (e: Exception) {
                    tlog(ctx, "phase tower-resolve $id failed after ${now() - t0} ms: ${e.message?.take(120)}")
                    reasons += "Tower $id: ${e.message?.take(80)}"
                    null
                }
                if (tower != null) {
                    tlog(ctx, "phase tower-resolve $id: ${now() - t0} ms, ${tower.size} stream(s)")
                    for (p in tower) {
                        if (play("Tower $id", p, 15_000L, 25_000L).ok) { ok = true; break }
                    }
                    if (ok) break
                }
                failedYt += id
                tlog(ctx, "YouTube $id: no source worked, skipped from now on")
            }
        }
        loading = false
        if (ok) {
            tlog(ctx, "playing after ${now() - all0} ms")
            view?.requestFocus()
        } else {
            failed = reasons.joinToString("  ·  ").ifBlank { "no trailer source" }
            tlog(ctx, "none worked for ${req.title} after ${now() - all0} ms: $failed")
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
