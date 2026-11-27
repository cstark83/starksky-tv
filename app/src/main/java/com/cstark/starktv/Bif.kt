package com.cstark.starktv

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plex video preview thumbnails (Roku BIF format): a 64-byte header (magic, version, frame count, interval), an index of
 * (frame number, byte offset) pairs, then the JPEG frames. Kept in memory for the item being played; frames are decoded
 * only when asked for (seek bar scrubbing), the last few decoded ones are kept.
 */
class Bif private constructor(private val data: ByteArray, private val times: LongArray, private val offs: IntArray) {
    private val decoded = LinkedHashMap<Int, ImageBitmap?>(16, 0.75f, true)

    /** The frame shown at [ms] (the last one at or before it), or null. */
    fun frame(ms: Long): ImageBitmap? {
        if (times.isEmpty()) return null
        var lo = 0; var hi = times.size - 1; var i = 0
        while (lo <= hi) { val m = (lo + hi) / 2; if (times[m] <= ms) { i = m; lo = m + 1 } else hi = m - 1 }
        synchronized(decoded) { if (decoded.containsKey(i)) return decoded[i] }
        val len = offs[i + 1] - offs[i]
        val bmp = if (len > 0) runCatching { BitmapFactory.decodeByteArray(data, offs[i], len)?.asImageBitmap() }.getOrNull() else null
        synchronized(decoded) {
            decoded[i] = bmp
            while (decoded.size > 12) decoded.remove(decoded.keys.first())
        }
        return bmp
    }

    companion object {
        private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)

        fun parse(d: ByteArray): Bif? {
            if (d.size < 64 || !d.copyOfRange(0, 8).contentEquals(MAGIC)) return null
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val count = b.getInt(12)
            val interval = b.getInt(16).let { if (it == 0) 1000 else it }
            if (count <= 0 || 64 + (count + 1) * 8 > d.size) return null
            val times = LongArray(count)
            val offs = IntArray(count + 1)
            for (i in 0..count) {
                val ts = b.getInt(64 + i * 8).toLong() and 0xffffffffL
                val off = b.getInt(64 + i * 8 + 4)
                if (i < count) times[i] = ts * interval
                offs[i] = off.coerceIn(0, d.size)
            }
            return Bif(d, times, offs)
        }
    }
}
