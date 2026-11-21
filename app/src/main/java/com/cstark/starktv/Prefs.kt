package com.cstark.starktv

import android.content.Context
import java.util.UUID

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

    var weatherName: String
        get() = sp.getString("wname", "Columbia City") ?: "Columbia City"
        set(v) { sp.edit().putString("wname", v).apply() }

    fun signOut() {
        val cid = clientId
        val style = introStyle
        val subs = subsDefault
        val cel = celsius
        val daily = introDaily
        sp.edit().clear().putString("cid", cid).putInt("intro", style).putBoolean("subs", subs).putBoolean("celsius", cel).putBoolean("introDaily", daily).apply()
    }
}
