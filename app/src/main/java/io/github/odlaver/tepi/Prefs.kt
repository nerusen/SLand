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

    var islandShadow: Boolean
        get() = bool("island.shadow", true)
        set(v) = set("island.shadow", v)

    var shadowPreset: String
        get() = str("island.shadow.preset", "soft")
        set(v) = set("island.shadow.preset",
            if (v in setOf("soft", "balanced", "strong", "custom")) v else "soft")

    var shadowOpacity: Int
        get() = int("island.shadow.opacity", SHADOW_OPACITY_SOFT).coerceIn(SHADOW_OPACITY_MIN, SHADOW_OPACITY_MAX)
        set(v) = set("island.shadow.opacity", v.coerceIn(SHADOW_OPACITY_MIN, SHADOW_OPACITY_MAX))

    var logoMode: String
        get() = str("idle.logo", "author")
        set(v) = set("idle.logo", if (v == "none") "none" else "author")

    var greetingEnabled: Boolean
        get() = bool("greeting.enabled", false)
        set(v) = set("greeting.enabled", v)

    var greetingName: String
        get() = str("greeting.name", "")
        set(v) = set("greeting.name", v.trim().take(GREETING_NAME_MAX))

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

    // Whether a tab is offered in the island's navigation at all.
    //
    // Per tab rather than one list, so a tab added in a later version arrives
    // switched on instead of missing: the default is yes and only the ones the
    // user has turned off are written down. Home is not asked about — it is
    // the page everything else is reached from, and an island whose navigation
    // is empty is an island with nothing behind the swipe.
    fun tabOn(id: String) = id == "home" || bool(tabKey(id), DEFAULT_NAV_TABS.contains(id))

    fun activeTabCount(): Int = DEFAULT_NAV_IDS.count { tabOn(it) }

    fun setTab(id: String, on: Boolean): Boolean {
        if (id == "home") return true
        if (on && !tabOn(id) && activeTabCount() >= MAX_NAV_TABS) return false
        set(tabKey(id), on)
        return true
    }

    // Which notice may put itself up without being asked. The island's live
    // scenes are the ones that arrive on their own, and this is the user
    // saying they would rather they did not; the tab switch above is about the
    // swipe, which is a different want.
    fun liveOn(id: String) = bool(liveKey(id), true)

    fun setLive(id: String, on: Boolean) = set(liveKey(id), on)

    // Walking distance and the activity block under the map
    var steps: Boolean
        get() = bool("maps.steps", true)
        set(v) = set("maps.steps", v)

    // Height in cm and weight in kg, for the stride the step count is turned
    // into metres with and the calories that come out of it. Zero means not
    // said, and the defaults below are used instead.
    var heightCm: Int
        get() = int("body.height", HEIGHT_DEFAULT)
        set(v) = set("body.height", v)

    var weightKg: Int
        get() = int("body.weight", WEIGHT_DEFAULT)
        set(v) = set("body.weight", v)

    // Brightness warning: the level it fires at, and whether it fires at all
    var glareWarn: Boolean
        get() = bool("glare.warn", true)
        set(v) = set("glare.warn", v)

    var glareAt: Int
        get() = int("glare.at", GLARE_DEFAULT)
        set(v) = set("glare.at", v)

    // The eyes sway while music plays
    var eyeSway: Boolean
        get() = bool("eye.sway", true)
        set(v) = set("eye.sway", v)

    // Wallet balance privacy: the Finance tab keeps the user's choice locally.
    var financeBalanceHidden: Boolean
        get() = bool("finance.balance.hidden", false)
        set(v) = set("finance.balance.hidden", v)

    companion object {
        private fun tabKey(id: String) = "tab.$id"
        private fun liveKey(id: String) = "live.$id"

        const val OFFSET_MIN = -8
        const val OFFSET_MAX = 24

        // Around the width the island takes during a call
        const val IDLE_WIDTH_DEFAULT = 150
        const val IDLE_WIDTH_MIN = 90
        const val IDLE_WIDTH_MAX = 260

        const val GREETING_NAME_MAX = 24

        const val SHADOW_OPACITY_MIN = 0
        const val SHADOW_OPACITY_MAX = 100
        const val SHADOW_OPACITY_SOFT = 25
        const val SHADOW_OPACITY_BALANCED = 50
        const val SHADOW_OPACITY_STRONG = 75

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

        // Body, for the stride and the calorie figure. An average adult, so a
        // user who never opens the settings still gets a distance within about
        // a tenth rather than nothing at all.
        const val HEIGHT_DEFAULT = 168
        const val HEIGHT_MIN = 120
        const val HEIGHT_MAX = 210
        const val WEIGHT_DEFAULT = 65
        const val WEIGHT_MIN = 30
        const val WEIGHT_MAX = 160

        // Where the brightness warning fires, as a percentage of the system's
        // own range.
        //
        // Eighty by default, because that is about where a phone indoors stops
        // being comfortable and starts being a lamp. The floor is sixty: below
        // that the warning would fire during ordinary daylight use and become
        // the thing the user learns to ignore. The ceiling is a hundred, which
        // is "only at the very top" rather than off — the switch is for off.
        const val GLARE_DEFAULT = 80
        const val GLARE_MIN = 60
        const val GLARE_MAX = 100

        const val MAX_NAV_TABS = 7
        val DEFAULT_NAV_TABS = setOf("music", "timer", "prayer", "notes", "shelf", "shots", "maps")
        val DEFAULT_NAV_IDS = listOf(
            "music", "timer", "agenda", "prayer", "agents", "notes", "shelf", "github",
            "shots", "maps", "apps", "finance", "kanban", "assistant",
        )
    }
}
