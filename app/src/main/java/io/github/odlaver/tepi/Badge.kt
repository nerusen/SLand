package io.github.odlaver.tepi

import android.content.Context
import android.os.SystemClock
import android.telephony.TelephonyManager
import java.io.RandomAccessFile

// What the resting capsule carries where the battery used to be.
//
// The capsule is the island when nothing is happening: a black bar in the
// status bar with the logo at one end and the battery at the other. The
// battery is the obvious thing to put there and it is still the default, but
// it is not the only thing worth a glance, and on a phone that already shows
// its battery in the status bar beside it, it is the one thing that is
// definitely redundant.
//
// Eleven choices, in three families:
//
//   Nothing       none. The slot stays, so the capsule keeps its width and
//                 the logo keeps its place; it simply holds nothing. Not the
//                 same as a narrower capsule, which the width slider is for.
//   The battery   battery, circle, dot. The same reading drawn three ways,
//                 from a full shell down to a single coloured dot.
//   A figure      clock, cpu, ram, provider, text, wifi, bluetooth. A short
//                 string, right-aligned in the slot and ellipsized to it.
//
// Everything read from the system is read on a timer rather than per frame.
// The capsule repaints whenever something asks it to, and /proc/stat is a file
// read: doing that inside onDraw would be disk work in the middle of an
// animation. The cached value is a second or two old at worst, which for a CPU
// percentage is closer to the truth than an instantaneous sample anyway.
enum class Badge {
    None, Battery, Circle, Dot, Clock, Cpu, Ram, Provider, Text, Wifi, Bluetooth;

    // Whether this badge draws a string. The two families are laid out
    // differently: a figure is right-aligned text that may be ellipsized, a
    // mark is a fixed-aspect shape.
    val textual get() = this == Clock || this == Cpu || this == Ram || this == Provider ||
        this == Text || this == Wifi || this == Bluetooth

    companion object {
        const val KEY = "idle.badge"
        // The user's own line, for Badge.Text
        const val TEXT_KEY = "idle.badge.text"
        // A capsule is 150 dp by default and a word has to fit beside the
        // lens, so there is no point letting someone type a paragraph
        const val TEXT_MAX = 24

        fun of(prefs: Prefs): Badge {
            val want = prefs.str(KEY, Battery.name)
            return entries.firstOrNull { it.name == want } ?: Battery
        }

        fun set(prefs: Prefs, b: Badge) = prefs.set(KEY, b.name)

        // Label for the settings page, in the order they are offered
        fun label(b: Badge) = when (b) {
            None -> R.string.badge_none
            Battery -> R.string.badge_battery
            Circle -> R.string.badge_circle
            Dot -> R.string.badge_dot
            Clock -> R.string.badge_clock
            Cpu -> R.string.badge_cpu
            Ram -> R.string.badge_ram
            Provider -> R.string.badge_provider
            Text -> R.string.badge_text
            Wifi -> R.string.badge_wifi
            Bluetooth -> R.string.badge_bluetooth
        }
    }
}

// The readings behind the badges, sampled on a timer.
//
// One holder rather than a method per badge, because the two that cost
// anything — the CPU and the carrier — both have to be remembered between
// samples: a CPU percentage is the difference between two readings of
// /proc/stat, so a single reading says nothing at all.
class Badges(private val context: Context, private val prefs: Prefs) {
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val activity = context.getSystemService(android.app.ActivityManager::class.java)

    // Last /proc/stat sample: busy jiffies and total jiffies
    private var lastBusy = 0L
    private var lastTotal = 0L
    private var cpuPct = -1
    private var cpuAt = 0L

    private var ramPct = -1
    private var ramAt = 0L

    // Wi-Fi and Bluetooth names come from the same place Home's tiles read
    // them, so the capsule and the tile never disagree
    private var radios: Radios? = null

    fun wire(r: Radios) { radios = r }

    // How long the caller may wait before asking again, for whichever badge
    // is up. The clock wants to be right to the minute and the rest change
    // slowly, so this is the badge's own pace rather than one interval for
    // everything.
    fun pace(b: Badge): Long = when (b) {
        Badge.Clock -> 10_000L
        Badge.Cpu, Badge.Ram -> 2_000L
        Badge.Provider, Badge.Wifi, Badge.Bluetooth -> 5_000L
        else -> 0L
    }

