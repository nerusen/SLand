package io.github.odlaver.tepi

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.abs
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
    // Green, for a phone on the cable. Separate from red rather than one
    // "tint" spring between three colours: the two states are independent —
    // charging at four per cent is both — and a single spring would have had
    // to pick one, which is how a charging phone would end up looking angry.
    private val green = Spring(0f, 0.004f, 240f, 30f)
    // How far the eyes have taken up the music's sway. A spring rather than a
    // switch, so the swaying starts and stops with the song instead of
    // snapping into step with it.
    private val groove = Spring(0f, 0.004f, 200f, 26f)

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
    // The charging glow: a blurred copy of whatever the eyes are, drawn under
    // them. One paint for both eyes, and the blur is a filter rather than a
    // second pass, so the cost is a shader rather than a redraw.
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private var haloFor = 0f

    // Battery, as the two levels Android itself warns at
    private var low = 0
    // On the cable. Not the same question as low: a phone at four per cent
    // with a charger in it is charging, and that is what the eyes should say.
    private var plugged = false

    // Whether something is playing. The eyes sway to it, which is the only
    // thing the notch can do about music: there is no room out here for a
    // title, and a title is not what someone watching a video sideways with
    // a song on wants anyway.
    private var grooving = false

    // Sway phase, in radians, and the slower one the shape breathes on
    private var swayPhase = 0f
    private var pulsePhase = 0f

    // The event in charge, and when it hands back
    private var event = Mood.Calm
    private var eventUntil = 0L

    // Blink timing: when the next one is due, and when the lid opens again
    private var blinkAt = SystemClock.uptimeMillis() + BLINK_MS
    private var openAt = 0L

    // Slow throb for the critical battery, in radians
    private var phase = 0f

    // And the slow fade the charging glow breathes on, which is its own
    // because it must keep going while the critical throb is not
    private var glowPhase = 0f

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
    // on the cable is not in trouble, whatever the level says. It is green
    // instead, which is a different thing said with the same shape — the eyes
    // keep resting and blinking exactly as they were and only the colour
    // moves, so a phone put on charge does not change expression, it changes
    // mood lighting.
    fun battery(level: Int, charging: Boolean) {
        plugged = charging
        low = when {
            charging || level < 0 -> 0
            level <= CRITICAL -> CRITICAL
            level <= LOW -> LOW
            else -> 0
        }
    }

    // Something is playing, or it is not. The eyes sway to it.
    fun music(playing: Boolean) {
        grooving = playing
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
        green.target = if (plugged) 1f else 0f
        // The sway is held back inside an event. A startled pair of eyes that
        // is also swaying to the music reads as neither: the fright is two
        // and a half seconds long and the sway can wait for it.
        groove.target = if (grooving && m == Mood.Calm) 1f else 0f

        var moving = false
        for (s in arrayOf(lid, size, joy, glare, red, green, groove)) moving = s.step(dt) or moving
        // The critical level pulses, so it keeps asking for frames
        if (low == CRITICAL) {
            phase = (phase + dt * THROB_RATE) % TWO_PI
            moving = true
        }
        // So does the charging glow, which breathes considerably slower: it is
        // reassurance rather than a warning, and a fast pulse would read as
        // one
        if (plugged || green.value > 0.01f) {
            glowPhase = (glowPhase + dt * GLOW_RATE) % TWO_PI
            moving = true
        }
        // And so does the sway, which is a continuous motion rather than a
        // spring settling: two phases, one for the side-to-side and a slower
        // one for the shape, so the two never line up into a mechanical beat
        if (groove.value > 0.01f) {
            swayPhase = (swayPhase + dt * SWAY_RATE) % TWO_PI
            pulsePhase = (pulsePhase + dt * PULSE_RATE) % TWO_PI
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
    //
    // While music plays the whole pair rides a path rather than sitting still.
    // The path is an arc and not a line: eyes that slide left and right along
    // a rail read as a mechanism, and eyes that rise a little at the ends of
    // the swing read as a head moving. One sine for the sideways and its own
    // double for the lift is exactly that arc — the lift completes two cycles
    // for every one of the sway, so the pair is lowest in the middle and
    // highest at both ends, which is a shallow smile traced out over a couple
    // of seconds.
    fun draw(c: Canvas, cx: Float, cy: Float, h: Float, a: Float) {
        if (h <= 0f || a <= 0.01f) return
        val k = groove.value.coerceIn(0f, 1f)
        // The size wobble rides the slower phase, and it is deliberately
        // small: a third of a tenth either way is a pair of eyes breathing
        // with the beat, and anything more is a pair of eyes being squeezed
        val beat = if (k > 0.01f) 1f + k * PULSE * sin(pulsePhase.toDouble()).toFloat() else 1f
        val full = h * size.value.coerceIn(0.5f, 2f) * beat
        val f = form(full)
        // The air is held, so the gap is whatever the shapes are actually as
        // wide as plus that. The stroke counts: it puffs the smile out by half
        // of itself on each side, which is a whole stroke of width between the
        // two eyes.
        val gap = full * f.wide + f.stroke + h * AIR
        var ox = 0f
        var oy = 0f
        if (k > 0.01f) {
            val s = sin(swayPhase.toDouble()).toFloat()
            ox = h * SWAY_X * k * s
            // Double frequency, so the lift is a bowl under the swing rather
            // than a tilt that follows it
            oy = -h * SWAY_Y * k * sin(2.0 * swayPhase).toFloat()
        }
        glow(c, cx + ox, cy + oy, gap, full, a, f)
        one(c, cx + ox - gap / 2, cy + oy, full, a, f)
        one(c, cx + ox + gap / 2, cy + oy, full, a, f)
    }

    // The soft green halo under a charging pair.
    //
    // A blurred copy of the same two shapes, drawn first so the eyes sit on
    // top of their own light rather than inside a wash of it. Its opacity
    // breathes on a slow phase, which is the whole of the animation: the eyes
    // themselves do not move, so the glow coming and going is what says the
    // phone is taking a charge rather than simply being green.
    //
    // The blur radius follows the eye's own height, so a notch at the smallest
    // setting gets a halo in proportion rather than a green smudge the size of
    // the block.
    private fun glow(c: Canvas, cx: Float, cy: Float, gap: Float, full: Float, a: Float, f: Shape) {
        val g = green.value.coerceIn(0f, 1f)
        if (g <= 0.02f || full <= 0f) return
        val breathe = GLOW_FLOOR + (1f - GLOW_FLOOR) * (0.5f + 0.5f * sin(glowPhase.toDouble()).toFloat())
        val strength = g * breathe * GLOW_A * a
        if (strength <= 0.01f) return
        val blur = max(ui.dp(1.5f), full * GLOW_BLUR)
        // The filter is rebuilt only when the radius actually changes, which
        // is on a size change rather than per frame: a BlurMaskFilter
         // allocates, and this is called sixty times a second
        if (abs(blur - haloFor) > 0.5f) {
            haloFor = blur
            halo.maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        halo.color = ui.alpha(GREEN, strength)
        // Stroked as well as filled, so the halo is a touch larger than the
        // eye it is behind and shows all the way round rather than only where
        // the blur spills
        halo.strokeWidth = max(f.stroke, blur * 0.6f)
        for (side in intArrayOf(-1, 1)) {
            shape(cx + side * gap / 2, cy, full, f)
            c.drawPath(path, halo)
        }
    }

    // How wide a pair is at its widest moment, against the resting height of
    // one eye: the air between them plus the two widest shapes either of them
    // gets, grown by a startle, plus the sway the music puts them on. The
    // caller needs this to know whether a face fits where it is going, and
    // working it back out of these constants on the other side of the call is
    // how the two drift apart.
    //
    // The sway counts twice over: once because the whole pair slides that far
    // sideways, and once because the shapes are a little larger at the top of
    // the beat. Both are small, and both would show as a clipped eye if they
    // were left out of the budget — which is exactly where a shape that is
    // only ever nearly too big goes wrong.
    fun pairWidth(h: Float) = h * (AIR + 2f * SPAN * MAX_GROWTH * (1f + PULSE) + 2f * SWAY_X)

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
        val colour = ui.alpha(colour(), a)

        if (f.rest > 0.01f) {
            // The resting oval, held inside the curve's own band and centred
            // on it.
            //
            // The oval is underneath only to fill the lens's slightly pinched
            // ends, so it must never be the taller of the two: if it were,
            // dropping it at the hand-over would be the shape jumping down a
            // fifth of its height in one frame, which is the flicker this is
            // all here to get rid of. Inside the band, the hand-over is
            // invisible. The blink's floors are in the squeeze rather than
            // here, so the band carries them too.
            //
            // A quadratic sits halfway between its control point and the
            // chord, so the band is those two midpoints.
            val tip = tipOf(cy, full, f)
            val upper = upperOf(cy, full, f)
            val lower = lowerOf(cy, full, f)
            val top = min(tip, (tip + upper) / 2)
            val bottom = max(tip, (tip + lower) / 2)
            val mid = (top + bottom) / 2
            val eh = bottom - top
            val w = full * WIDTH
            oval.set(cx - w / 2, mid - eh / 2, cx + w / 2, mid + eh / 2)
            solid.color = colour
            c.drawOval(oval, solid)
        }

        shape(cx, cy, full, f)
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

    // The eye's outline, into the shared path.
    //
    // Pulled out of one() so the charging halo can draw the same silhouette
    // blurred underneath without a second copy of the construction: two
    // quadratics bowing away from each other, the far one further than the
    // near one, so the shape is fat in the middle and runs out towards its two
    // ends. At rest the two bow by exactly the oval's half-height and the
    // shape is the oval itself; bowed down with its ends swept up it is the
    // devil's scowl; bowed up with its ends dipped and drawn plump it is the
    // smile. The whole thing is squeezed towards the eye line by the lid, so a
    // glare can still blink.
    private fun shape(cx: Float, cy: Float, full: Float, f: Shape) {
        val half = full * f.wide / 2
        val tip = tipOf(cy, full, f)
        path.reset()
        path.moveTo(cx - half, tip)
        path.quadTo(cx, upperOf(cy, full, f), cx + half, tip)
        path.quadTo(cx, lowerOf(cy, full, f), cx - half, tip)
        path.close()
    }

    // How far the lid has the shape squeezed towards the eye line.
    //
    // A blink is a line, not nothing, so there are two floors under it: one in
    // the eye's own units and one in dp, because the smallest notch with the
    // smallest eye in it is a shape only a few dp tall and a fourteen per cent
    // slice of that is under a pixel. They are applied to the squeeze itself
    // rather than to the oval alone — the oval is held inside the curve's
    // band, so a floor the curve did not also get would simply be clamped back
    // off again.
    private fun squeezeOf(full: Float): Float {
        val open = lid.value.coerceIn(0f, 1f)
        val floor = max(LINE, if (full > 1f) ui.dp(BLINK_FLOOR) / full else LINE)
        return max(floor, open)
    }

    private fun tipOf(cy: Float, full: Float, f: Shape) = cy + full * f.tip * squeezeOf(full)
    private fun upperOf(cy: Float, full: Float, f: Shape) = cy + full * f.up * squeezeOf(full)
    private fun lowerOf(cy: Float, full: Float, f: Shape) = cy + full * f.lo * squeezeOf(full)

    // White at rest, red as the battery goes, green on the cable. The critical
    // level breathes between full and most of full, which is the difference
    // between a warning that is on and one that is urgent.
    //
    // Red wins over green where both somehow apply, but they cannot: battery()
    // clears low whenever the charger is in, which is the right way round —
    // a phone at four per cent that is charging is a phone that is fine.
    private fun colour(): Int {
        val t = red.value.coerceIn(0f, 1f)
        val g = green.value.coerceIn(0f, 1f)
        if (t <= 0.01f && g <= 0.01f) return ui.ink
        if (t > g) {
            val pulse = if (low == CRITICAL) 0.78f + 0.22f * (0.5f + 0.5f * sin(phase.toDouble()).toFloat()) else 1f
            return mix(ui.ink, RED, min(1f, t * pulse))
        }
        // Green holds steady; it is the halo behind it that breathes, so the
        // eyes themselves do not flicker while the phone charges
        return mix(ui.ink, GREEN, g)
    }

    private fun mix(from: Int, to: Int, k: Float) = Color.rgb(
        lerp(Color.red(from), Color.red(to), k),
        lerp(Color.green(from), Color.green(to), k),
        lerp(Color.blue(from), Color.blue(to), k),
    )

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
        // The sway, while music plays, declared first because the size
        // budgets below are worked out from it.
        //
        // SWAY_X is how far the pair slides either side of centre, against the
        // resting height of one eye, and SWAY_Y is the lift at the ends of the
        // swing. The lift is a quarter of the slide and runs at twice its
        // frequency, which is what bends the path into a shallow arc instead
        // of a rail: the pair is lowest in the middle of the swing and highest
        // at both ends. RATE is a little over one full swing every two
        // seconds, which is unhurried enough to read as a sway rather than a
        // jitter, and PULSE_RATE is deliberately not a multiple of it so the
        // size wobble drifts against the slide instead of marching with it.
        //
        // PULSE is how much the shapes themselves grow and shrink on that
        // slower phase: three per cent, which is the difference between eyes
        // that are alive to the music and eyes being pumped. It is small on
        // purpose — the thing being avoided is a face that looks like it is
        // chewing.
        private const val SWAY_X = 0.3f
        private const val SWAY_Y = 0.075f
        private const val SWAY_RATE = 3.1f
        private const val PULSE_RATE = 4.3f
        private const val PULSE = 0.03f

        // The most the eye ever grows past its resting height, which is the
        // startle, and the tallest the drawn shape gets against that grown
        // height. Both are public because whoever hands draw() a height has to
        // budget for the largest thing it can become, and a copy of either
        // number on the other side of that call is a copy that will drift.
        //
        // EXTENT is over 1 because the crescents are not inside the oval: the
        // scowl's floor bows to half of DEVIL_FLOOR below the eye line, which
        // is further down than the oval's own edge, and that is the lowest
        // point anything here reaches. The sway's lift and the beat's growth
        // are both in it too, for the same reason they are in pairWidth: a
        // budget that only covers the still face is a budget that clips the
        // moving one. It works out at 1.10 x (1 + PULSE) + SWAY_Y; the number
        // is written out so the budget cannot silently move when one of those
        // two is tuned, which is exactly the kind of change that would start
        // clipping an eye on one phone and not another.
        const val MAX_GROWTH = 1.32f
        const val MAX_EXTENT = 1.208f

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

        // The charging glow.
        //
        // RATE is deliberately a third of the critical throb: the one is a
        // warning and should feel like a pulse, the other is reassurance and
        // should feel like breathing. FLOOR keeps the halo from going out
        // entirely at the bottom of the breath — a glow that reaches zero
        // reads as a flash rather than a fade. BLUR is against the eye's own
        // height so a small notch gets a small halo, and A is how bright the
        // whole thing is allowed to get, which is well under the eye in front
        // of it: a halo as bright as its source is not a halo.
        private const val GLOW_RATE = 2.1f
        private const val GLOW_FLOOR = 0.32f
        private const val GLOW_BLUR = 0.42f
        private const val GLOW_A = 0.72f

        // One turn, for wrapping the phases. Written out rather than derived
        // from Math.PI, because a const val may only hold a compile-time
        // constant and a conversion to Float is a call.
        private const val TWO_PI = 6.2831855f

        // Same red the battery mark uses at its critical level, and the same
        // green it uses while charging
        private val RED = Color.rgb(255, 69, 58)
        private val GREEN = Color.rgb(52, 199, 89)
    }
}
