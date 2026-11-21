package com.cstark.starktv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val BANDS = 24

/**
 * Experiment: ask Android to hand us the audio another app is playing, to drive a visualizer.
 * Android and the music app both get a say, so this may be blocked. [state] says what happened.
 */
object Capture {
    /** idle, running, blocked, denied, unavailable */
    var state by mutableStateOf("idle")
    var note by mutableStateOf("")
    val bands = FloatArray(BANDS)

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, CaptureService::class.java))
        if (state == "running") state = "idle"
    }
}

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            Capture.state = "unavailable"
            Capture.note = "No capture permission was received."
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 29) {
            Capture.state = "unavailable"
            Capture.note = "This Android version can't capture app audio."
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { shutdown() }
            }, Handler(Looper.getMainLooper()))
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(16384)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            record = rec
            rec.startRecording()
            running = true
            Capture.state = "running"
            Capture.note = ""
            Thread { loop(rec) }.start()
        } catch (e: SecurityException) {
            Capture.state = "denied"
            Capture.note = "Android refused audio access."
            stopSelf()
        } catch (e: Exception) {
            Capture.state = "unavailable"
            Capture.note = e.message ?: "Couldn't start audio capture."
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("capture", "Music visualizer", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Starksky TV visualizer")
            .setContentText("Listening to the music that's playing")
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(7, n)
        }
    }

    private fun loop(rec: AudioRecord) {
        val n = 1024
        val raw = ShortArray(n * 2)
        val re = FloatArray(n)
        val im = FloatArray(n)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / (n - 1))).toFloat() }
        val edges = IntArray(BANDS + 1) { max(1, Math.pow(380.0, it.toDouble() / BANDS).toInt()) }
        for (i in 1..BANDS) if (edges[i] <= edges[i - 1]) edges[i] = edges[i - 1] + 1
        var ref = 0.02f
        var silentSince = 0L
        while (running) {
            val got = rec.read(raw, 0, raw.size)
            if (got <= 0) { Thread.sleep(10); continue }
            var peak = 0
            for (i in 0 until n) {
                val l = raw[2 * i].toInt()
                val r = raw[2 * i + 1].toInt()
                peak = max(peak, max(abs(l), abs(r)))
                re[i] = ((l + r) / 65536f) * win[i]
                im[i] = 0f
            }
            if (peak < 4 && NowPlaying.playing) {
                if (silentSince == 0L) silentSince = SystemClock.elapsedRealtime()
                if (SystemClock.elapsedRealtime() - silentSince > 5000 && Capture.state == "running") {
                    Capture.state = "blocked"
                    Capture.note = "Android allowed capture, but the music app is sending silence to it."
                }
            } else if (peak >= 4) {
                silentSince = 0L
                if (Capture.state == "blocked") Capture.state = "running"
            }
            fft(re, im)
            var top = 0f
            val vals = FloatArray(BANDS)
            for (b in 0 until BANDS) {
                var sum = 0f
                val lo = edges[b]
                val hi = min(edges[b + 1], n / 2)
                var cnt = 0
                for (k in lo until max(hi, lo + 1)) {
                    sum += sqrt(re[k] * re[k] + im[k] * im[k])
                    cnt++
                }
                val v = if (cnt > 0) sum / cnt else 0f
                vals[b] = v
                if (v > top) top = v
            }
            ref = max(ref * 0.995f, top)
            for (b in 0 until BANDS) {
                val v = (vals[b] / ref).coerceIn(0f, 1f)
                Capture.bands[b] = max(v, Capture.bands[b] * 0.82f)
            }
        }
    }

    private fun shutdown() {
        running = false
        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        for (i in 0 until BANDS) Capture.bands[i] = 0f
    }

    override fun onDestroy() {
        shutdown()
        if (Capture.state == "running") Capture.state = "idle"
        super.onDestroy()
    }
}

private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while ((j and bit) != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val t = re[i]; re[i] = re[j]; re[j] = t
            val u = im[i]; im[i] = im[j]; im[j] = u
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * Math.PI / len
        val wr = cos(ang).toFloat()
        val wi = sin(ang).toFloat()
        var i = 0
        while (i < n) {
            var cr = 1f
            var ci = 0f
            val half = len / 2
            for (k in 0 until half) {
                val ur = re[i + k]
                val ui = im[i + k]
                val xr = re[i + k + half]
                val xi = im[i + k + half]
                val vr = xr * cr - xi * ci
                val vi = xr * ci + xi * cr
                re[i + k] = ur + vr
                im[i + k] = ui + vi
                re[i + k + half] = ur - vr
                im[i + k + half] = ui - vi
                val ncr = cr * wr - ci * wi
                ci = cr * wi + ci * wr
                cr = ncr
            }
            i += len
        }
        len = len shl 1
    }
}
