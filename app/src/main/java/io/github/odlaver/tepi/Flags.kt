package io.github.odlaver.tepi

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

// The six system switches the island reports as a line of text
enum class Flag { Dnd, Silent, Torch, Hotspot, Airplane, Saver }

// System modes the user flips from the shade: do not disturb, silent, the
// torch, the hotspot, airplane mode, battery saver.
//
// Each one is read from its own source and reported in both directions, on and
// off, because a notice that only ever says "on" leaves the user guessing
// whether the second press worked. Everything is debounced and the first
// second and a half is swallowed: reading the initial state of six switches at
// once would otherwise spill six lines into the island the moment the service
// starts.
class Flags(private val context: Context, private val prefs: Prefs, private val flipped: (Flag, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val notif = context.getSystemService(NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var started = false
    private var readyAt = 0L

    // Last reported position of each switch, so only real changes are news
    private val state = HashMap<Flag, Boolean>()

    // Airplane mode turns the radios off itself, and the shade's hotspot and
    // Wi-Fi tiles follow it a moment later. Those are consequences, not
    // choices, so they are held back for a few seconds. Silent is held back
    // after do not disturb for the same reason: one press, one line.
    private var planeAt = 0L
    private var dndAt = 0L

    private fun on(f: Flag): Boolean = when (f) {
        Flag.Dnd -> try { notif.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL } catch (_: Exception) { false }
        Flag.Silent -> try { audio.ringerMode != AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) { false }
        Flag.Torch -> torch
        Flag.Hotspot -> hotspot()
        Flag.Airplane -> try { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 } catch (_: Exception) { false }
        Flag.Saver -> try { power?.isPowerSaveMode == true } catch (_: Exception) { false }
    }

    private fun key(f: Flag) = when (f) {
        Flag.Dnd -> "act.dnd"
        Flag.Silent -> "act.ringer"
        Flag.Torch -> "act.torch"
        Flag.Hotspot -> "act.hotspot"
        Flag.Airplane -> "act.plane"
        Flag.Saver -> "act.saver"
    }

    private fun look(f: Flag) {
        if (!started) return
        val now = on(f)
        val was = state[f]
        state[f] = now
        if (was == now) return
        val t = SystemClock.uptimeMillis()
        // The causes are stamped even when their own line is switched off or
        // still inside the quiet opening window, because what they suppress is
        // the radios they drag along with them, not their own notice
        if (f == Flag.Airplane) planeAt = t
        if (f == Flag.Dnd) dndAt = t
        if (t < readyAt) return
        if (!prefs.bool(key(f), true)) return
        // Knock-on effects of a switch the user has already been told about:
        // airplane mode takes the radios down with it, and do not disturb
        // silences the ringer on most builds. One press, one line.
        if (f == Flag.Hotspot && t - planeAt < CASCADE_MS) return
        if (f == Flag.Silent && (t - dndAt < QUIET_MS || t - planeAt < CASCADE_MS)) return
        flipped(f, now)
    }

    // One pass over everything: cheap, and it keeps the switches that have no
    // broadcast of their own honest.
    //
    // Causes before effects, which is why this is a list and not Flag.entries:
    // airplane mode has to be seen before the hotspot it just turned off, or
    // the suppression above would be reading a timestamp from the press that
    // has not been looked at yet.
    private val sweep = Runnable { for (f in ORDER) look(f) }

    private fun poke() {
        main.removeCallbacks(sweep)
        main.postDelayed(sweep, 250)
    }

    private val watch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Waking up is not a flip. A switch that moved while the phone
            // was asleep, battery saver coming on by itself at a low level
            // being the usual one, is not something the user just did, so the
            // positions are taken again in silence instead of announced.
            if (i.action == Intent.ACTION_SCREEN_ON) { reseed(); return }
            poke()
        }
    }

    // Adopt the current positions without reporting any of them
    private fun reseed() {
        for (f in Flag.entries) state[f] = on(f)
        readyAt = SystemClock.uptimeMillis() + 1200
    }

    // The torch has a real callback, so it is not swept for
    private var torch = false
    private val torches = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            // Only the camera that actually has a flash; the others report too
            if (!flashOf(id)) return
            torch = enabled
            poke()
        }
        override fun onTorchModeUnavailable(id: String) {
            if (!flashOf(id)) return
            torch = false
            poke()
        }
    }

    private val flashIds = HashSet<String>()

    private fun flashOf(id: String): Boolean {
        if (id in flashIds) return true
        return try {
            val has = camera.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (has) flashIds.add(id)
            has
        } catch (_: Exception) { false }
    }

    // Tethering has no public read. The state broadcast and the getter behind
    // it are both hidden API, so this is reflection with a broadcast fallback,
    // and a phone that refuses both simply never shows the hotspot line.
    @Volatile private var apOn = false

    private fun hotspot(): Boolean {
        try {
            val m = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true
            (m.invoke(wifi) as? Boolean)?.let { return it }
        } catch (_: Throwable) { }
        return apOn
    }

    private val apWatch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // WIFI_AP_STATE_ENABLED is 13, mirroring WIFI_STATE_ENABLED + 10
            apOn = i.getIntExtra(EXTRA_AP_STATE, -1) == AP_ENABLED
            poke()
        }
    }

    fun start() {
        if (started) return
        started = true
        readyAt = SystemClock.uptimeMillis() + 1500
        val f = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(ACTION_AIRPLANE)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            // A switch may move while the phone sleeps; waking is where the
            // new positions are adopted without announcing any of them
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try { context.registerReceiver(watch, f, Context.RECEIVER_EXPORTED) } catch (_: Exception) { }
        try { context.registerReceiver(apWatch, IntentFilter(ACTION_AP_STATE), Context.RECEIVER_EXPORTED) } catch (_: Exception) { }
        try { camera.registerTorchCallback(torches, main) } catch (_: Exception) { }
        // Seed every switch at its current position, quietly
        for (fl in Flag.entries) state[fl] = on(fl)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(sweep)
        try { context.unregisterReceiver(watch) } catch (_: Exception) { }
        try { context.unregisterReceiver(apWatch) } catch (_: Exception) { }
        try { camera.unregisterTorchCallback(torches) } catch (_: Exception) { }
        state.clear()
    }

    companion object {
        // Both are hidden API, named by string
        private const val ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"
        private const val AP_ENABLED = 13

        // Deprecated for apps to send, still the signal Android broadcasts
        private const val ACTION_AIRPLANE = Intent.ACTION_AIRPLANE_MODE_CHANGED

        // A radio going quiet because airplane mode went on is the same press
        private const val CASCADE_MS = 4000L
        // Silent following do not disturb is the same press too
        private const val QUIET_MS = 1500L

        // Causes first, so a sweep stamps airplane mode and do not disturb
        // before it reaches the switches they move on their own
        private val ORDER = listOf(Flag.Airplane, Flag.Dnd, Flag.Silent, Flag.Hotspot, Flag.Torch, Flag.Saver)

        // Where each line's switch lives, for the tap. The torch has no page
        // of its own, so its line is a notice only and never opens anything.
        fun settings(f: Flag): Intent = when (f) {
            Flag.Dnd -> Intent(ACTION_ZEN_SETTINGS)
            Flag.Silent -> Intent(Settings.ACTION_SOUND_SETTINGS)
            Flag.Torch -> Intent(Settings.ACTION_SETTINGS)
            Flag.Hotspot -> Intent(ACTION_HOTSPOT_SETTINGS)
            Flag.Airplane -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            Flag.Saver -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }

        // Neither of these is in the SDK as a constant: the do not disturb
        // page and the tethering page are both named by string. A phone that
        // does not answer them simply does not open anything, which is what
        // the tap already falls back to.
        private const val ACTION_ZEN_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
        private const val ACTION_HOTSPOT_SETTINGS = "android.settings.TETHER_SETTINGS"
    }
}
