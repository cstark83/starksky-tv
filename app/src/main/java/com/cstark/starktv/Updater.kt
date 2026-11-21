package com.cstark.starktv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

const val UPDATE_REPO = "cstark83/starksky-tv"

/** Checks GitHub Releases for a newer build and installs it (Android asks you to press Install once). */
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
            val body = withContext(Dispatchers.IO) {
                val r = Request.Builder()
                    .url("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                http.newCall(r).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body!!.string()
                }
            }
            val j = JSONObject(body)
            val n = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
            var apk: String? = null
            val assets = j.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
                }
            }
            if (n > current(ctx) && apk != null) {
                newBuild = n
                url = apk
                status = "Update available: build $n"
            } else {
                newBuild = 0
                status = "Up to date (build ${current(ctx)})"
            }
        } catch (e: Exception) {
            status = "Couldn't check for updates (${e.message})"
        } finally {
            busy = false
        }
    }

    suspend fun install(ctx: Context) {
        val link = url ?: return
        if (busy) return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            status = "Allow installs for Starksky TV, come back and press Install again"
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                status = "Turn on Settings > Device Preferences > Security > Unknown sources > Starksky TV"
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
}
