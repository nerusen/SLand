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

// A pair of eyes, and what they are doing.
//
// The same face Tepi wears in the app, drawn into the landscape notch: two
// white ovals, taller than they are wide, that blink on a timer and change
// shape when something happens to the phone. Both are always drawn
// identically — a pair that blinks or glares out of step is not a face being
// expressive, it is a face with something wrong with it.
//
// Four moods, and the order between them matters more than the drawing does:
//
//   Calm      the resting ovals, blinking every seven seconds
//   Startled  a notification from another app: the eyes widen, then settle
//   Angry     the battery is nearly gone: red crescents with heavy lids
//   Happy     the notch was tapped: plump upward crescents for three seconds
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
// scowl; bowed up with round ends it is a smile. Because it is one
// construction, the numbers that make it can be interpolated, and the change
// between any two moods is the curve bending rather than one picture replacing
// another.
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
    // How far the curve has bent into the happy crescent
    private val joy = Spring(0f, 0.004f, 420f, 32f)
    // How far it has bent into the angry one. Slower than joy: a glare
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
    //
    // The gap is worked out from the width the shapes are actually being drawn
    // at, not from a constant: the crescents are half again as wide as the
    // resting oval, and a gap sized for the oval has them growing into each
    // other until the pair reads as one smudge. Air between the two is what is
    // held constant instead, so they slide apart as they curve and back
    // together as they settle.
    fun draw(c: Canvas, cx: Float, cy: Float, h: Float, a: Float) {
        if (h <= 0f || a <= 0.01f) return
        val full = h * size.value.coerceIn(0.5f, 2f)
        val f = form(full)
        // The air is held, so the gap is whatever the shapes are actually as
        // wide as plus that. The stroke counts: it puffs the smile out by half
        // of itself on each side, which is a whole stroke of width between the
        // two eyes.
        val gap = full * f.wide + f.stroke + h * AIR
        one(c, cx - gap / 2, cy, full, a, f)
        one(c, cx + gap / 2, cy, full, a, f)
    }

    // How wide a pair is at its widest moment, against the resting height of
    // one eye: the air between them plus the two widest shapes either of them
    // gets, grown by a startle. The caller needs this to know whether a face
    // fits where it is going, and working it back out of these constants on
    // the other side of the call is how the two drift apart.
    fun pairWidth(h: Float) = h * (AIR + 2f * SPAN * MAX_GROWTH)

    // The shape both eyes are drawn with, worked out once a frame.
    //
    // One holder rather than a pair of local sets: the two eyes are
    // deliberately identical, and the gap between them has to come from the
    // same width the paths are drawn at or the two drift and the eyes touch.
    private class Shape {
        // Whether the resting oval is still being drawn underneath the curve
        var rest = 1f
        // Path width, against the eye's grown height
        var wide = 0f
        // Where the two ends sit, positive being below the eye line, and the
        // control points of the upper and lower edges
        var tip = 0f
        var up = 0f
        var lo = 0f
        // The round stroke that thickens the smile and blunts its ends, in px
        var stroke = 0f
    }

    private val drawn = Shape()

    // Where the moods have got to, as one shape rather than three.
    //
    // Not a crossfade. Three finished shapes faded over each other means two
    // translucent eyes on top of one another for the whole transition, which
    // does not read as an eye changing shape — it reads as one picture being
    // swapped for another, and at these sizes that is a flicker. The scowl and
    // the smile are already the same two-curve construction, so the numbers
    // that make it are interpolated instead and the eye is a single opaque
    // path that bends.
    //
    // What it bends out of is the oval itself, not a flat line. Two quadratics
    // bowing away from each other by exactly the oval's half-height are a lens
    // the same size and shape as the oval, so the curve starts life as the
    // resting eye and leaves it by unbending: the silhouette never jumps and
    // never pinches thinner than where it is going. The oval is still drawn
    // underneath for the first part of the move, because the lens is a hair
    // narrower than a true ellipse at the ends and the blink lives in it.
    //
    // Joy is applied over the glare rather than beside it, so a tap on a dying
    // phone reads as the smile winning; between the two the curve passes
    // through the resting oval, which is a frown unbending, which is what it
    // should look like.
    private fun form(full: Float): Shape {
        val j = joy.value.coerceIn(0f, 1f)
        val g = glare.value.coerceIn(0f, 1f)
        val scowl = (1f - j) * g
        val cres = (j + scowl).coerceIn(0f, 1f)
        // Which crescent the curve is heading for, where 1 is all smile
        val pj = if (cres > 0.001f) (j / cres).coerceIn(0f, 1f) else 0f
        drawn.rest = (1f - cres / HANDOVER).coerceIn(0f, 1f)
        drawn.wide = WIDTH * lerp(1f, DEVIL_W * (1f - pj) + HAPPY_W * pj, cres)
        drawn.tip = lerp(0f, -DEVIL_TIP * (1f - pj) + HAPPY_TIP * pj, cres)
        // From the oval's own two edges out to the crescent's control points.
        // A quadratic sits halfway between its control point and the chord, so
        // a control point at twice the half-height draws an edge through the
        // oval's own: that is where these two start.
        drawn.up = lerp(-1f, DEVIL_LID * (1f - pj) - HAPPY_TOP * pj, cres)
        drawn.lo = lerp(1f, DEVIL_FLOOR * (1f - pj) - HAPPY_BOT * pj, cres)
        // The floor in dp is what makes the stroke visible at all on the
        // smallest setting, where the fraction comes out under a pixel. It
        // comes in with the smile, so the scowl keeps its points.
        drawn.stroke = max(ui.dp(1f), full * HAPPY_PUFF) * j
        return drawn
    }

    private fun lerp(from: Float, to: Float, k: Float) = from + (to - from) * k

    // One eye, in whatever shape the moods have settled on. The height passed
    // in is the grown one, so the startle is already in it.
    private fun one(c: Canvas, cx: Float, cy: Float, full: Float, a: Float, f: Shape) {
        val open = lid.value.coerceIn(0f, 1f)
        val colour = ui.alpha(colour(), a)

        // How far the lid has the shape squeezed towards the eye line.
        //
        // A blink is a line, not nothing, so there are two floors under it:
        // one in the eye's own units and one in dp, because the smallest notch
        // with the smallest eye in it is a shape only a few dp tall and a
        // fourteen per cent slice of that is under a pixel. They are applied
        // here, to the squeeze itself, rather than to the oval alone — the
        // oval is held inside the curve's band, so a floor the curve did not
        // also get would simply be clamped back off again.
        val floor = max(LINE, if (full > 1f) ui.dp(BLINK_FLOOR) / full else LINE)
        val squeeze = max(floor, open)
        // The curve, built first because the oval behind it is measured
        // against it. Two quadratics bowing away from each other, the far one
        // further than the near one, so the shape is fat in the middle and
        // runs out towards its two ends. At rest the two bow by exactly the
        // oval's half-height and the shape is the oval itself; bowed down with
        // its ends swept up it is the devil's scowl; bowed up with its ends
        // dipped and drawn plump it is the smile. The whole thing is squeezed
        // towards the eye line by the lid, so a glare can still blink.
        val half = full * f.wide / 2
        val tip = cy + full * f.tip * squeeze
        val upper = cy + full * f.up * squeeze
        val lower = cy + full * f.lo * squeeze

        if (f.rest > 0.01f) {
            // The resting oval, held inside the curve's own band and centred
            // on it.
            //
            // The oval is underneath only to fill the lens's slightly pinched
            // ends, so it must never be the taller of the two: if it were,
            // dropping it at the hand-over would be the shape jumping down a
            // fifth of its height in one frame, which is the flicker this is
            // all here to get rid of. Inside the band, the hand-over is
            // invisible. The blink's floors are in the squeeze above rather
            // than here, so the band carries them too.
            //
            // A quadratic sits halfway between its control point and the
            // chord, so the band is those two midpoints.
            val top = min(tip, (tip + upper) / 2)
            val bottom = max(tip, (tip + lower) / 2)
            val mid = (top + bottom) / 2
            val eh = bottom - top
            val w = full * WIDTH
            oval.set(cx - w / 2, mid - eh / 2, cx + w / 2, mid + eh / 2)
            solid.color = colour
            c.drawOval(oval, solid)
        }

        path.reset()
        path.moveTo(cx - half, tip)
        path.quadTo(cx, upper, cx + half, tip)
        path.quadTo(cx, lower, cx - half, tip)
        path.close()
        // Filled and then stroked with a round join, which rounds the two ends
        // and puffs the shape out by half a stroke on each side: the difference
        // between a cute curve and a sharp one. It comes in with the smile, so
        // the scowl keeps its points. The width is the same one draw() budgeted
        // for in the gap, so the air between the two eyes does not shift as the
        // stroke arrives.
        val p = if (f.stroke > 0.3f) plump.also { it.strokeWidth = f.stroke } else solid
        p.color = colour
        c.drawPath(path, p)
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
        // Clear space between the two eyes, against the resting height of one.
        //
        // The air, not the distance between the centres. A fixed centre
        // distance is only right for one shape, and the crescents are half
        // again as wide as the oval it was measured from: the smile overlapped
        // its twin by a sixth of an eye and the scowl's two points met in the
        // middle, which is where the pair stopped reading as two eyes. Holding
        // the gap instead means every mood keeps the spacing the resting pair
        // was tuned to, and the eyes slide apart as they curve.
        private const val AIR = 0.24f
        // The widest any single shape gets, against the resting height: the
        // happy crescent grown by a startle, plus the stroke that puffs it.
        // Rounded up rather than worked out exactly, because the stroke has a
        // floor in dp and so is a larger share of a small eye than of a big
        // one; the budget has to cover the smallest setting on the densest
        // screen, which is where that floor bites hardest.
        private const val SPAN = 1.18f
        // How far into a mood change the resting oval stops being drawn.
        //
        // It is not a fade: the curve begins as a lens the size of the oval
        // and the oval is only underneath it to fill the lens's slightly
        // pinched ends, so once the shape has bent appreciably out of the
        // resting one the oval is a blob behind a smile rather than part of
        // it. A third of the way is where the curve is unmistakably its own
        // shape and still the taller of the two, so the hand-over does not
        // show.
        private const val HANDOVER = 0.34f
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
