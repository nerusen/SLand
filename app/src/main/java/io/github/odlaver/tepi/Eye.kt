package io.github.odlaver.tepi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// One eye, and what it is doing.
//
// Tepi's own face in the app is a pair of these; the landscape notch has room
// for one, so this is the single-eye version of the same idea: a white oval,
// taller than it is wide, that blinks on a timer and changes shape when
// something happens to the phone.
//
// Four moods, and the order between them matters more than the drawing does:
//
//   Calm      the resting oval, blinking every seven seconds
//   Startled  a notification from another app: the eye widens, then settles
//   Angry     the battery is nearly gone: the eye goes red and gains a brow
//   Happy     the notch was tapped: the eye becomes a curve for three seconds
//
// Startled and Happy are events with a deadline, Angry is a state that lasts
// as long as the battery does. Events win while they last, so a tap still gets
// its smile on a phone at four per cent, and the red comes back on its own
// when the three seconds are up. The red tint itself is tied to the battery
// rather than to the mood, so a smile on a dying phone is still a red smile:
// the colour is the warning, the shape is the reply to the tap.
//
// Everything is a spring rather than a frame count, so the shape is always
// somewhere sensible even when two things happen at once, and the caller only
// has to ask whether anything is still moving.
class Eye(private val ui: Ui) {
    enum class Mood { Calm, Startled, Angry, Happy }

    // Lid: 1 is open, near 0 is a blink. Stiff, because a slow blink reads as
    // a sleepy eye rather than a blinking one.
    private val lid = Spring(1f, 0.004f, 900f, 48f)
    // How wide the eye is against its resting size; the startle is a jump to
    // a little over 1 and a settle back
    private val size = Spring(1f, 0.004f, 420f, 26f)
    // Crossfade from the oval to the happy curve
    private val joy = Spring(0f, 0.004f, 420f, 32f)
    // Red tint, and the brow that comes with the angry mood
    private val red = Spring(0f, 0.004f, 240f, 30f)
    private val brow = Spring(0f, 0.004f, 300f, 30f)

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val oval = RectF()
    private val path = Path()

    // Battery, as the two levels Android itself warns at
    private var low = 0

    // The event in charge, and when it hands back
    private var event = Mood.Calm
    private var eventUntil = 0L

    // Blink timing: when the next one is due, and when the lid opens again
    private var blinkAt = SystemClock.uptimeMillis() + BLINK_MS
    private var openAt = 0L

    // Slow throb for the critical battery, in radians
    private var phase = 0f

    val mood: Mood get() = when {
        eventUntil > SystemClock.uptimeMillis() -> event
        low > 0 -> Mood.Angry
        else -> Mood.Calm
    }

    // A notification from another app. Re-arming rather than queueing: three
    // messages in a row are one startle that lasts a little longer, not three
    // widenings fighting each other.
    fun startled() {
        event = Mood.Startled
        eventUntil = SystemClock.uptimeMillis() + STARTLE_MS
        // Eyes do not blink in the middle of a fright
        lid.target = 1f
        blinkAt = eventUntil + BLINK_MS / 2
    }

    // The notch was tapped and is not opening anything
    fun happy() {
        event = Mood.Happy
        eventUntil = SystemClock.uptimeMillis() + HAPPY_MS
        // The curve is a closed eye already; no blink inside it
        blinkAt = eventUntil + BLINK_MS / 2
    }

    // Straight from ACTION_BATTERY_CHANGED. Charging is never angry: a phone
    // on the cable is not in trouble, whatever the level says.
    fun battery(level: Int, charging: Boolean) {
        low = when {
            charging || level < 0 -> 0
            level <= CRITICAL -> CRITICAL
            level <= LOW -> LOW
            else -> 0
        }
    }

    // True while still moving
    fun step(dt: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val m = mood

        // Blink, unless the eye is a curve or still wide from a fright
        if (m == Mood.Calm || m == Mood.Angry) {
            if (openAt == 0L && now >= blinkAt) {
                lid.target = BLINK_LID
                openAt = now + BLINK_HOLD
                // Seven seconds between blinks, counted from this one rather
                // than from the lid coming back up: measuring from the end
                // would add the hold to every interval and make the eye drift
                // slower and slower against the clock
                blinkAt = now + BLINK_MS
            } else if (openAt != 0L && now >= openAt) {
                lid.target = 1f
                openAt = 0L
            }
        } else {
            // No blinking inside a curve or a fright: the lid is held open and
            // the next one is scheduled for after the mood is over
            openAt = 0L
            lid.target = 1f
        }

        size.target = if (m == Mood.Startled) STARTLE_SIZE else 1f
        joy.target = if (m == Mood.Happy) 1f else 0f
        red.target = if (low > 0) 1f else 0f
        brow.target = if (m == Mood.Angry) 1f else 0f

        var moving = false
        for (s in arrayOf(lid, size, joy, red, brow)) moving = s.step(dt) or moving
        // The critical level pulses, so it keeps asking for frames
        if (low == CRITICAL) {
            phase = (phase + dt * THROB_RATE) % (Math.PI.toFloat() * 2)
            moving = true
        }
        // A deadline still to come is motion as far as the caller is concerned
        if (eventUntil > now) moving = true
        return moving
    }

