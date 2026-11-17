package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import io.github.odlaver.tepi.Capture
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Shot
import java.text.DateFormat
import java.util.Date

// The screenshot just taken: square thumbnail, its details, two actions.
//
// Thumbnail hard left, name and figures beside it, Open and Share as round
// chips on the right. The chips are icons rather than a pair of labelled
// buttons on a second line: that keeps the card one row tall, which is what
// lets it appear and leave without the island growing into a panel the user
// then has to read.
class ShotScene(host: Host, private val share: (Shot) -> Unit) : Scene(host) {
    override val id = "shot"
    // Above messages and alerts, below an incoming call: a capture is a direct
    // answer to a button press, so it should not be made to queue
    override val priority = 93
    override val personal = true

    private val main = Handler(Looper.getMainLooper())
    private val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val day = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
    private var shot: Shot? = null
    private val tmp = RectF()
    private val hitOpen = RectF()
    private val hitShare = RectF()
    private val hitCard = RectF()

    private val drop = Runnable { shot = null; host.refresh() }

    fun show(s: Shot) {
        shot = s
        main.removeCallbacks(drop)
        // Longer than a passing notice: the two chips are only worth putting
        // up if there is time to aim at them
        main.postDelayed(drop, SHOW_MS)
        host.refresh()
    }

    override fun live() = shot != null
    override fun card() = true
    override fun dismiss() { shot = null; main.removeCallbacks(drop) }

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    private val thumbS get() = ui.dp(44f)
    private val chip get() = ui.dp(34f)

    override fun panelHeight(w: Float) = thumbS

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val s = shot ?: return
        // Square crop of the capture itself, so it reads as the picture and
        // not as a file icon
        tmp.set(r.left, r.top, r.left + thumbS, r.top + thumbS)
        if (s.thumb != null) ui.art(c, s.thumb, tmp, a, 0.18f)
        else {
            ui.fill(c, tmp, ui.dp(8f), ui.fill, a)
            ui.icon(c, Icon.Image, tmp.centerX(), tmp.centerY(), ui.dp(20f), ui.sub, a)
        }

        val gap = ui.s8
        hitShare.set(r.right - chip, r.top + (thumbS - chip) / 2, r.right, r.top + (thumbS + chip) / 2)
        hitOpen.set(hitShare.left - gap - chip, hitShare.top, hitShare.left - gap, hitShare.bottom)
        // Open is the likely one, so it carries the filled chip
        ui.fill(c, hitOpen, chip / 2, ui.ink, a)
        ui.icon(c, Icon.Open, hitOpen.centerX(), hitOpen.centerY(), ui.dp(16f), android.graphics.Color.BLACK, a)
        ui.fill(c, hitShare, chip / 2, ui.fillStrong, a)
        ui.icon(c, Icon.Share, hitShare.centerX(), hitShare.centerY(), ui.dp(16f), ui.ink, a)

        val tx = tmp.right + ui.s12
        val tw = hitOpen.left - ui.s8 - tx
        ui.text(c, host.ctx.getString(R.string.shot_taken), ui.small, tx, r.top + ui.dp(11f), tw, a)
        ui.text(c, s.name, ui.title, tx, r.top + ui.dp(26f), tw, a)
        // Size and the clock, which says more than a date that is always today
        ui.text(c, listOf(bytes(s.size), stamp(s.takenAt)).filter { it.isNotEmpty() }.joinToString(" · "), ui.body, tx, r.top + ui.dp(41f), tw, a)
        // Anywhere else on the card is Open as well
        hitCard.set(r.left, r.top, hitOpen.left - ui.s8, r.bottom)
    }

    // Today is told by the clock, anything older by its date
    private fun stamp(at: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        return if (sameDay) time.format(Date(at)) else day.format(Date(at))
    }

    private fun bytes(n: Long): String = when {
        n <= 0 -> ""
        n >= 1024L * 1024 -> host.ctx.getString(R.string.size_mb, n / (1024f * 1024f))
        else -> host.ctx.getString(R.string.size_kb, (n / 1024f).coerceAtLeast(1f))
    }

    override fun tap(x: Float, y: Float): Boolean {
        val s = shot ?: return false
        when {
            hitShare.contains(x, y) -> { host.haptic(); share(s); gone() }
            hitOpen.contains(x, y) || hitCard.contains(x, y) -> {
                host.haptic()
                host.open(Capture.open(s))
                gone()
            }
            else -> return false
        }
        return true
    }

    private fun gone() {
        shot = null
        main.removeCallbacks(drop)
        host.collapse()
        host.refresh()
    }

    override fun describe(): String {
        val s = shot ?: return ""
        return host.ctx.getString(R.string.shot_taken) + ": " + s.name
    }

    companion object {
        private const val SHOW_MS = 6000L
    }
}