    // The string a textual badge shows, or null when there is nothing to say.
    // Null is not an empty string: the caller leaves the slot alone rather
    // than drawing nothing into it, which is what keeps a missing carrier from
    // looking like a bug.
    fun text(b: Badge): String? = when (b) {
        Badge.Clock -> clock()
        Badge.Cpu -> cpu()?.let { context.getString(R.string.badge_cpu_at, it) }
        Badge.Ram -> ram()?.let { context.getString(R.string.badge_ram_at, it) }
        Badge.Provider -> carrier()
        Badge.Text -> prefs.str(Badge.TEXT_KEY, "").trim().take(Badge.TEXT_MAX).takeIf { it.isNotEmpty() }
        Badge.Wifi -> radios?.wifi()?.takeIf { it.on }?.name
        Badge.Bluetooth -> radios?.bluetooth()?.takeIf { it.on }?.name
        else -> null
    }

    // 24-hour, zero-padded, from the system clock. Not the locale's own short
    // format: the capsule is a few characters wide and an AM/PM suffix is a
    // third of them, so the one place in Tepi that spells a time out in full
    // is the one that cannot afford to.
    private fun clock(): String {
        val c = java.util.Calendar.getInstance()
        return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    // Busy per cent across all cores, from /proc/stat.
    //
    // The first line of that file is the total across every core as a running
    // count of jiffies in each state, so the load is the change in the busy
    // states over the change in all of them. Two readings are needed and the
    // first one can only prime the pair, which is why a fresh Badges reports
    // nothing until its second sample.
    //
    // Android 8 locked /proc down for apps, and on most builds since then this
    // file reads as the app's own cgroup or refuses outright. A refusal is not
    // worked around: the badge reports nothing and the slot stays empty, which
    // is honest in a way that a made-up figure would not be.
    private fun cpu(): Int? {
        val now = SystemClock.uptimeMillis()
        if (now - cpuAt < CPU_MS && cpuAt != 0L) return cpuPct.takeIf { it >= 0 }
        cpuAt = now
        val line = try {
            RandomAccessFile("/proc/stat", "r").use { it.readLine() }
        } catch (_: Throwable) { null } ?: return cpuPct.takeIf { it >= 0 }
        if (!line.startsWith("cpu ")) return cpuPct.takeIf { it >= 0 }
        val parts = line.split(" ").filter { it.isNotEmpty() }.drop(1).mapNotNull { it.toLongOrNull() }
        // user nice system idle iowait irq softirq, at least
        if (parts.size < 4) return cpuPct.takeIf { it >= 0 }
        val total = parts.sum()
        // Idle and iowait are the two that are not work
        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
        val busy = total - idle
        val dTotal = total - lastTotal
        val dBusy = busy - lastBusy
        val primed = lastTotal > 0L
        lastTotal = total
        lastBusy = busy
        // The first sample can only prime the pair: on its own it would be the
        // average since the phone booted, not the load now. A counter that
        // went backwards is a suspend, and is treated the same way.
        if (!primed || dTotal <= 0L || dBusy < 0L) return cpuPct.takeIf { it >= 0 }
        cpuPct = ((dBusy * 100) / dTotal).toInt().coerceIn(0, 100)
        return cpuPct
    }

    // Memory in use, as a share of what the phone has.
    //
    // availMem rather than freeMem: what matters to the user is how much is
    // actually available for something new, and Linux counts cache as used
    // even though it would be handed over on demand. This is the same figure
    // the system's own developer screen shows.
    private fun ram(): Int? {
        val now = SystemClock.uptimeMillis()
        if (now - ramAt < RAM_MS && ramAt != 0L) return ramPct.takeIf { it >= 0 }
        ramAt = now
        val info = android.app.ActivityManager.MemoryInfo()
        try { activity?.getMemoryInfo(info) } catch (_: Exception) { return ramPct.takeIf { it >= 0 } }
        if (info.totalMem <= 0L) return ramPct.takeIf { it >= 0 }
        val used = info.totalMem - info.availMem
        ramPct = ((used * 100) / info.totalMem).toInt().coerceIn(0, 100)
        return ramPct
    }

    // The network operator's name. The SIM's own name is the fallback: a phone
    // with no signal still knows whose SIM is in it, and "Telkomsel" with no
    // bars is more use than nothing at all.
    private fun carrier(): String? {
        val t = telephony ?: return null
        val names = listOf(
            try { t.networkOperatorName } catch (_: Exception) { null },
            try { t.simOperatorName } catch (_: Exception) { null },
        )
        for (n in names) {
            val s = n?.trim()
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }

    companion object {
        private const val CPU_MS = 1500L
        private const val RAM_MS = 1500L
    }
}
