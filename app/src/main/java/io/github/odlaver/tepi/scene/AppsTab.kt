package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.Pinned
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Shortcuts
import kotlin.math.ceil
import kotlin.math.min

// The pinned apps, as a grid of icons.
//
// Five across and up to two down, icons only. A label under each would be
// three characters and an ellipsis at this width, and would say less than the
// icon above it does — app icons are the one kind of picture people are
// genuinely faster at than text, which is why launchers have drawn them that
// way for fifteen years.
//
// A tap opens the app and closes the island. Nothing is held open afterwards:
// the point of a shortcut is to be gone by the time the app is up.
class AppsTab(host: Host, private val apps: Shortcuts, private val pick: () -> Unit) : TabScene(host) {
    override val id = "apps"
    // What somebody keeps on their island says something about them
    override val personal = true
    override val priority = 7
    override val order = 11

    private val cell = RectF()

    // An empty grid is still worth a page the first time: it is where the
    // "choose some" button lives, and a tab that only appeared once the
    // feature had been set up would be a feature nobody could find
    override fun tab() = true

    private val gap get() = ui.s8

    // Square cells, as wide as five of them plus their gaps fit
    private fun side(w: Float) = min(ui.dp(CELL_MAX), (w - gap * (Shortcuts.COLS - 1)) / Shortcuts.COLS)

    private fun rows(n: Int) = min(Shortcuts.ROWS, ceil(n / Shortcuts.COLS.toFloat()).toInt()).coerceAtLeast(1)

    override fun panelHeight(w: Float): Float {
        val head = ui.dp(18f) + ui.s12
        val list = apps.list
        if (list.isEmpty()) return head + ui.dp(36f) + ui.s12 + ui.btnH
        val r = rows(list.size)
        return head + r * side(w) + (r - 1) * gap + ui.s12 + ui.btnH
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val list = apps.list
        var y = head(c, r, Icon.Grid, host.ctx.getString(R.string.tab_apps),
            if (list.isNotEmpty()) "${list.size}/${Shortcuts.MAX}" else null, a)
        if (list.isEmpty()) {
            empty(c, r, y, host.ctx.getString(R.string.apps_pin_empty), a)
            actions(c, r, y + ui.dp(36f) + ui.s12, listOf(
                Action(host.ctx.getString(R.string.apps_pin_choose), true, Icon.Plus) { pick(); host.collapse() },
            ), a)
            return
        }
        val s = side(r.width())
        val rowCount = rows(list.size)
        // Centred in the panel rather than packed left: a row of two icons
        // hard against the left edge of a wide panel reads as a rendering
        // accident, where two in the middle reads as two
        for ((i, app) in list.withIndex()) {
            val row = i / Shortcuts.COLS
            if (row >= rowCount) break
            val col = i % Shortcuts.COLS
            // Each row is centred on its own count, so a second row of three
            // sits under the middle of the five above it
            val inRow = min(Shortcuts.COLS, list.size - row * Shortcuts.COLS)
            val thisW = inRow * s + (inRow - 1) * gap
            val x0 = r.left + (r.width() - thisW) / 2
            val top = y + row * (s + gap)
            cell.set(x0 + col * (s + gap), top, x0 + col * (s + gap) + s, top + s)
            icon(c, cell, app, a)
            hits.add(RectF(cell) to {
                apps.launch(app)?.let { host.open(it) }
                host.collapse()
            })
        }
        y += rowCount * s + (rowCount - 1) * gap + ui.s12
        actions(c, r, y, listOf(
            Action(host.ctx.getString(R.string.apps_pin_edit), false, Icon.Gear) { pick(); host.collapse() },
        ), a)
    }

    // One icon on its own rounded tile. The tile is there for the apps whose
    // icon is a transparent glyph rather than a filled square: without it they
    // float in the panel with nothing to stand on, and the grid stops reading
    // as a grid.
    private fun icon(c: Canvas, at: RectF, app: Pinned, a: Float) {
        ui.fill(c, at, at.width() * TILE_R, ui.fill, a)
        val bmp = app.icon
        if (bmp == null) {
            ui.icon(c, Icon.Layers, at.centerX(), at.centerY(), at.width() * 0.5f, ui.sub, a)
            return
        }
        // Inset, so the icon's own shape shows against the tile instead of
        // filling it corner to corner
        cellInner.set(at)
        cellInner.inset(at.width() * INSET, at.height() * INSET)
        ui.image(c, bmp, cellInner, a)
    }

    private val cellInner = RectF()

    override fun describe(): String =
        host.ctx.getString(R.string.tab_apps) + ": " + apps.list.size

    companion object {
        // A cell never larger than this, so a wide panel gets five sensible
        // icons rather than five enormous ones
        private const val CELL_MAX = 54f
        // Corner radius as a share of the cell, and how far the icon is inset
        // inside it
        private const val TILE_R = 0.26f
        private const val INSET = 0.12f
    }
}
