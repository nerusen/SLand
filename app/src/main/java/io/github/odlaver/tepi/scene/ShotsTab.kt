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

    // Cells fill the panel's width rather than being a fixed size.
    //
    // They used to be a fixed 68 dp tall at a phone's own aspect, and as many
    // as happened to fit went in a row — which on most panels came out at
    // four and a half, so four were drawn and the last cell's worth of width
    // was left blank down the right-hand side. A grid that does not reach the
    // edge of the thing it is in reads as a mistake, and it was.
    //
    // So the column count is chosen first, four across, and the cells are
    // sized to fit it exactly: eight recent captures and a row that reaches
    // both edges.
    //
    // The height does not simply follow from that width, though, and this is
    // the part that is worth saying out loud. A quarter of a panel is around
    // 76 dp wide, and a cell that tall-and-thin at a phone's real proportions
    // would be 136 dp high — two rows of that is a panel taller than most of
    // the pages in the island put together, which is how a picker turns into a
    // page. So the height is capped, and what gives is the crop: the cell ends
    // up a little wider than a phone screen and the thumbnail is centre-cropped
    // to it, losing a sliver off the top and bottom. That is the right thing to
    // lose. What a screenshot is recognised by is its middle — the map, the
    // chat, the receipt — and the parts being trimmed are the status bar and
    // the navigation bar, which are identical in every capture and so carry no
    // information at all.
    private fun cols() = COLS

    private fun cellW(w: Float) = (w - gap * (cols() - 1)) / cols()

    // A phone's own shape where there is room for it, and no taller than the
    // cap when there is not
    private fun cellH(w: Float) = min(ui.dp(CELL_MAX), cellW(w) / SHAPE)

    private fun list(w: Float) = capture.shots.take(cols() * ROWS)

    private fun rows(n: Int) = min(ROWS, ceil(n / cols().toFloat()).toInt()).coerceAtLeast(1)

    override fun panelHeight(w: Float): Float {
        val head = ui.dp(18f) + ui.s12
        val n = list(w).size
        if (n == 0) return head + ui.dp(36f) + ui.s12 + ui.btnH
        val rows = rows(n)
        val ch = cellH(w)
        return head + rows * ch + (rows - 1) * gap + ui.s12 + ui.btnH
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
        val cols = cols()
        val rows = rows(shots.size)
        val cw = cellW(r.width())
        val ch = cellH(r.width())
        grid.set(r.left, y, r.right, y + rows * ch + (rows - 1) * gap)
        val radius = ui.dp(8f)
        for ((i, s) in shots.withIndex()) {
            val row = i / cols
            if (row >= rows) break
            val left = r.left + (i % cols) * (cw + gap)
            val top = y + row * (ch + gap)
            cell.set(left, top, left + cw, top + ch)
            if (s.thumb != null) ui.art(c, s.thumb, cell, a, radius / cw)
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
        // Four across, two down: eight captures, and the row reaches both
        // edges of the panel instead of stopping a cell short of the right.
        // Four rather than five because a screenshot narrower than this stops
        // being recognisable as the screen it came off; four rather than three
        // because three at this width are large enough to be a gallery rather
        // than a picker.
        private const val COLS = 4
        private const val ROWS = 2
        // Cell width over its height, roughly a phone screen's shape. The
        // cells are sized from the panel now, so this sets how tall they want
        // to be rather than how wide.
        private const val SHAPE = 0.56f
        // And how tall they are actually allowed to get. Two rows of this plus
        // the heading and the button row come out around 215 dp, which is the
        // same order as the music page and the agenda — a picker that is one
        // of the island's pages rather than a page of its own.
        private const val CELL_MAX = 76f
    }
}
