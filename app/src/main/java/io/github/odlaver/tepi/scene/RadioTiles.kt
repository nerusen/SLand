package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.RadioState
import io.github.odlaver.tepi.Radios

// Wi-Fi and Bluetooth side by side at the bottom of Home.
//
// Lit when the radio is on, dark when it is off, the same way the system's own
// quick tiles read at a glance. A tap asks the system to turn the radio on; a
// press and hold opens that radio's settings page, which is why Home takes the
// long press back from the island's quick menu while a finger is on a tile.
class RadioTiles(private val host: Host, private val radios: Radios) : HomeRow {
    private val ui get() = host.ui
    private val left = RectF()
    private val right = RectF()

    // Personal: the lock screen has the system's own toggles above it, and the
    // network name is not for a locked phone to show
    override fun personal() = true
    override fun visible() = true
    override fun height(w: Float) = ui.dp(64f)

    // The tiles are their own cards; no row card behind them
    override fun bare() = true

    override fun draw(c: Canvas, r: RectF, a: Float) {
        val gap = ui.s8
        val w = (r.width() - gap) / 2
        left.set(r.left, r.top, r.left + w, r.bottom)
        right.set(r.right - w, r.top, r.right, r.bottom)
        tile(c, left, Icon.Wifi, radios.wifi(), host.ctx.getString(R.string.wifi), a)
        tile(c, right, Icon.Bluetooth, radios.bluetooth(), host.ctx.getString(R.string.bluetooth), a)
    }

    // One column. The label is the connection when there is one, and the
    // radio's own name when it is on but joined to nothing, so the tile never
    // goes blank.
    private fun tile(c: Canvas, box: RectF, icon: Icon, s: RadioState, label: String, a: Float) {
        ui.fill(c, box, ui.radiusRow, if (s.on) ui.ink else ui.fill, a)
        val fg = if (s.on) Color.BLACK else ui.sub
        val size = ui.dp(20f)
        val cx = box.left + ui.s12 + size / 2
        ui.icon(c, icon, cx, box.top + ui.dp(20f), size, fg, a)
        val tx = box.left + ui.s12
        val room = box.width() - ui.s12 * 2
        val title = s.name ?: label
        val pct = s.battery
        // The name is ellipsized to the column, so a long network reads as
        // "Long network na…" rather than running past the island's edge
        ui.text(c, title, if (s.on) ui.labelDark else ui.label, tx, box.top + ui.dp(if (pct != null) 42f else 47f), room, a)
        // Earbud charge, under the name: the figure only, since the capsule
        // above already carries the phone's own battery mark
        if (pct != null) ui.text(c, host.ctx.getString(R.string.percent, pct), if (s.on) ui.bodyDark else ui.body, tx, box.top + ui.dp(57f), room, a)
    }

    override fun tap(x: Float, y: Float): Boolean {
        val wifi = left.contains(x, y)
        if (!wifi && !right.contains(x, y)) return false
        host.haptic()
        host.open(if (wifi) Radios.wifiToggle() else Radios.btToggle(radios.bluetooth().on, radios.canBt))
        host.collapse()
        return true
    }

    // Long press: straight to that radio's own settings page
    override fun hold(x: Float, y: Float): Boolean {
        val wifi = left.contains(x, y)
        if (!wifi && !right.contains(x, y)) return false
        host.open(if (wifi) Radios.wifiSettings() else Radios.btSettings())
        host.collapse()
        return true
    }
}