    // Centre, the eye's resting height, and the alpha of whatever is drawing
    // it. The width follows the height, so one number sets the whole eye.
    fun draw(c: Canvas, cx: Float, cy: Float, h: Float, a: Float) {
        if (h <= 0f || a <= 0.01f) return
        val k = size.value.coerceIn(0.5f, 2f)
        val full = h * k
        val w = full * WIDTH
        val open = lid.value.coerceIn(0f, 1f)
        val j = joy.value.coerceIn(0f, 1f)
        val tint = colour()

        // Oval and curve are crossfaded rather than morphed: two simple
        // shapes, each always drawn correctly, beat one shape that is wrong
        // in the middle
        if (j < 0.99f) {
            val eh = max(full * LINE, full * open)
            oval.set(cx - w / 2, cy - eh / 2, cx + w / 2, cy + eh / 2)
            solid.color = ui.alpha(tint, a * (1f - j))
            c.drawOval(oval, solid)
        }
        if (j > 0.01f) {
            // Happy is a closed eye: an arc bowed upwards, drawn as a stroke
            // so it reads as a line and not as a sliver of the oval
            val aw = w * HAPPY_W
            val rise = full * HAPPY_RISE
            stroke.strokeWidth = max(ui.dp(1.4f), full * LINE)
            stroke.color = ui.alpha(tint, a * j)
            path.reset()
            path.moveTo(cx - aw / 2, cy + rise / 2)
            path.quadTo(cx, cy - rise * 1.5f, cx + aw / 2, cy + rise / 2)
            c.drawPath(path, stroke)
        }

        // Brow: one slanted stroke over the eye. Angled rather than straight,
        // because a level line above an eye is a lid and a slanted one is a
        // frown, and the frown is the whole point of the angry mood.
        val b = brow.value.coerceIn(0f, 1f)
        if (b > 0.02f) {
            val bw = w * BROW_W
            val top = cy - full * BROW_GAP
            val tilt = full * BROW_TILT
            stroke.strokeWidth = max(ui.dp(1.2f), full * BROW_LINE)
            stroke.color = ui.alpha(tint, a * b)
            c.drawLine(cx - bw / 2, top - tilt / 2, cx + bw / 2, top + tilt / 2, stroke)
        }
    }

    // White at rest, red as the battery goes. The critical level breathes
    // between full and most of full, which is the difference between a warning
    // that is on and one that is urgent.
    private fun colour(): Int {
        val t = red.value.coerceIn(0f, 1f)
        if (t <= 0.01f) return ui.ink
        val pulse = if (low == CRITICAL) 0.78f + 0.22f * (0.5f + 0.5f * sin(phase.toDouble()).toFloat()) else 1f
        val mix = min(1f, t * pulse)
        return Color.rgb(
            lerp(Color.red(ui.ink), Color.red(RED), mix),
            lerp(Color.green(ui.ink), Color.green(RED), mix),
            lerp(Color.blue(ui.ink), Color.blue(RED), mix),
        )
    }

    private fun lerp(from: Int, to: Int, k: Float) = (from + (to - from) * k).toInt().coerceIn(0, 255)

    // How long the caller may sleep once nothing is moving. The eye is still
    // on a timer when it is at rest, so a notch that simply stopped drawing
    // would never blink again; this is when to wake it up.
    fun idleWait(): Long {
        val now = SystemClock.uptimeMillis()
        val next = minOf(
            if (openAt != 0L) openAt else blinkAt,
            if (eventUntil > now) eventUntil else Long.MAX_VALUE,
        )
        return (next - now).coerceIn(16L, BLINK_MS)
    }

    companion object {
        // Oval width over its height: taller than wide, the way Tepi's own
        // pair are drawn in the app
        private const val WIDTH = 0.62f
        // Thinnest the eye gets: a blink is a line, not nothing at all
        private const val LINE = 0.14f

        // Every seven seconds, held shut just long enough to be seen
        private const val BLINK_MS = 7000L
        private const val BLINK_HOLD = 110L
        private const val BLINK_LID = 0.08f

        // A fright is a jump in size and a few seconds to get over it
        private const val STARTLE_MS = 2600L
        private const val STARTLE_SIZE = 1.32f

        // A tap is answered for three seconds
        private const val HAPPY_MS = 3000L
        private const val HAPPY_W = 1.5f
        private const val HAPPY_RISE = 0.5f

        // Brow: how wide, how far above the eye, how far it tilts, how thick
        private const val BROW_W = 1.55f
        private const val BROW_GAP = 0.62f
        private const val BROW_TILT = 0.34f
        private const val BROW_LINE = 0.15f

        // The two levels Android itself warns at
        private const val LOW = 20
        private const val CRITICAL = 10

        // Radians per second of the critical throb: about one breath a second
        private const val THROB_RATE = 6.2f

        // Same red the battery mark uses at its critical level
        private val RED = Color.rgb(255, 69, 58)
    }
}
