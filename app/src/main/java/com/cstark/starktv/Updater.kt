package com.cstark.starktv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

const val UPDATE_REPO = "cstark83/starksky-tv"

/** Latest build number + its APK link, from Tower (LAN only; Tower reads the private GitHub repo with its own login). */
const val TOWER_LATEST_URL = "http://192.168.1.8:8480/latest"

/** Tower's "install the latest build now" endpoint (LAN only, answers only the SHIELD; scripts/starsky-update-now on Tower). */
const val TOWER_UPDATE_URL = "http://192.168.1.8:8480/update-now"
private const val TOWER_WAIT_MS = 90_000L

/** Steps of the top-bar Update (see UpdatePanel). */
enum class UpStep { Contacting, Waiting, Downloading, Confirm, Installing, Failed }

/** Asks Tower for the newest GitHub release build and installs it (Android asks you to press Install once). */
object Updater {
    var newBuild by mutableIntStateOf(0)
    var url by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun current(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    suspend fun check(ctx: Context) {
        if (busy) return
        busy = true
        status = "Checking for updates…"
        try {
            // Tower answers from GitHub with its saved login (the repo is private), see TOWER_LATEST_URL
            val body = withContext(Dispatchers.IO) {
                val r = Request.Builder().url(TOWER_LATEST_URL).build()
                try {
                    latestHttp.newCall(r).execute().use { resp ->
                        if (!resp.isSuccessful) error("Tower answered HTTP ${resp.code}")
                        resp.body!!.string()
                    }
                } catch (e: java.io.IOException) {
                    error("Tower can't be reached")
                }
            }
            val j = JSONObject(body)
            val n = j.optInt("build", 0)
            val apk: String? = j.optString("apk").takeIf { it.startsWith("http") }
            if (n > current(ctx) && apk != null) {
                newBuild = n
                url = apk
                status = "Update available: build $n"
                saveResult(ctx, "build $n available")
            } else {
                newBuild = 0
                status = "Up to date (build ${current(ctx)})"
                saveResult(ctx, "up to date (build ${current(ctx)})")
            }
        } catch (e: Exception) {
            status = "Couldn't check for updates (${e.message})"
            saveResult(ctx, "couldn't check (${e.message})")
        } finally {
            busy = false
        }
    }

    /** Saves the check result with its time, for the "Last update check" line in Settings. */
    private fun saveResult(ctx: Context, result: String) {
        val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        Prefs(ctx.applicationContext).lastUpdateCheck = "$time  ·  $result"
    }

    suspend fun install(ctx: Context) {
        val link = url ?: return
        if (busy) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            status = "Allow installs for Starsky TV, come back and press Install again"
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                status = "Turn on Settings > Device Preferences > Security > Unknown sources > Starsky TV"
            }
            return
        }
        busy = true
        status = "Downloading update…"
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "starksky.apk")
                val r = Request.Builder().url(link).build()
                http.newCall(r).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body!!.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                out
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            status = "Press Install on the screen that appears"
        } catch (e: Exception) {
            status = "Update failed (${e.message})"
        } finally {
            busy = false
        }
    }

    // ---------------------------------------------------------------- "Update" on the top bar
    var nowOpen by mutableStateOf(false)
    var nowStep by mutableStateOf(UpStep.Contacting)
    var nowMsg by mutableStateOf("")
    var nowProgress by mutableFloatStateOf(-1f)   // download 0..1, -1 = not downloading
    var nowTarget by mutableIntStateOf(0)
    var nowViaTower by mutableStateOf(true)
    private var nowJob: Job? = null

    /** /latest can take up to ~15 s while Tower asks GitHub. */
    private val latestHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val towerHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Starts the update at once: Tower installs it (primary); if Tower can't, this TV downloads it and opens Android's installer. */
    fun startUpdateNow(ctx: Context, scope: CoroutineScope) {
        if (nowOpen) return
        nowOpen = true
        nowStep = UpStep.Contacting
        nowMsg = ""
        nowProgress = -1f
        nowViaTower = true
        nowTarget = newBuild
        nowJob = scope.launch { runUpdateNow(ctx.applicationContext) }
    }

    /** Back on the panel: stops waiting / downloading and closes it. */
    fun cancelUpdateNow() {
        nowJob?.cancel()
        nowJob = null
        nowOpen = false
        nowProgress = -1f
    }

    private suspend fun runUpdateNow(ctx: Context) {
        if (url == null || newBuild == 0) check(ctx)
        if (newBuild > 0) nowTarget = newBuild
        val reason = askTower()
        if (reason == null) {
            nowStep = UpStep.Waiting
            nowMsg = "Waiting for Tower to install… (up to 90 seconds)"
            val start = System.currentTimeMillis()
            var seenRunning = false
            while (System.currentTimeMillis() - start < TOWER_WAIT_MS) {
                delay(3000)
                val st = towerStatus() ?: continue
                val state = st.optString("state")
                if (state == "running" || state == "installing") seenRunning = true
                // the first seconds may still show the previous request's result: trust it only once this request ran
                val current = seenRunning || System.currentTimeMillis() - start > 15_000
                when {
                    state == "installing" -> {
                        nowStep = UpStep.Installing
                        nowMsg = "Installing, the app will restart"
                    }
                    current && state == "uptodate" -> {
                        nowStep = UpStep.Failed
                        nowMsg = "Tower says Starsky TV is already up to date (build ${st.optInt("build")})."
                        return
                    }
                    current && state == "failed" -> return fallback(ctx, st.optString("message").ifBlank { "Tower couldn't install it" })
                }
            }
            return fallback(ctx, "Tower didn't finish within 90 seconds")
        }
        fallback(ctx, reason)
    }

    /** POSTs the request to Tower. Null when Tower accepted it, otherwise why not. */
    private suspend fun askTower(): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(TOWER_UPDATE_URL).post(ByteArray(0).toRequestBody("text/plain".toMediaType())).build()
            towerHttp.newCall(req).execute().use { r ->
                if (r.isSuccessful) null else "Tower answered ${r.code}"
            }
        } catch (e: Exception) {
            "Tower can't be reached"
        }
    }

    private suspend fun towerStatus(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            towerHttp.newCall(Request.Builder().url("$TOWER_UPDATE_URL/status").build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string() ?: return@use null) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** This TV downloads the APK itself (cancellable, with progress) and opens Android's installer. */
    private suspend fun fallback(ctx: Context, why: String) {
        nowViaTower = false
        val link = url
        if (link == null) {
            nowStep = UpStep.Failed
            nowMsg = "$why, and the update can't be found on Tower right now. Try again later or use Settings."
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            nowStep = UpStep.Failed
            nowMsg = "$why. Android doesn't allow Starsky TV to install updates yet: turn on Settings > Device Preferences > " +
                "Security & restrictions > Unknown sources > Starsky TV, then press Update again."
            return
        }
        nowStep = UpStep.Downloading
        nowMsg = "$why, so this TV is downloading the update itself."
        nowProgress = 0f
        val file = try {
            withContext(Dispatchers.IO) {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "starksky.apk")
                http.newCall(Request.Builder().url(link).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body!!
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                ensureActive()                       // Back cancels between chunks
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                if (total > 0) nowProgress = done.toFloat() / total
                            }
                        }
                    }
                }
                out
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            nowStep = UpStep.Failed
            nowProgress = -1f
            nowMsg = "The download failed (${e.message}). Check the network and press Update again."
            return
        }
        nowProgress = -1f
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            nowStep = UpStep.Confirm
            nowMsg = "Android will now ask \"Do you want to install an update to this existing application?\" " +
                "Move to Install and press OK on the remote. When it says App installed, press OK on Open."
        } catch (e: Exception) {
            nowStep = UpStep.Failed
            nowMsg = "Android's installer couldn't be opened (${e.message}). Use Settings > Install update."
        }
    }
}
