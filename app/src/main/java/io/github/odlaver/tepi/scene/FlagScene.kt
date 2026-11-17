package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.odlaver.tepi.Flag
import io.github.odlaver.tepi.Flags
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Spring

// A system switch, said in one line.
//
// This is the lyric bar's shape: the closed island, a strip under the status
// bar, one line of text in the middle of it. The mark sits inline with the
// words rather than out beside the camera, so the whole notice is a single
// centred object and the neck stays empty as it does when nothing is playing.
//
// Lines queue instead of overwriting: flipping airplane mode and the torch
// within a second should read as two notices, not as one that flickered. The
// text crossfades and slides between them, so a second line replaces the
// first the same way one lyric replaces another.
class FlagScene(host: Host) : Scene(host) {
    override val id = "flag"
    override val priority = 93

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Pair<Flag, Boolean>>()
    // Widest line of the set, so the strip does not resize as lines change
    private var widest = 0f
    private var prev = ""
    private val swap = Spring(1f, 0.004f, 260f, 32f)

    private val drop = Runnable { next() }

    fun show(f: Flag, on: Boolean) {
        val first = queue.firstOrNull()
        if (first != null && first.first == f) {
            // The switch on screen moved again: say the new position in place
            // and give it the full time over, rather than sliding a line out
            // only to slide the same switch back in
            queue[0] = f to on
            arm()
            measure()
            host.refresh()
            return
        }
        // Waiting line for the same switch: it never got shown, so the newer
        // position simply replaces it
        queue.removeAll { it.first == f }
        queue.add(f to on)
        // A line already up keeps its own timer; this one is next in turn
        if (queue.size == 1) {
            prev = ""
            swap.snap(0f)
            swap.target = 1f
            arm()
        }
        measure()
        host.refresh()
    }

    private fun arm() {
        main.removeCallbacks(drop)
        main.postDelayed(drop, SHOW_MS)
    }

    private fun next() {
        if (queue.isEmpty()) return
        prev = line(queue[0])
        queue.removeAt(0)
        if (queue.isNotEmpty()) {
            swap.snap(0f)
            swap.target = 1f
            arm()
        } else prev = ""
        measure()
        host.refresh()
    }

    override fun live() = queue.isNotEmpty()
    override fun dismiss() { queue.clear(); prev = ""; main.removeCallbacks(drop) }
    // Gone in seconds; never a page to swipe to
    override fun tab() = false

    override fun step(dt: Float) = swap.step(dt)

    // Nothing beside the lens: the line carries the whole notice
    override fun wing() = 0f
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    // Second in line: just the switch's mark, in the small circle
    override fun drawBubble(c: Canvas, r: RectF, a: Float) {
        val cur = queue.firstOrNull() ?: return
        ui.icon(c, mark(cur.first, cur.second), r.centerX(), r.centerY(), r.width() * 0.5f, ui.ink, a)
    }

    private val iconS get() = ui.dp(15f)
    private val iconGap get() = ui.dp(7f)

    private fun measure() {
        var w = 0f
        for (q in queue) w = maxOf(w, ui.lyric.measureText(line(q)))
        if (prev.isNotEmpty()) w = maxOf(w, ui.lyric.measureText(prev))
        widest = w
    }

    override fun rowWidth(): Float {
        if (queue.isEmpty()) return 0f
        if (widest <= 0f) measure()
        return widest + iconS + iconGap + ui.dp(40f)
    }

    override fun drawRow(c: Canvas, r: RectF, a: Float) {
        val cur = queue.firstOrNull() ?: return
        val k = swap.value.coerceIn(0f, 1f)
        val shift = ui.dp(10f)
        val room = r.width() - ui.s24
        if (k < 1f && prev.isNotEmpty()) group(c, null, prev, r.centerX(), r.centerY() - shift * k, room, a * (1 - k))
        group(c, mark(cur.first, cur.second), line(cur), r.centerX(), r.centerY() + shift * (1 - k), room, a * k)
    }

    // Mark and words as one centred block, so neither drifts off the middle
    private fun group(c: Canvas, icon: Icon?, text: String, cx: Float, cy: Float, room: Float, a: Float) {
        if (a <= 0.01f) return
        if (icon == null) { ui.centered(c, text, ui.lyric, cx, cy, room, a); return }
        val tw = minOf(ui.lyric.measureText(text), room - iconS - iconGap)
        val total = iconS + iconGap + tw
        val x = cx - total / 2
        ui.icon(c, icon, x + iconS / 2, cy, iconS, ui.ink, a)
        ui.text(c, text, ui.lyric, x + iconS + iconGap, ui.baseline(ui.lyric, cy), tw, a)
    }

    // Tap the line to land on the switch it is about
    override fun tapCompact(): Boolean {
        val cur = queue.firstOrNull() ?: return true
        // The torch has no settings page of its own; the line is just a notice
        if (cur.first == Flag.Torch) return true
        host.open(Flags.settings(cur.first))
        dismiss()
        host.refresh()
        return true
    }

    private fun mark(f: Flag, on: Boolean): Icon = when (f) {
        Flag.Dnd -> Icon.Moon
        Flag.Silent -> if (on) Icon.BellOff else Icon.Bell
        Flag.Torch -> Icon.Flash
        Flag.Hotspot -> Icon.Hotspot
        Flag.Airplane -> Icon.Plane
        Flag.Saver -> Icon.Leaf
    }

    // Both directions get a line: a notice that only ever says "on" leaves
    // the user wondering whether the second press registered
    private fun line(q: Pair<Flag, Boolean>): String {
        val (f, on) = q
        if (f == Flag.Silent) return ringer(on)
        return host.ctx.getString(when (f) {
            Flag.Dnd -> if (on) R.string.f_dnd_on else R.string.f_dnd_off
            Flag.Torch -> if (on) R.string.f_torch_on else R.string.f_torch_off
            Flag.Hotspot -> if (on) R.string.f_hotspot_on else R.string.f_hotspot_off
            Flag.Airplane -> if (on) R.string.f_plane_on else R.string.f_plane_off
            Flag.Saver -> if (on) R.string.f_saver_on else R.string.f_saver_off
            Flag.Silent -> R.string.f_silent_on
        })
    }

    // Silent and vibrate are different enough to name apart, and the ring
    // mode is read at the moment the line is drawn so it matches the phone
    private fun ringer(on: Boolean): String {
        if (!on) return host.ctx.getString(R.string.f_ring_on)
        val mode = try { host.ctx.getSystemService(AudioManager::class.java).ringerMode } catch (_: Exception) { AudioManager.RINGER_MODE_SILENT }
        return host.ctx.getString(if (mode == AudioManager.RINGER_MODE_VIBRATE) R.string.f_vibrate_on else R.string.f_silent_on)
    }

    override fun panelHeight(w: Float) = 0f
    override fun drawPanel(c: Canvas, r: RectF, a: Float) {}

    override fun describe() = queue.firstOrNull()?.let { line(it) } ?: ""

    companion object {
        private const val SHOW_MS = 3000L
    }
}
