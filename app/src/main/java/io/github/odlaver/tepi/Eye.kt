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
//   Angry     the battery is nearly gone: a red crescent with a heavy lid
//   Happy     the notch was tapped: a plump upward crescent for three seconds
//
// Startled and Happy are events with a deadline, Angry is a state that lasts
// as long as the battery does. Events win while they last, so a tap still gets
// its smile on a phone at four per cent, and the glare comes back on its own
// when the three seconds are up. The red tint itself is tied to the battery
// rather than to the mood, so a smile on a dying phone is still a red smile:
// the colour is the warning, the shape is the reply to the tap.
//
// Angry and Happy are both crescents, and they are deliberately the same
// construction read in opposite directions: two curves bowing the same way,
// the far one further than the near one, so the shape is fat in the middle and
// runs out to nothing at the two ends. Bowed down with sharp ends it is a
// scowl; bowed up with round ends it is a smile. That is also why the angry
// mood has no brow any more — a crescent lid is already the scowl, and a
// separate stroke floating above it was a second eyebrow on a face with one
// eye.
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
    // Crossfade from the oval to the happy crescent
    private val joy = Spring(0f, 0.004f, 420f, 32f)
    // Crossfade from the oval to the angry crescent. Slower than joy: a glare
    // settling in should feel like a mood arriving, not a shape snapping.
    private val glare = Spring(0f, 0.004f, 260f, 30f)
    // Red tint
    private val red = Spring(0f, 0.004f, 240f, 30f)

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    // The happy crescent is filled and then stroked with a round join, which
    // rounds its two ends and puffs the whole shape out by half a stroke. That
    // is the whole difference between a cute curve and a sharp one, and it is
    // cheaper than building rounded ends into the path.
    private val plump = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
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
        // The crescent is a closed eye already; no blink inside it
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

        // Blink, unless the eye is a happy crescent or still wide from a
        // fright. The glare does blink: a narrowed eye closing and reopening
        // is a good deal more pointed than one that simply stares.
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
            // No blinking inside a crescent or a fright: the lid is held open
            // and the next one is scheduled for after the mood is over
            openAt = 0L
            lid.target = 1f
        }

        size.target = if (m == Mood.Startled) MAX_GROWTH else 1f
        joy.target = if (m == Mood.Happy) 1f else 0f
        glare.target = if (m == Mood.Angry) 1f else 0f
        red.target = if (low > 0) 1f else 0f

        var moving = false
        for (s in arrayOf(lid, size, joy, glare, red)) moving = s.step(dt) or moving
        // The critical level pulses, so it keeps asking for frames
        if (low == CRITICAL) {
            phase = (phase + dt * THROB_RATE) % (Math.PI.toFloat() * 2)
            moving = true
        }
        // A deadline still to come is motion as far as the caller is concerned
        if (eventUntil > now) moving = true
        return moving
    }

    // A pair of eyes: centre of the face, the resting height of one eye, and
    // the alpha. The width of each eye follows its height, and the gap between
    // them follows it too, so one number still sets the whole face.
    //
    // Both eyes are drawn identically rather than independently. A pair that
    // blinks or glares out of step is not a face being expressive, it is a
    // face with something wrong with it; the two reading as one is the whole
    // reason a pair is better than a single eye here.
    fun draw(c: Canvas, cx: Float, cy: Float, h: Float, a: Float) {
        if (h <= 0f || a <= 0.01f) return
        val gap = h * size.value.coerceIn(0.5f, 2f) * GAP
        one(c, cx - gap / 2, cy, h, a)
        one(c, cx + gap / 2, cy, h, a)
    }

    // How wide a pair is in total, against the resting height of one eye: the
    // gap between the two centres plus the widest either of them gets. The
    // caller needs this to know whether a face fits where it is going, and
    // working it back out of these constants on the other side of the call is
    // how the two drift apart.
    fun pairWidth(h: Float) = h * (GAP + WIDTH * MAX_SPREAD) * MAX_GROWTH

    // One eye, in whatever shape the moods have settled on
    private fun one(c: Canvas, cx: Float, cy: Float, h: Float, a: Float) {
        val k = size.value.coerceIn(0.5f, 2f)
        val full = h * k
        val w = full * WIDTH
        val open = lid.value.coerceIn(0f, 1f)
        val j = joy.value.coerceIn(0f, 1f)
        val g = glare.value.coerceIn(0f, 1f)
        val tint = colour()

        // Three shapes crossfaded rather than one shape morphed: each is
        // always drawn correctly on its own, where a single path bent between
        // an oval, a scowl and a smile is wrong in the middle of every
        // transition. Joy is applied last and over everything, so a tap during
        // a glare reads as the smile winning.
        val rest = (1f - j) * (1f - g)
        val scowl = (1f - j) * g

        if (rest > 0.01f) {
            // A blink is a line, not nothing: the height has a floor in the
            // eye's own units and a second one in dp, because the smallest
            // notch with the smallest eye in it is a shape only a few dp tall
            // and a fourteen per cent slice of that is under a pixel
            val eh = max(max(full * LINE, ui.dp(BLINK_FLOOR)), full * open)
            oval.set(cx - w / 2, cy - eh / 2, cx + w / 2, cy + eh / 2)
            solid.color = ui.alpha(tint, a * rest)
            c.drawOval(oval, solid)
        }

        if (scowl > 0.01f) {
            // The devil's eye: a crescent with its ends swept up above the eye
            // line and its body sagging below it, both edges bowing down and
            // the lower one bowing further. The ends are left sharp — that is
            // the point of it — and the whole thing is squeezed towards the
            // eye line by the lid, so a glare can still blink.
            val squeeze = max(LINE, open)
            val half = w * DEVIL_W / 2
            val tip = cy - full * DEVIL_TIP * squeeze
            path.reset()
            path.moveTo(cx - half, tip)
            path.quadTo(cx, cy + full * DEVIL_LID * squeeze, cx + half, tip)
            path.quadTo(cx, cy + full * DEVIL_FLOOR * squeeze, cx - half, tip)
            path.close()
            solid.color = ui.alpha(tint, a * scowl)
            c.drawPath(path, solid)
        }

        if (j > 0.01f) {
            // Happy is a closed eye: the same crescent turned over, bowing up
            // with its ends dipped, and drawn plump with round ends so it
            // reads as a bubble of a smile rather than a sliver of the oval.
            val half = w * HAPPY_W / 2
            val tip = cy + full * HAPPY_TIP
            path.reset()
            path.moveTo(cx - half, tip)
            path.quadTo(cx, cy - full * HAPPY_TOP, cx + half, tip)
            path.quadTo(cx, cy - full * HAPPY_BOT, cx - half, tip)
            path.close()
            plump.strokeWidth = max(ui.dp(1f), full * HAPPY_PUFF)
            plump.color = ui.alpha(tint, a * j)
            c.drawPath(path, plump)
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
        // The most the eye ever grows past its resting height, which is the
        // startle, and the tallest the drawn shape gets against that grown
        // height. Both are public because whoever hands draw() a height has to
        // budget for the largest thing it can become, and a copy of either
        // number on the other side of that call is a copy that will drift.
        //
        // EXTENT is over 1 because the crescents are not inside the oval: the
        // scowl's floor bows to half of DEVIL_FLOOR below the eye line, which
        // is further down than the oval's own edge, and that is the lowest
        // point anything here reaches.
        const val MAX_GROWTH = 1.32f
        const val MAX_EXTENT = 1.10f

        // Oval width over its height: taller than wide, the way Tepi's own
        // pair are drawn in the app
        private const val WIDTH = 0.62f
        // Between the two eye centres, against the resting height of one. A
        // shade wider than one eye, which is what keeps the pair reading as
        // two eyes rather than as one wide blur.
        private const val GAP = 0.86f
        // The widest any single shape gets against the oval's own width: the
        // happy crescent plus its puff. Used for the pair's total width.
        private const val MAX_SPREAD = 1.70f
        // Thinnest the eye gets: a blink is a line, not nothing at all, as a
        // fraction of the height and as a hard floor in dp for the settings
        // where that fraction comes out under a pixel
        private const val LINE = 0.14f
        private const val BLINK_FLOOR = 1.2f

        // Every seven seconds, held shut just long enough to be seen
        private const val BLINK_MS = 7000L
        private const val BLINK_HOLD = 110L
        private const val BLINK_LID = 0.08f

        // A fright is a jump in size and a few seconds to get over it
        private const val STARTLE_MS = 2600L

        // A tap is answered for three seconds
        private const val HAPPY_MS = 3000L

        // The angry crescent, in fractions of the eye's height. The ends sweep
        // up by TIP, the upper edge sags to LID and the lower one to FLOOR;
        // the gap between those two at the centre is what the eye is made of,
        // so FLOOR must stay the larger of the pair. A quadratic sits halfway
        // between its control point and the chord, so the thickness in the
        // middle works out at (FLOOR - LID) / 2 — a third of the height here —
        // and the three numbers are chosen so the shape is centred on the eye
        // line rather than hanging below it.
        private const val DEVIL_W = 1.42f
        private const val DEVIL_TIP = 0.36f
        private const val DEVIL_LID = 0.46f
        private const val DEVIL_FLOOR = 1.08f

        // The happy crescent, the same construction turned over: the ends dip
        // by TIP, the outer edge rises to TOP and the inner one to BOT, and
        // PUFF is the round stroke that thickens it and blunts its ends. Same
        // third-of-the-height body, same centring.
        private const val HAPPY_W = 1.55f
        private const val HAPPY_TIP = 0.29f
        private const val HAPPY_TOP = 0.87f
        private const val HAPPY_BOT = 0.25f
        private const val HAPPY_PUFF = 0.07f

        // The two levels Android itself warns at
        private const val LOW = 20
        private const val CRITICAL = 10

        // Radians per second of the critical throb: about one breath a second
        private const val THROB_RATE = 6.2f

        // Same red the battery mark uses at its critical level
        private val RED = Color.rgb(255, 69, 58)
    }
}
