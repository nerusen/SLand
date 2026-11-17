package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Spring
import kotlin.math.min
import kotlin.math.roundToInt

// Volume, while the buttons are being pressed.
//
// Two tiers, using the same shape the lyric bar uses: beside the camera a
// speaker and the figure, and under the status bar a track with the mute mark
// at one end and the full mark at the other. The figure is there because a
// line alone cannot tell 60% from 70%, and the end marks light up at 0 and at
// full, which is the feedback that says the button has nothing left to give.
//
// The track's width is fixed, not measured from the value: a width that grew
// with the level would make the island pulse on every press, right where the
// eye is resting.
//
// The track is also draggable, so the bar the buttons put up is the slider as
// well: a press to call it, a scrub to land on the level wanted. The bar is
// held open for as long as a finger is on it, because a slider that vanished
// mid-drag would be worse than no slider at all.
class VolumeScene(
    host: Host,
    // Steps of the stream being shown, and the setter for it. Both read the
    // live stream rather than a copy, so a drag moves whatever the buttons
    // were moving.
    private val steps: () -> Int = { 0 },
    private val setLevel: (Int) -> Unit = {},
) : Scene(host) {
    override val id = "volume"
    override val priority = 93

    private val main = Handler(Looper.getMainLooper())
    private var shown = false
    private var level = 0
    private var max = 1
    private var muted = false
    private var stream = AudioManager.STREAM_MUSIC
    // The fill slides to the new value instead of jumping between steps
    private val fill = Spring(0f, 0.002f, 420f, 34f)
    private val tmp = RectF()
    // Where the track was last drawn, and whether a finger is on it
    private val track = RectF()
    private var dragging = false
    // Last step handed to AudioManager during a drag
    private var sent = -1

    private val drop = Runnable { shown = false; host.refresh() }

    fun show(now: Int, ceiling: Int, silent: Boolean, which: Int) {
        level = now
        max = ceiling.coerceAtLeast(1)
        muted = silent
        stream = which
        if (!shown) fill.snap(frac())
        // Mid-drag the fill belongs to the finger. This is also the call the
        // drag itself causes, through the setter and the broadcast that
        // follows it, so letting it retarget here would pull the fill back to
        // the nearest step on every step of a scrub.
        if (!dragging) fill.target = frac()
        shown = true
        main.removeCallbacks(drop)
        // A finger on the track holds the bar up; the timer is armed again
        // when it lifts
        if (!dragging) main.postDelayed(drop, SHOW_MS)
        host.refresh()
    }

    private fun frac() = (level.toFloat() / max).coerceIn(0f, 1f)

    override fun live() = shown
    override fun dismiss() { shown = false; dragging = false; main.removeCallbacks(drop) }
    // Not a page: it is gone long before anyone could swipe to it
    override fun tab() = false
    override fun tapCompact() = true

    override fun step(dt: Float): Boolean {
        // Mid-drag the fill is set by the finger, in apply; retargeting it
        // from the stream here would fight the scrub
        if (!dragging) fill.target = frac()
        return fill.step(dt)
    }

    // Speaker and the figure beside the lens
    override fun wing() = ui.compact.measureText("100%") + ui.dp(26f)

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val s = min(left.height(), ui.dp(20f))
        ui.icon(c, if (muted) Icon.Mute else Icon.Volume, left.left + s / 2, left.centerY(), s, ui.ink, a)
        // Mid-drag the figure is read off the track rather than the stream, so
        // it lands on the step under the thumb instead of trailing the
        // broadcast that confirms it
        val k = if (dragging) fill.target.coerceIn(0f, 1f) else frac()
        val pct = (k * 100).roundToInt()
        ui.textEnd(c, host.ctx.getString(R.string.percent, pct), ui.compact, right.right, ui.baseline(ui.compact, right.centerY()), a)
    }

    // Room for the track: one width for every level, so the island holds still
    override fun rowWidth() = if (shown) ui.dp(240f) else 0f

    // Second in line: the speaker alone, in the small circle beside the shape
    override fun drawBubble(c: Canvas, r: RectF, a: Float) =
        ui.icon(c, if (muted) Icon.Mute else Icon.Volume, r.centerX(), r.centerY(), r.width() * 0.5f, ui.ink, a)

    override fun drawRow(c: Canvas, r: RectF, a: Float) {
        val icon = ui.dp(16f)
        val gap = ui.s12
        val left = r.left + ui.s16
        val right = r.right - ui.s16
        // Both ends go bright only at their own extreme, so the marks
        // themselves report "nothing left" without a word of text
        ui.icon(c, Icon.Mute, left + icon / 2, r.centerY(), icon, if (frac() <= 0f) ui.ink else ui.faint, a)
        ui.icon(c, Icon.Volume, right - icon / 2, r.centerY(), icon, if (frac() >= 1f) ui.ink else ui.faint, a)

        val barL = left + icon + gap
        val barR = right - icon - gap
        if (barR <= barL) { track.setEmpty(); return }
        // A drag goes for the thin track, so the hit area is grown to a
        // thumb's worth of row rather than the four dp that are drawn
        track.set(barL, r.top, barR, r.bottom)
        // Thicker under the finger: the only cue a flat track can give that
        // it is being held rather than merely watched
        val h = if (dragging) ui.dp(7f) else ui.dp(4f)
        val rad = h / 2
        // Grey behind, white in front: the empty part of the track still
        // reads as track rather than as island
        tmp.set(barL, r.centerY() - h / 2, barR, r.centerY() + h / 2)
        ui.fill(c, tmp, rad, ui.fillStrong, a)
        val k = fill.value.coerceIn(0f, 1f)
        if (k <= 0f) return
        // Keep a visible sliver, so a nearly silent phone still shows where
        // the fill starts
        tmp.right = barL + (barR - barL) * k
        if (tmp.right - tmp.left < h) tmp.right = tmp.left + h
        ui.fill(c, tmp, rad, ui.ink, a)
    }

    // The bar is the slider: a finger anywhere along the track takes the
    // gesture, and the level follows it from that first touch rather than
    // waiting for a move, so a tap partway along jumps straight there.
    override fun grab(x: Float, y: Float): Boolean {
        if (!shown || track.isEmpty || !track.contains(x, y)) return false
        if (steps() <= 0) return false
        dragging = true
        sent = level
        main.removeCallbacks(drop)
        host.haptic()
        apply(x)
        return true
    }

    override fun drag(x: Float, y: Float) {
        if (!dragging) return
        apply(x)
    }

    override fun release() {
        if (!dragging) return
        dragging = false
        // Back to a passing notice now the finger is gone
        main.removeCallbacks(drop)
        main.postDelayed(drop, SHOW_MS)
        host.refresh()
    }

    // Finger position onto a step of the stream.
    //
    // AudioManager is told once per step, not once per frame: a scrub across a
    // fifteen-step media stream is fifteen calls, not three hundred. The
    // comparison is against what was last sent rather than against the level
    // read back, because the read-back arrives by broadcast and a fast drag
    // would otherwise send the same step several times while it catches up.
    private fun apply(x: Float) {
        val w = track.width()
        if (w <= 0f) return
        val top = steps()
        if (top <= 0) return
        val want = ((x - track.left) / w).coerceIn(0f, 1f)
        val stepped = Math.round(want * top)
        // Snapped, not raw: the fill shows the step the stream is actually on,
        // so a coarse five-step ringer looks like the five steps it has
        fill.target = stepped.toFloat() / top
        if (stepped != sent) {
            sent = stepped
            host.haptic()
            setLevel(stepped)
        }
        host.refresh()
    }

    override fun panelHeight(w: Float) = 0f
    override fun drawPanel(c: Canvas, r: RectF, a: Float) {}

    override fun describe(): String {
        val what = host.ctx.getString(when (stream) {
            AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION -> R.string.vol_ring
            AudioManager.STREAM_VOICE_CALL -> R.string.vol_call
            AudioManager.STREAM_ALARM -> R.string.vol_alarm
            else -> R.string.vol_media
        })
        return "$what ${host.ctx.getString(R.string.percent, (frac() * 100).roundToInt())}"
    }

    companion object {
        private const val SHOW_MS = 1800L
    }
}
