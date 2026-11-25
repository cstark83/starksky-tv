package com.cstark.starktv

import android.app.Application
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class (registered in the manifest). Installs a default uncaught-exception handler that saves the last crash
 * (time, thread, exception class + message, first 5 stack lines) to SharedPreferences for the Settings "Last crash" line,
 * then hands the crash to the previous handler, so the app still crashes normally.
 */
class StarskyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initTrailerExtractor() // NewPipe ready before the first trailer press (background thread)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
                val head = "$time  ·  ${thread.name}  ·  ${e.javaClass.name}: ${e.message ?: ""}".take(400)
                val stack = e.stackTrace.take(5).joinToString("\n") { "  at $it" }
                // commit() (not apply()): the process is about to die
                getSharedPreferences("stark", MODE_PRIVATE).edit().putString("crash", "$head\n$stack").commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
