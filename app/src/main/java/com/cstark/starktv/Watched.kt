package com.cstark.starktv

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Every watched / unwatched mark goes through here (player: end, countdown, past 90%; hold-OK menus; title page chips).
 *
 * - Each mark is saved first (SharedPreferences "markq", written with commit()), then sent. It leaves the queue only when
 *   Plex answers 2xx (or 404: the item is gone, nothing to mark). Anything else (no network, Plex restarting, 5xx, 401)
 *   stays and is retried: 20 s, 1, 5, 15 min, then every 30 min, and again at every app start. Marks older than 7 days are dropped.
 * - The mark uses the token of the profile that was active when it was made (stored with it), so a later profile switch
 *   can't send it as someone else, and it never touches another profile.
 * - Every attempt is written to the playback log: "mark: scrobble <what> (<ratingKey>) for <profile> [why] -> HTTP 200 in 12 ms".
 * - Manual marks (hold-OK, chips) also tell Tower's watch ledger (POST /watch-event, port 8480), so a manual "unwatched" is
 *   never put back by the restore and a manual "watched" is in the ledger at once. Queued and retried the same way.
 */
object MarkQueue {
    private const val PREFS = "markq"
    private const val MAX_AGE_MS = 7L * 24 * 3600_000
    private const val TOWER_EVENT_URL = "http://192.168.1.8:8480/watch-event"
    private val BACKOFF_S = longArrayOf(20, 60, 300, 900, 1800)

    /** Bumped after every successful mark: Home, Continue Watching, Next Up and grids reload from it. */
    var version by mutableIntStateOf(0)
        private set

    private class Entry(
        val id: String,
        /** "plex" = scrobble/unscrobble, "tower" = ledger event. */
        val kind: String,
        val base: String,
        val token: String,
        val user: String,
        val rk: String,
        val watched: Boolean,
        val label: String,
        val why: String,
        val ts: Long,
        var tries: Int,
    ) {
        fun json() = JSONObject().put("id", id).put("kind", kind).put("base", base).put("token", token).put("user", user)
            .put("rk", rk).put("watched", watched).put("label", label).put("why", why).put("ts", ts).put("tries", tries)
    }

    private fun entry(o: JSONObject) = Entry(
        o.optString("id"), o.optString("kind", "plex"), o.optString("base"), o.optString("token"), o.optString("user"),
        o.optString("rk"), o.optBoolean("watched"), o.optString("label"), o.optString("why"), o.optLong("ts"), o.optInt("tries"),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var app: Context? = null
    private var clientId = ""
    private var retryJob: Job? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** App start: remember the context and send whatever is still waiting from before (crash, update, TV off). */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        clientId = Prefs(ctx.applicationContext).clientId
        val n = load().size
        if (n > 0) log("$n mark(s) waiting from before, sending now")
        kick()
    }

    fun pending(): Int = load().size

    private fun log(msg: String) { app?.let { PlayLog.add(it, "mark: $msg") } }

