package io.github.odlaver.tepi

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("tepi", Context.MODE_PRIVATE)

    fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun set(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    fun int(key: String, def: Int) = sp.getInt(key, def)
    fun set(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    fun str(key: String, def: String) = sp.getString(key, def) ?: def
    fun set(key: String, v: String) = sp.edit().putString(key, v).apply()

    // Master switch: island and every feature
    var enabled: Boolean
        get() = bool("enabled", true)
        set(v) = set("enabled", v)

    var music: Boolean
        get() = bool("music", true)
        set(v) = set("music", v)

    var lyrics: Boolean
        get() = bool("lyrics", true)
        set(v) = set("lyrics", v)

    var lyricsOnline: Boolean
        get() = bool("lyrics.online", true)
        set(v) = set("lyrics.online", v)

    var notifs: Boolean
        get() = bool("notifs", true)
        set(v) = set("notifs", v)

    var notifContent: Boolean
        get() = bool("notifs.content", true)
        set(v) = set("notifs.content", v)

    // Tap the camera to open Home
    var cameraTap: Boolean
        get() = bool("camera.tap", true)
        set(v) = set("camera.tap", v)

    // Island shift in dp, down positive
    var offsetDp: Int
        get() = int("offset", 0)
        set(v) = set("offset", v)

    // Resting capsule when nothing is happening
    var idleBar: Boolean
        get() = bool("idle.bar", true)
        set(v) = set("idle.bar", v)

    // Width of the resting capsule in dp
    var idleWidthDp: Int
        get() = int("idle.width", IDLE_WIDTH_DEFAULT)
        set(v) = set("idle.width", v)

    // Tab bar in an open panel: pinned up, or shown on a page change and
    // stepped back after a few seconds. Automatic by default.
    var navAlways: Boolean
        get() = bool("nav.always", false)
        set(v) = set("nav.always", v)

    // Landscape notch: the rounded block with one eye in it, on the camera
    // edge, for when the island itself has nowhere to be. On by default,
    // because the alternative is a phone that looks like Tepi is off.
    var notchBar: Boolean
        get() = bool("notch.bar", true)
        set(v) = set("notch.bar", v)

    // What a tap on the notch does: open the island's menu, or only make the
    // eye smile. Off by default: the notch is up during games and videos, and
    // a menu that can be opened by a stray thumb there is a menu in the way.
    var notchOpens: Boolean
        get() = bool("notch.opens", false)
        set(v) = set("notch.opens", v)

    companion object {
        const val OFFSET_MIN = -8
        const val OFFSET_MAX = 24

        // Around the width the island takes during a call
        const val IDLE_WIDTH_DEFAULT = 150
        const val IDLE_WIDTH_MIN = 90
        const val IDLE_WIDTH_MAX = 260
    }
}
