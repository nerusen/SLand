package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import io.github.odlaver.tepi.GhDay
import io.github.odlaver.tepi.Github
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// The GitHub contribution calendar, laid out the way the profile page lays it
// out: weeks as columns, Sunday on top, month names along the top edge and
// Mon, Wed, Fri down the side, a Less–More key underneath. GitHub's greens are
// swapped for steps of the island's own ink, so it sits with the rest of the
// island instead of being the one coloured thing on it.
//
// A year is 53 columns and a panel is not as wide as a profile page, so the
// newest weeks that fit at a readable square size are shown, ending today the
// way the profile's calendar does.
class GithubTab(host: Host, private val gh: Github, private val connect: () -> Unit) : TabScene(host) {
    override val id = "github"
    override val personal = true
    override val priority = 11
    override val order = 8

    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val month = SimpleDateFormat("MMM", Locale.getDefault())
    private val longDay = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
    private val span = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private val cal = Calendar.getInstance()
    private val cell = RectF()
    private val grid = RectF()

    // Tapped square, like hovering one on the profile; today until then. Kept
    // as a date, so it survives the calendar being fetched again.
    private var picked: String? = null
    private var shownCols = 0
    private var shownCell = 0f
    private var shownFrom = 0
    private var gridLeft = 0f
    private var gridTop = 0f

    private val gap get() = ui.dp(2f)
    private val minCell get() = ui.dp(8f)
    private val maxCell get() = ui.dp(12f)
    private val monthH get() = ui.dp(14f)

