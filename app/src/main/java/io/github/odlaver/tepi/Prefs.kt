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

    // Landscape notch: the rounded block with one eye in it, hanging from the
    // middle of the top edge, for when the island itself has nowhere to be. On
    // by default, because the alternative is a phone that looks like Tepi is
    // off.
    var notchBar: Boolean
        get() = bool("notch.bar", true)
        set(v) = set("notch.bar", v)

    // What a tap on the notch does: open the island's menu, or only make the
    // eye smile. Off by default: the notch is up during games and videos, and
    // a menu that can be opened by a stray thumb there is a menu in the way.
    var notchOpens: Boolean
        get() = bool("notch.opens", false)
        set(v) = set("notch.opens", v)

    // Size of the notch itself, as a percentage of the drawn default. Both the
    // depth and the length scale together, so the silhouette keeps its
    // proportions at every setting.
    var notchSize: Int
        get() = int("notch.size", NOTCH_SIZE_DEFAULT)
        set(v) = set("notch.size", v)

    // Size of the eye inside it, as a percentage of its default height against
    // the block's depth. Separate from the notch's own size on purpose: the
    // two wants are different, one is how much of the screen is given up and
    // the other is how much of that block is face.
    var notchEye: Int
        get() = int("notch.eye", NOTCH_EYE_DEFAULT)
        set(v) = set("notch.eye", v)

    companion object {
        const val OFFSET_MIN = -8
        const val OFFSET_MAX = 24

        // Around the width the island takes during a call
        const val IDLE_WIDTH_DEFAULT = 150
        const val IDLE_WIDTH_MIN = 90
        const val IDLE_WIDTH_MAX = 260

        // Notch size, per cent of the default 26 x 100 dp block.
        //
        // The floor is where the shape is still legibly a notch with an eye in
        // it rather than a dark smudge: at 70 the block is 18 dp deep, and the
        // eye inside it at its own smallest setting is about 6 dp tall, which
        // is a blink that can still be seen. The ceiling is where it stops
        // being a notch and starts being a bar someone has to look past: at
        // 150 it is 39 dp deep and 150 dp long, already deeper than a status
        // bar. Anything past that is covering the game it is supposed to sit
        // quietly on top of, and the screen-fraction caps in Notch would start
        // clipping it on smaller phones anyway.
        const val NOTCH_SIZE_DEFAULT = 100
        const val NOTCH_SIZE_MIN = 70
        const val NOTCH_SIZE_MAX = 150

        // Eye size, per cent of its default height within the block.
        //
        // The floor is a dot that still blinks: at 60 in the smallest notch
        // the eye is about 5 dp tall, and its blink has a dp floor of its own
        // so it cannot thin out to nothing. The ceiling is set by the room the
        // shape needs rather than by taste — a startled eye grows a third
        // again, and the crescents reach further than the oval — so Notch
        // holds the drawn height to a fraction of the depth whatever is asked
        // for. At 120 the eye is exactly against that ceiling, so the slider
        // stops there instead of pretending to do more.
        const val NOTCH_EYE_DEFAULT = 100
        const val NOTCH_EYE_MIN = 60
        const val NOTCH_EYE_MAX = 120
    }
}
