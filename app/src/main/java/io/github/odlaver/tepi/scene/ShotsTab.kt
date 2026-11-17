package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.Capture
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import kotlin.math.ceil
import kotlin.math.min

// The last few screenshots, as a grid of thumbnails.
//
// The screenshot card is a notice: it comes up on its own, says what just
// happened, and leaves. This is the other half of the same idea, a place to go
// looking rather than something that arrives: the captures from the last little
// while, two rows of them, each one a tap away from the gallery. It is what
// saves the trip through a photo app when the picture wanted is simply the one
// taken a minute ago.
//
// Cells are small portraits rather than a few big ones. A screenshot is a
// picture of a phone screen, so a cell with a phone's own proportions shows the
// top of it instead of a square cut out of the middle; and a thumbnail that is
// only recognisable, not readable, is the right size here, because the thing
// being chosen is "the one with the map in it", not the text on it.
//
// Thumbnails come from MediaStore's own loadThumbnail, built on a background
// thread by Capture and handed over as a list. The tab asks for a refresh while
// it draws and shows whatever it was last given, so opening the page never
// waits on the disk.
class ShotsTab(host: Host, private val capture: Capture, private val all: () -> Unit) : TabScene(host) {
    override val id = "shots"
    // Pictures of whatever was on screen: not for the lock screen
    override val personal = true
    override val priority = 9
    override val order = 9

    // Which capture the action row below is about. Held as a uri, so it
    // survives the list being built again in the background.
    private var picked: android.net.Uri? = null
    private val cell = RectF()
    private val grid = RectF()

    // Shown only once the gallery read has been granted and the feature is on;
    // otherwise the tab would be a permanent empty page
    override fun tab() = capture.tabOn()

    private val gap get() = ui.s4
    private val cellH get() = ui.dp(CELL_H)
    private val cellW get() = cellH * SHAPE

    // As many cells as fit the panel, within reason: a very wide panel should
    // not turn two rows into a contact sheet
    private fun cols(w: Float) = (((w + gap) / (cellW + gap)).toInt()).coerceIn(3, MAX_COLS)

    private fun list(w: Float) = capture.shots.take(cols(w) * ROWS)

    private fun rows(n: Int, cols: Int) = min(ROWS, ceil(n / cols.toFloat()).toInt()).coerceAtLeast(1)

    override fun panelHeight(w: Float): Float {
        val head = ui.dp(18f) + ui.s12
        val n = list(w).size
        if (n == 0) return head + ui.dp(36f) + ui.s12 + ui.btnH
        val rows = rows(n, cols(w))
        return head + rows * cellH + (rows - 1) * gap + ui.s12 + ui.btnH
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        // Asking here rather than on open: the tab is also reached by a swipe
        // and by the icon row, and all of them end in a draw
        capture.refresh { host.refresh() }
        val shots = list(r.width())
        var y = head(c, r, Icon.Image, host.ctx.getString(R.string.tab_shots),
            if (shots.isNotEmpty()) shots.size.toString() else null, a)
        if (shots.isEmpty()) {
            empty(c, r, y, host.ctx.getString(R.string.shots_empty), a)
            actions(c, r, y + ui.dp(36f) + ui.s12, listOf(
                Action(host.ctx.getString(R.string.gallery), false, Icon.Open) { all(); host.collapse() },
            ), a)
            return
        }
        // A capture that has since been deleted must not keep the action row
        val sel = shots.firstOrNull { it.uri == picked } ?: shots.first()
        val cols = cols(r.width())
        val rows = rows(shots.size, cols)
        grid.set(r.left, y, r.right, y + rows * cellH + (rows - 1) * gap)
        val radius = ui.dp(8f)
        for ((i, s) in shots.withIndex()) {
            val row = i / cols
            if (row >= rows) break
            val left = r.left + (i % cols) * (cellW + gap)
            val top = y + row * (cellH + gap)
            cell.set(left, top, left + cellW, top + cellH)
            if (s.thumb != null) ui.art(c, s.thumb, cell, a, radius / cellW)
            else {
                ui.fill(c, cell, radius, ui.fill, a)
                ui.icon(c, Icon.Image, cell.centerX(), cell.centerY(), ui.dp(16f), ui.sub, a)
            }
            // The one the buttons below act on is outlined, so a grid of
            // look-alike captures still says which is which
            if (s.uri == sel.uri) outline(c, cell, radius, a)
            hits.add(RectF(cell) to {
                // First tap picks, so the buttons name a picture; a second tap
                // on the same cell is the one that opens it
                if (s.uri == picked) {
                    host.open(Capture.open(s))
                    host.collapse()
                } else picked = s.uri
            })
        }
        y = grid.bottom + ui.s12

        actions(c, r, y, listOf(
            Action(host.ctx.getString(R.string.open), true, Icon.Open) { host.open(Capture.open(sel)); host.collapse() },
            Action(host.ctx.getString(R.string.share), false, Icon.Share) { host.open(Capture.share(sel)); host.collapse() },
        ), a)
    }

    private fun outline(c: Canvas, r: RectF, radius: Float, a: Float) {
        ui.stroke.strokeWidth = ui.dp(1.5f)
        ui.stroke.color = ui.alpha(ui.ink, a)
        c.drawRoundRect(r, radius, radius, ui.stroke)
    }

    override fun describe(): String {
        val n = capture.shots.size
        if (n == 0) return host.ctx.getString(R.string.shots_empty)
        return host.ctx.getString(R.string.tab_shots) + ": " + n
    }

    companion object {
        // Two rows of small portraits: tall enough to recognise, short enough
        // that the panel is still a panel
        private const val ROWS = 2
        private const val MAX_COLS = 6
        private const val CELL_H = 68f
        // Cell width over its height, roughly a phone screen's shape
        private const val SHAPE = 0.56f
    }
}
