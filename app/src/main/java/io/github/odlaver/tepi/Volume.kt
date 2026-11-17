package io.github.odlaver.tepi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.SystemClock

// Volume of the stream the buttons are actually moving.
//
// Android has no public callback for a volume change, but it does broadcast
// one: VOLUME_CHANGED_ACTION carries the stream and its new value. The action
// is not in the SDK, so it is named by string; a build that does not send it
// leaves the bar out rather than showing a stale figure. The current value is
// always read back from AudioManager, so the bar matches the system slider
// exactly instead of tracking its own count.
class Volume(private val context: Context, private val prefs: Prefs, private val moved: (Int, Int, Boolean) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var started = false
    private var readyAt = 0L

    // Which stream the buttons last moved, as named by the broadcast. Android
    // decides this itself: music while something is playing, the ringer
    // otherwise, so the bar follows whatever the buttons are actually on.
    private var stream = AudioManager.STREAM_MUSIC

    private val watch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!started || !prefs.bool(KEY, true)) return
            // The very first broadcasts after registering are the system
            // catching the app up, not the user pressing anything
            if (SystemClock.uptimeMillis() < readyAt) return
            val s = i.getIntExtra(EXTRA_STREAM, -1)
            if (s !in SHOWN) return
            stream = s
            report()
        }
    }

    // Level and ceiling of the live stream, plus whether sound is muted
    private fun report() {
        val a = audio ?: return
        val max = try { a.getStreamMaxVolume(stream) } catch (_: Exception) { 0 }
        if (max <= 0) return
        val now = try { a.getStreamVolume(stream) } catch (_: Exception) { 0 }
        val silent = stream != AudioManager.STREAM_MUSIC && a.ringerMode != AudioManager.RINGER_MODE_NORMAL
        moved(now, max, silent || now == 0)
    }

    // Which stream a given level belongs to, for the label
    val kind get() = stream

    fun start() {
        if (started) return
        started = true
        readyAt = SystemClock.uptimeMillis() + 1200
        try {
            context.registerReceiver(watch, IntentFilter(ACTION_VOLUME_CHANGED), Context.RECEIVER_EXPORTED)
        } catch (_: Exception) { started = false }
    }

    fun stop() {
        if (!started) return
        started = false
        try { context.unregisterReceiver(watch) } catch (_: Exception) { }
    }

    companion object {
        const val KEY = "act.volume"

        // AudioManager.VOLUME_CHANGED_ACTION and its extras are hidden API
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        // Streams worth a bar: what the buttons move in practice
        private val SHOWN = setOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_ALARM,
        )
    }
}
