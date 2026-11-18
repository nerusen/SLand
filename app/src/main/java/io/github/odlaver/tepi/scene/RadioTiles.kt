package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.Access
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.Quick
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.RadioState
import io.github.odlaver.tepi.Radios

// Four tiles at the bottom of Home, in two rows.
//
// Wi-Fi and Bluetooth were the first pair: lit when the radio is on, dark when
// it is off, the same way the system's own quick tiles read at a glance. A tap
// asks the system to turn the radio on; a press and hold opens that radio's
// settings page, which is why Home takes the long press back from the island's
// quick menu while a finger is on a tile.
//
// Mobile data and the lock screen are the second pair, and they are deliberately
// in the same grid rather than somewhere of their own: all four are "the state
// of the phone, changed from here", and a user reaching for the data switch is
// reaching to the same place they reach for Wi-Fi.
//
// The two new ones are not symmetrical with the old ones underneath, though
// they look it. Mobile data can always be read and usually cannot be written,
// so the tile shows the truth and falls through to the system page when the
// write is refused. The lock screen can always be written when the
// accessibility service is on and never otherwise, so it says which.
class RadioTiles(private val host: Host, private val radios: Radios) : HomeRow {
    private val ui get() = host.ui
    private val wifiBox = RectF()
    private val btBox = RectF()
    private val dataBox = RectF()
    private val lockBox = RectF()

    // Personal: the lock screen has the system's own toggles above it, and the
    // network name is not for a locked phone to show
    override fun personal() = true
    override fun visible() = true

    // Two rows where the phone has a cellular radio, one where it does not: a
    // tablet with no SIM slot is offered neither a data tile nor a lonely lock
    // tile beside an empty half.
    private fun second() = Quick.hasData(host.ctx)

    override fun height(w: Float) = if (second()) ui.dp(64f) * 2 + ui.s8 else ui.dp(64f)

    // The tiles are their own cards; no row card behind them
    override fun bare() = true

    override fun draw(c: Canvas, r: RectF, a: Float) {
        val gap = ui.s8
        val w = (r.width() - gap) / 2
        val h = ui.dp(64f)
        wifiBox.set(r.left, r.top, r.left + w, r.top + h)
        btBox.set(r.right - w, r.top, r.right, r.top + h)
        tile(c, wifiBox, Icon.Wifi, radios.wifi(), host.ctx.getString(R.string.wifi), a)
        tile(c, btBox, Icon.Bluetooth, radios.bluetooth(), host.ctx.getString(R.string.bluetooth), a)
        if (!second()) { dataBox.setEmpty(); lockBox.setEmpty(); return }
        val y = r.top + h + gap
        dataBox.set(r.left, y, r.left + w, y + h)
        lockBox.set(r.right - w, y, r.right, y + h)
        // Mobile data reads like a radio, because it is one
        val on = Quick.dataOn(host.ctx)
        tile(c, dataBox, Icon.Signal, RadioState(on, null), host.ctx.getString(R.string.q_data), a,
            under = host.ctx.getString(if (on) R.string.q_on else R.string.q_off))
        // The lock tile is an action, not a state: it is never lit, because
        // there is nothing for it to be lit about. A tile that glowed would be
        // claiming the screen is locked, which it plainly is not if this is
        // being looked at.
        val can = Quick.canLock()
        tile(c, lockBox, Icon.Lock, RadioState(false, null), host.ctx.getString(R.string.q_lock), a,
            under = host.ctx.getString(if (can) R.string.q_lock_sub else R.string.q_lock_need))
    }

    // One column. The label is the connection when there is one, and the
    // radio's own name when it is on but joined to nothing, so the tile never
    // goes blank.
    private fun tile(c: Canvas, box: RectF, icon: Icon, s: RadioState, label: String, a: Float, under: String? = null) {
        ui.fill(c, box, ui.radiusRow, if (s.on) ui.lead else ui.fill, a)
        val fg = if (s.on) ui.onLead else ui.sub
        val size = ui.dp(20f)
        val cx = box.left + ui.s12 + size / 2
        ui.icon(c, icon, cx, box.top + ui.dp(20f), size, fg, a)
        val tx = box.left + ui.s12
        val room = box.width() - ui.s12 * 2
        val title = s.name ?: label
        val pct = s.battery
        val sub = under ?: pct?.let { host.ctx.getString(R.string.percent, it) }
        // The name is ellipsized to the column, so a long network reads as
        // "Long network na…" rather than running past the island's edge
        ui.text(c, title, if (s.on) ui.labelDark else ui.label, tx, box.top + ui.dp(if (sub != null) 42f else 47f), room, a)
        // Earbud charge, or the state line: the figure only, since the capsule
        // above already carries the phone's own battery mark
        if (sub != null) ui.text(c, sub, if (s.on) ui.bodyDark else ui.body, tx, box.top + ui.dp(57f), room, a)
    }

    override fun tap(x: Float, y: Float): Boolean {
        if (wifiBox.contains(x, y)) {
            host.haptic()
            host.open(Radios.wifiToggle())
            host.collapse()
            return true
        }
        if (btBox.contains(x, y)) {
            host.haptic()
            host.open(Radios.btToggle(radios.bluetooth().on, radios.canBt))
            host.collapse()
            return true
        }
        if (dataBox.contains(x, y)) {
            host.haptic()
            // The flip first; the page only when the flip was refused, which
            // on most builds is always. Collapsing either way: a tile whose
            // state is about to change is not a tile worth staring at.
            val want = !Quick.dataOn(host.ctx)
            if (Quick.setData(host.ctx, want)) {
                host.refresh()
                return true
            }
            host.open(Quick.networkSettings())
            host.collapse()
            return true
        }
        if (lockBox.contains(x, y)) {
            host.haptic()
            if (Quick.lock()) {
                // The screen is going off; there is nothing left to show
                host.collapse()
                return true
            }
            // No accessibility service, so there is no lock to perform. Its
            // settings page is the only useful answer.
            Access.openSettings(host.ctx)
            host.collapse()
            return true
        }
        return false
    }

    // Long press: straight to that radio's own settings page
    override fun hold(x: Float, y: Float): Boolean {
        val page = when {
            wifiBox.contains(x, y) -> Radios.wifiSettings()
            btBox.contains(x, y) -> Radios.btSettings()
            // Hold on the data tile is the mobile network page, as asked: the
            // tap tries the switch, the hold goes where the switch lives
            dataBox.contains(x, y) -> Quick.networkSettings()
            lockBox.contains(x, y) -> { Access.openSettings(host.ctx); host.collapse(); return true }
            else -> return false
        }
        host.open(page)
        host.collapse()
        return true
    }
}
