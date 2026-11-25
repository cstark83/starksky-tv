package com.cstark.starktv

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import java.util.UUID

/** "Resume rewind" choices in seconds (Settings); resuming starts this much before the saved Plex position. */
val RESUME_REWIND_CHOICES = listOf(15, 30, 45, 60)
fun rewindLabel(s: Int) = if (s == 60) "1 minute" else "$s seconds"
/** Where a resume really starts: savedMs minus the rewind, never below 0. A fresh play (savedMs 0) stays at 0. */
fun resumeStartMs(savedMs: Long, rewindS: Int): Long = if (savedMs <= 0L) 0L else (savedMs - rewindS * 1000L).coerceAtLeast(0L)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("stark", Context.MODE_PRIVATE)

    val clientId: String
        get() = sp.getString("cid", null)
            ?: UUID.randomUUID().toString().also { sp.edit().putString("cid", it).apply() }

    var accountToken: String?
        get() = sp.getString("acct", null)
        set(v) { sp.edit().putString("acct", v).apply() }

    var userToken: String?
        get() = sp.getString("utoken", null)
        set(v) { sp.edit().putString("utoken", v).apply() }

    var serverToken: String?
        get() = sp.getString("stoken", null)
        set(v) { sp.edit().putString("stoken", v).apply() }

    var userName: String?
        get() = sp.getString("uname", null)
        set(v) { sp.edit().putString("uname", v).apply() }

    var serverName: String?
        get() = sp.getString("sname", null)
        set(v) { sp.edit().putString("sname", v).apply() }

    var lastBase: String?
        get() = sp.getString("lastbase", null)
        set(v) { sp.edit().putString("lastbase", v).apply() }

    var candidates: List<String>
        get() = (sp.getString("cands", "") ?: "").split("\n").filter { it.isNotBlank() }
        set(v) { sp.edit().putString("cands", v.joinToString("\n")).apply() }

    var subsDefault: Boolean
        get() = sp.getBoolean("subs", false)
        set(v) { sp.edit().putBoolean("subs", v).apply() }

    var introStyle: Int
        get() = sp.getInt("intro", 0)
        set(v) { sp.edit().putInt("intro", v).apply() }

    /** false = the opening plays on every launch, true = only the first launch each day. */
    var introDaily: Boolean
        get() = sp.getBoolean("introDaily", false)
        set(v) { sp.edit().putBoolean("introDaily", v).apply() }

    /** Resume rewind in seconds (15/30/45/60, default 60). Device-wide: same for every profile, kept on sign-out. */
    var resumeRewind: Int
        get() = sp.getInt("resumeRewind", 60).takeIf { it in RESUME_REWIND_CHOICES } ?: 60
        set(v) { sp.edit().putInt("resumeRewind", v).apply() }

    var lastIntroDay: Long
        get() = sp.getLong("introDay", -1L)
        set(v) { sp.edit().putLong("introDay", v).apply() }

    var celsius: Boolean
        get() = sp.getBoolean("celsius", false)
        set(v) { sp.edit().putBoolean("celsius", v).apply() }

    /** Weather location. Defaults to Columbia City, Indiana. */
    var weatherLat: Double
        get() = sp.getFloat("wlat", 41.157f).toDouble()
        set(v) { sp.edit().putFloat("wlat", v.toFloat()).apply() }

    var weatherLon: Double
        get() = sp.getFloat("wlon", -85.488f).toDouble()
        set(v) { sp.edit().putFloat("wlon", v.toFloat()).apply() }

    // Kept in Compose state too, so the Settings line updates the moment a sync finishes.
    private val lastSyncState = mutableLongStateOf(sp.getLong("lastsync", 0L))

    /** When the home data last loaded successfully from Plex (ms since epoch, 0 = never). */
    var lastSync: Long
        get() = lastSyncState.longValue
        set(v) { lastSyncState.longValue = v; sp.edit().putLong("lastsync", v).apply() }

    /** "time · title · ERROR_CODE · message" of the last playback failure, shown in Settings. */
    var lastPlayError: String?
        get() = sp.getString("playerr", null)
        set(v) { sp.edit().putString("playerr", v).apply() }

    /** "time · result" of the last update check (automatic or manual), shown in Settings. */
    var lastUpdateCheck: String?
        get() = sp.getString("updchk", null)
        set(v) { sp.edit().putString("updchk", v).apply() }

    // Audio / subtitle choice per show, per profile: "a=<lang>;t=off" or "a=<lang>;t=<lang>,f=<forced 0|1>,s=<SDH 0|1>".
    private fun trkKey(show: String) = "trk:${userName ?: "default"}:$show"
    fun trackChoice(show: String): String? = sp.getString(trkKey(show), null)
    fun setTrackChoice(show: String, v: String) { sp.edit().putString(trkKey(show), v).apply() }
    /** Forget every saved track choice (all shows, all profiles). Returns how many were removed. */
    fun forgetTracks(): Int {
        val keys = sp.all.keys.filter { it.startsWith("trk:") }
        sp.edit().apply { keys.forEach { remove(it) } }.apply()
        return keys.size
    }

    /** Kid profile (Plex Home user with a restrictionProfile, e.g. "older_kid"): no Discover, no requests. */
    private val restrictedState = androidx.compose.runtime.mutableStateOf(sp.getBoolean("kid", false))
    var restricted: Boolean
        get() = restrictedState.value
        set(v) { restrictedState.value = v; sp.edit().putBoolean("kid", v).apply() }

    /** "time · thread · Exception: message" + first stack lines of the last crash (Settings), null = none. */
    var lastCrash: String?
        get() = sp.getString("crash", null)
        set(v) { sp.edit().putString("crash", v).apply() }

    /** Build number seen on the last start (0 = never started), for the "Updated to build N" label. */
    var lastBuild: Int
        get() = sp.getInt("lastbuild", 0)
        set(v) { sp.edit().putInt("lastbuild", v).apply() }

    /** Last Open-Meteo answer (raw JSON) and when it was fetched, so the weather shows at once after a restart. */
    var weatherCache: String?
        get() = sp.getString("wcache", null)
        set(v) { sp.edit().putString("wcache", v).apply() }

    var weatherCacheAt: Long
        get() = sp.getLong("wcacheAt", 0L)
        set(v) { sp.edit().putLong("wcacheAt", v).apply() }

    var weatherName: String
        get() = sp.getString("wname", "Columbia City") ?: "Columbia City"
        set(v) { sp.edit().putString("wname", v).apply() }

    fun signOut() {
        val cid = clientId
        val style = introStyle
        val subs = subsDefault
        val cel = celsius
        val daily = introDaily
        val build = lastBuild
        val rewind = resumeRewind
        lastSyncState.longValue = 0L
        restrictedState.value = false
        sp.edit().clear().putString("cid", cid).putInt("intro", style).putBoolean("subs", subs).putBoolean("celsius", cel).putBoolean("introDaily", daily).putInt("lastbuild", build).putInt("resumeRewind", rewind).apply()
    }
}
