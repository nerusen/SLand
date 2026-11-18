package io.github.odlaver.tepi

import android.database.ContentObserver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

// Screen brightness, watched for the one thing worth saying about it.
//
// A phone turned most of the way up indoors is a lamp held at reading
// distance, and the usual reason it is there is that it was turned up outside
// in daylight and never turned back down. Nobody notices doing that, which is
// exactly why a notice is worth anything at all: it is not information the
// user lacks, it is a thing they have stopped seeing.
//
// Said once per trip over the line, never again until the brightness comes
// back down. A warning that repeated every time the slider twitched above the
// threshold would be the warning the user learns to swipe away without
// reading, which is worse than no warning: it teaches the island's cards to be
// ignored.
//
// Read through Settings.System, which needs no permission to read — only to
// write, and nothing here writes. Automatic brightness is accounted for: the
// value that matters is the one actually on screen, so the adjustment the
// system is applying is read too where the build exposes it.
class Glare(private val context: Context, private val prefs: Prefs, private val bright: (Int) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var readyAt = 0L

    // Whether the level is currently over the line. The notice fires on the
    // crossing, not on the state, so this is what makes it once per trip.
    private var over = false
    private var saidAt = 0L

    private val watch = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!started) return
            // A drag on the slider is a stream of changes; one look after the
            // finger stops is the whole of it
            main.removeCallbacks(look)
            main.postDelayed(look, 400)
        }
    }

    private val look = Runnable { check() }

    private fun check() {
        if (!started || !prefs.glareWarn) return
        val pct = percent() ?: return
        val line = prefs.glareAt.coerceIn(Prefs.GLARE_MIN, Prefs.GLARE_MAX)
        // Hysteresis, so a level sitting exactly on the line does not fire
        // every time the system nudges it by one. Coming back down has to go
        // properly under before the next trip counts.
        if (!over && pct >= line) {
            over = true
            val now = SystemClock.uptimeMillis()
            if (now < readyAt) return
            if (now - saidAt < QUIET_MS) return
            saidAt = now
            bright(pct)
            return
        }
        if (over && pct <= line - HYSTERESIS) over = false
    }

    // What is actually on screen, as a percentage.
    //
    // SCREEN_BRIGHTNESS is in the range the panel's own driver uses, which is
    // 0..255 on almost every build but is not promised to be: the real maximum
    // lives in a framework resource an app cannot read. 255 is assumed and a
    // value above it is clamped, which is the behaviour every brightness
    // widget on the platform has.
    private fun percent(): Int? {
        val raw = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        } catch (_: Exception) { -1 }
        if (raw < 0) return null
        return (raw * 100 / MAX).coerceIn(0, 100)
    }

    fun start() {
        if (started) return
        started = true
        readyAt = SystemClock.uptimeMillis() + 2500
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, watch,
            )
            // The mode matters too: switching automatic brightness off lands
            // the panel wherever the manual value was left
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, watch,
            )
        } catch (_: Exception) { started = false; return }
        // Adopt the current position quietly: a phone that was already bright
        // when the service started was not just turned up
        over = (percent() ?: 0) >= prefs.glareAt
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(look)
        try { context.contentResolver.unregisterContentObserver(watch) } catch (_: Exception) { }
    }

    companion object {
        // The driver range every Android build in practice uses
        private const val MAX = 255

        // How far back down counts as having come down
        private const val HYSTERESIS = 6

        // And the floor on how often this may be said at all, however much
        // the slider is played with
        private const val QUIET_MS = 10 * 60_000L
    }
}