    // Mon, Wed, Fri in the phone's language, worked out once: they are wanted
    // on every frame the tab is drawn
    private val weekdays: List<String> by lazy {
        val f = SimpleDateFormat("EEE", Locale.getDefault())
        val c = Calendar.getInstance()
        listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY).map { c.set(Calendar.DAY_OF_WEEK, it); f.format(c.time) }
    }

    // Widest of the three weekday marks, plus room before the first column
    private fun sideW() = weekdays.maxOf { ui.small.measureText(it) } + ui.dp(6f)

    // Columns and square size for a width: as many weeks as fit at no less
    // than minCell, squares grown up to maxCell when there is room to spare
    private fun fit(w: Float): Pair<Int, Float> {
        val n = gh.weeks.size
        if (n == 0) return 0 to 0f
        val room = w - sideW()
        val cols = min(n, ((room + gap) / (minCell + gap)).toInt()).coerceAtLeast(1)
        val size = min(maxCell, (room + gap) / cols - gap)
        return cols to size
    }

    private fun gridH(size: Float) = size * 7 + gap * 6

    override fun panelHeight(w: Float): Float {
        val head = ui.dp(18f) + ui.s12
        if (!gh.linked) return head + ui.dp(36f) + ui.s12 + ui.btnH
        if (gh.weeks.isEmpty()) return head + ui.dp(36f)
        val (_, size) = fit(w)
        return head + monthH + gridH(size) + ui.s12 + ui.dp(14f) + ui.s8 + ui.dp(14f)
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val end = if (gh.weeks.isNotEmpty()) host.ctx.getString(R.string.github_total, gh.total) else null
        var y = head(c, r, Icon.Github, gh.login.ifEmpty { host.ctx.getString(R.string.github) }, end, a)
        if (!gh.linked) {
            empty(c, r, y, host.ctx.getString(R.string.github_empty), a)
            actions(c, r, y + ui.dp(36f) + ui.s12, listOf(Action(host.ctx.getString(R.string.connect_now), true, Icon.Link) { connect(); host.collapse() }), a)
            return
        }
        if (gh.weeks.isEmpty()) {
            empty(c, r, y, host.ctx.getString(if (gh.failed) R.string.github_failed else R.string.github_loading), a)
            return
        }
        val weeks = gh.weeks
        val (cols, size) = fit(r.width())
        val from = weeks.size - cols
        shownCols = cols; shownCell = size; shownFrom = from
        val side = sideW()
        gridLeft = r.left + side
        gridTop = y + monthH

        months(c, weeks, from, cols, size, gridLeft, y, r.right, a)

        // Mon, Wed, Fri beside rows 1, 3 and 5, as on the profile
        weekdays.forEachIndexed { i, name ->
            val cy = gridTop + (1 + i * 2) * (size + gap) + size / 2
            ui.text(c, name, ui.small, r.left, ui.baseline(ui.small, cy), side, a)
        }

        val radius = size * 0.22f
        for (i in 0 until cols) {
            val x = gridLeft + i * (size + gap)
            for (d in weeks[from + i]) {
                val top = gridTop + d.weekday * (size + gap)
                cell.set(x, top, x + size, top + size)
                ui.fill(c, cell, radius, shade(d.level), a)
                if (d.date == picked) {
                    ui.stroke.strokeWidth = ui.dp(1f)
                    ui.stroke.color = ui.alpha(ui.ink, a)
                    c.drawRoundRect(cell, radius, radius, ui.stroke)
                }
            }
        }
        grid.set(gridLeft, gridTop, gridLeft + cols * (size + gap) - gap, gridTop + gridH(size))
        y = grid.bottom + ui.s12

        // Range on the left, the key on the right, like under the profile's
        val first = weeks[from].firstOrNull()?.date
        val last = weeks.last().lastOrNull()?.date
        val range = listOfNotNull(first, last).mapNotNull { parse(it) }.joinToString(" – ") { span.format(it) }
        legend(c, r, y, a)
        ui.text(c, range, ui.small, r.left, ui.baseline(ui.small, y + ui.dp(7f)), r.width() / 2, a)
        y += ui.dp(14f) + ui.s8

        // The tooltip a hover gives on the profile, for the tapped square
        val day = picked?.let { p -> weeks.asSequence().flatten().firstOrNull { it.date == p } } ?: weeks.last().lastOrNull()
        if (day != null) {
            val date = parse(day.date)?.let { longDay.format(it) } ?: day.date
            ui.text(c, host.ctx.resources.getQuantityString(R.plurals.github_day, day.count, day.count, date), ui.bodyInk, r.left, ui.baseline(ui.bodyInk, y + ui.dp(7f)), r.width(), a)
        }
    }

    // A month's name over the first column that starts in it. Names that
    // would run into the next one are dropped, as the profile drops them.
    private fun months(c: Canvas, weeks: List<List<GhDay>>, from: Int, cols: Int, size: Float, left: Float, top: Float, right: Float, a: Float) {
        var lastEnd = -1f
        var prev = -1
        for (i in 0 until cols) {
            val d = weeks[from + i].firstOrNull() ?: continue
            val m = parse(d.date)?.let { cal.time = it; cal.get(Calendar.MONTH) } ?: continue
            if (m == prev) continue
            prev = m
            // The first column only gets a name when its month carries on long
            // enough for the name to belong to it
            if (i == 0 && monthRuns(weeks, from, cols, m) < 3) continue
            val x = left + i * (size + gap)
            val name = month.format(cal.time)
            val w = ui.small.measureText(name)
            if (x < lastEnd + ui.dp(4f) || x + w > right) continue
            ui.text(c, name, ui.small, x, ui.baseline(ui.small, top + monthH / 2 - ui.dp(1f)), w + 1f, a)
            lastEnd = x + w
        }
    }

    private fun monthRuns(weeks: List<List<GhDay>>, from: Int, cols: Int, m: Int): Int {
        var n = 0
        for (i in 0 until cols) {
            val d = weeks[from + i].firstOrNull() ?: break
            val mm = parse(d.date)?.let { cal.time = it; cal.get(Calendar.MONTH) } ?: break
            if (mm != m) break
            n++
        }
        return n
    }

    private fun legend(c: Canvas, r: RectF, y: Float, a: Float) {
        val s = ui.dp(9f)
        val cy = y + ui.dp(7f)
        val more = host.ctx.getString(R.string.github_more)
        val less = host.ctx.getString(R.string.github_less)
        var x = r.right - ui.small.measureText(more)
        ui.text(c, more, ui.small, x, ui.baseline(ui.small, cy), ui.small.measureText(more) + 1f, a)
        x -= ui.dp(4f)
        for (lv in 4 downTo 0) {
            x -= s
            cell.set(x, cy - s / 2, x + s, cy + s / 2)
            ui.fill(c, cell, s * 0.22f, shade(lv), a)
            x -= gap
        }
        x -= ui.dp(2f) + ui.small.measureText(less)
        ui.text(c, less, ui.small, x, ui.baseline(ui.small, cy), ui.small.measureText(less) + 1f, a)
    }

    // GitHub's five buckets in monochrome: the empty square is the island's
    // faintest fill, the busiest is full ink
    private fun shade(level: Int) = when (level) {
        1 -> Color.argb(72, 245, 245, 247)
        2 -> Color.argb(125, 245, 245, 247)
        3 -> Color.argb(185, 245, 245, 247)
        4 -> ui.ink
        else -> ui.fill
    }

    private fun parse(s: String) = try { iso.parse(s) } catch (_: Exception) { null }

    override fun tap(x: Float, y: Float): Boolean {
        if (super.tap(x, y)) return true
        if (shownCols == 0 || !grid.contains(x, y)) return false
        val step = shownCell + gap
        val col = ((x - gridLeft) / step).toInt().coerceIn(0, shownCols - 1)
        val row = ((y - gridTop) / step).toInt().coerceIn(0, 6)
        val week = gh.weeks.getOrNull(shownFrom + col) ?: return false
        val day = week.firstOrNull { it.weekday == row } ?: return false
        host.haptic()
        picked = if (picked == day.date) null else day.date
        return true
    }
}