    private fun sp() = app!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): MutableList<Entry> {
        val c = app ?: return mutableListOf()
        return runCatching {
            val a = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("jobs", "[]"))
            (0 until a.length()).map { entry(a.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(list: List<Entry>) {
        val a = JSONArray(); list.forEach { a.put(it.json()) }
        sp().edit().putString("jobs", a.toString()).commit()
    }

    /**
     * Queue a mark and try it right away. Returns true when Plex took it on this first try (false = queued for retry).
     * [manual] = a hand mark (also sent to Tower's ledger).
     */
    suspend fun mark(api: PlexApi, prefs: Prefs, rk: String, watched: Boolean, label: String, why: String, manual: Boolean): Boolean {
        if (app == null || rk.isBlank()) return false
        val user = prefs.userName ?: "?"
        val now = System.currentTimeMillis()
        val plex = Entry(UUID.randomUUID().toString(), "plex", api.base, api.srvToken, user, rk, watched, label, why, now, 0)
        val tower = if (manual) Entry(UUID.randomUUID().toString(), "tower", "", "", user, rk, watched, label, why, now, 0) else null
        lock.withLock {
            // a newer mark of the same item for the same profile replaces an older one still waiting
            val list = load().filterNot { it.rk == rk && it.user == user && (it.kind == "plex" || (tower != null && it.kind == "tower")) }.toMutableList()
            list += plex
            tower?.let { list += it }
            save(list)
        }
        val ok = withContext(Dispatchers.IO) { attempt(plex) }
        tower?.let { t -> scope.launch { attempt(t) } }
        if (!ok) kick(BACKOFF_S[0])
        return ok
    }

    /** One try of [e]; removes it from the queue when done. True = done. */
    private suspend fun attempt(e: Entry): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val action = if (e.watched) "scrobble" else "unscrobble"
        val what = "${e.label} (${e.rk}) for ${e.user} [${e.why}]"
        val code: Int = try {
            val req = if (e.kind == "plex") {
                Request.Builder().url("${e.base}/:/$action?key=${e.rk}&identifier=com.plexapp.plugins.library")
                    .header("Accept", "application/json")
                    .header("X-Plex-Client-Identifier", clientId)
                    .header("X-Plex-Product", "Starsky TV")
                    .header("X-Plex-Device-Name", "Starsky TV")
                    .header("X-Plex-Token", e.token)
                    .build()
            } else {
                val q = "user=${enc(e.user)}&rk=${e.rk}&action=${if (e.watched) "watched" else "unwatched"}&ts=${e.ts / 1000}"
                Request.Builder().url("$TOWER_EVENT_URL?$q").post(ByteArray(0).toRequestBody(null)).header("User-Agent", "Starsky TV").build()
            }
            http.newCall(req).execute().use { it.code }
        } catch (ex: Exception) {
            log("${if (e.kind == "plex") action else "ledger $action"} $what -> failed after ${SystemClock.elapsedRealtime() - t0} ms: ${ex.javaClass.simpleName} ${ex.message?.take(80) ?: ""} (try ${e.tries + 1}, kept for retry)")
            bump(e); return false
        }
        val ms = SystemClock.elapsedRealtime() - t0
        val done = code in 200..299 || code == 404
        log("${if (e.kind == "plex") action else "ledger $action"} $what -> HTTP $code in $ms ms (try ${e.tries + 1})${if (done) "" else ", kept for retry"}${if (code == 404) ", item gone: dropped" else ""}")
        if (done) {
            lock.withLock { save(load().filterNot { it.id == e.id }) }
            if (e.kind == "plex" && code != 404) withContext(Dispatchers.Main) { version++ }
        } else {
            bump(e)
        }
        return done
    }

    private suspend fun bump(e: Entry) = lock.withLock {
        save(load().map { if (it.id == e.id) it.also { x -> x.tries++ } else it })
    }

    /** Send everything waiting now (after [afterS] seconds), oldest first; reschedules itself while something is left. */
    private fun kick(afterS: Long = 0) {
        if (app == null) return
        retryJob?.cancel()
        retryJob = scope.launch {
            if (afterS > 0) delay(afterS * 1000)
            val now = System.currentTimeMillis()
            val list = lock.withLock {
                val all = load()
                val old = all.filter { now - it.ts > MAX_AGE_MS }
                old.forEach { log("gave up after 7 days: ${if (it.watched) "scrobble" else "unscrobble"} ${it.label} (${it.rk}) for ${it.user}") }
                if (old.isNotEmpty()) save(all - old.toSet())
                all - old.toSet()
            }
            var minTries = Int.MAX_VALUE
            for (e in list.sortedBy { it.ts }) {
                if (e.tries == 0 && now - e.ts < 15_000) { minTries = minOf(minTries, 1); continue } // its first try is running
                if (!attempt(e)) minTries = minOf(minTries, e.tries + 1)
            }
            if (minTries != Int.MAX_VALUE) kick(BACKOFF_S[minOf(minTries, BACKOFF_S.size - 1)])
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** Short on-screen confirmation after a hand mark. */
fun markToast(ctx: Context, ok: Boolean, what: String, watched: Boolean) {
    val msg = if (ok) "$what marked ${if (watched) "watched" else "unwatched"}"
    else "Plex didn't answer: $what will be marked ${if (watched) "watched" else "unwatched"} as soon as it does"
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
