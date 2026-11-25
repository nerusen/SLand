package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The landscape notch.
//
// Turned sideways, the island has nowhere to be: the camera is on a short edge,
// the status bar is gone or shrunk to nothing, and a shape hanging off the top
// of a video is in the way of the video. The old answer was to hide, which left
// the phone looking like Tepi had been switched off.
//
// This is the other answer: a small rounded block hanging from the middle of
// the top edge, the shape an iPhone 12's notch has, with a pair of eyes in it.
// It says nothing and holds nothing — there is no room for a panel out here
// and no good place to put one — but it is awake, it blinks, and it reacts, so
// the phone still looks like it has Tepi on it. Tapping it either opens the
// island's menu or just makes it smile, which is a switch because the two
// wants are opposite: one is a shortcut, the other must never get in the way
// of a game.
//
// The top edge, not the camera edge. On the camera it was on a short side:
// a tall sliver in the corner of the eye, under the hand holding the phone,
// and sitting directly on the back gesture. The top edge is where a notch
// belongs in any orientation — it is the edge the eye already reads, it is
// where the island itself sits in portrait, and it is a long edge, so a block
// that is wide and shallow fits on it without taking a third of anything.
//
// It is a window of its own rather than a mode of the island. The island is
// built around the lens, with springs for a width, a neck and two tiers; none
// of that means anything out here, and bending it into this shape would have
// put a landscape branch through every one of its measurements. A second small
// window costs one surface and keeps both shapes simple.
class Notch(
    private val context: Context,
    private val screen: Display,
    private val type: Int,
    private val prefs: Prefs,
    private val ui: Ui,
    // Tap, when the switch says a tap opens the menu
    private val onOpen: () -> Unit,
) {
    val eye = Eye(ui)

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var attached = false

    // Open and close: 1 is the full block, 0 is nothing at all.
    //
    // Not a fade. The island opens and closes by rebuilding its own outline at
    // every step — width, height and radii all on springs — and the notch has
    // to leave the same way or the two look like separate things fighting over
    // the same edge. So the block collapses towards the middle of the edge and
    // into the glass, staying a correctly proportioned notch the whole way,
    // and the panel grows out of that same edge as it goes.
    //
    // Same constants as the island's own shape springs, so a notch handing
    // over to a panel is one motion at one speed rather than two that happen
    // to overlap.
    private val grow = Spring(0f, 0.004f, 420f, 30f)
    // The eyes' own fade, separate from the shape.
    //
    // When the island hands the edge back, it has already shrunk its panel
    // down to this exact silhouette, so the notch arrives at full size with no
    // shape animation of its own — the swap between two identical black blocks
    // in the same place is invisible. What must not be instant is the face:
    // the panel's content is still fading out underneath, and a pair of eyes
    // snapping on over it is the one thing that would give the handover away.
    // So the shape is instant and the eyes fade in, against the panel's
    // content fading out.
    private val look = Spring(0f, 0.004f, 420f, 40f)
    private val clockMix = Spring(0f, 0.004f, 280f, 34f)
    private val markA = Spring(0f, 0.004f, 420f, 34f)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.ink
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.ink
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var clockEnabled = false
    private var nextClockAt = 0L
    private var clockUntil = 0L
    private var clockText = ""
    private var markAt = 0L
    private var markUntil = 0L
    private var markPhase = 0f
    // Where the shape is heading: the island asks for this when a panel takes
    // over the edge, and the window is only torn down once the shape has
    // actually finished shrinking
    private var wanted = true
    private var showPending = false
    private val beginShape = Runnable {
        if (!attached || !wanted) return@Runnable
        showPending = false
        grow.target = 1f
        look.target = 1f
        retouch()
        kick()
    }

    // The block at the current size setting, measured once in place()
    private var blockW = 0f
    private var blockH = 0f

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private val lp = WindowManager.LayoutParams(
        1, 1, type, baseFlags, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Tepi notch"
        // Flush with the glass rather than politely inside the insets: the
        // notch is meant to start exactly at the top of the screen, cutout or
        // no cutout
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private inner class Face(c: Context) : View(c) {
        // The theme's own ground, read per frame rather than captured: the
        // island hands this Ui its colours back on a settings change, and a
        // colour copied into a paint at construction would leave the notch the
        // only black thing on a violet phone
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = Path()
        private val body = RectF()

        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // The window is grown by the flare on each side and by the open
            // spring's headroom; the block itself is the middle of it.
            val f = ui.glassFlare
            val k = grow.value.coerceIn(0f, 1f)
            if (k <= 0.001f) return
            // Closing is the shape shrinking back into the edge rather than
            // fading: the length collapses towards the centre, the depth
            // collapses towards the glass, and the radii follow the depth, so
            // the block is a correct notch at every size on the way in and out
            val len = blockW * k
            val dep = blockH * k
            if (len < 1f || dep < 1f) return
            body.set(w / 2 - len / 2, 0f, w / 2 + len / 2, dep)

            val cap = min(body.width(), body.height()) / 2f
            // The two free corners, hanging away from the glass, carry the
            // real radius: they are the whole silhouette of the thing. The two
            // on the glass are square, and flare outwards instead of rounding
            // inwards, so the block grows out of the bezel rather than sitting
            // on top of it — rounding them as well would curve the shape away
            // from the glass exactly where the flare is sweeping towards it
            // and punch a notch of wallpaper out between the two.
            //
            // The flare shrinks with the shape, so it is never wider than the
            // block it is flaring from. It has to clear the depth as well as
            // the half-length: a flare taller than the block it hangs off
            // would reach past the block's own bottom edge and read as two
            // legs rather than a join.
            val r = min(dep * CORNER, cap)
            shape.reset()
            ui.block(shape, body, r, min(f * k, min(len / 2, dep)))
            bg.color = ui.back
            canvas.drawPath(shape, bg)

            // The eyes, centred. Their height comes from the block's depth,
            // which is the short dimension and so the only one that can run
            // out; the pair's width follows that height.
            //
            // On their own fade, and clipped to the block. While the edge is
            // being handed over in either direction the two shapes overlap —
            // both black, so they read as one — and the face is what must not
            // appear over the panel's content. The clip also means a shape
            // that is still growing cannot leak an eye outside itself.
            val la = look.value.coerceIn(0f, 1f)
            val clock = clockMix.value.coerceIn(0f, 1f)
            if (la <= 0.01f && clock <= 0.01f) return
            canvas.save()
            canvas.clipPath(shape)
            clockPaint.color = ui.ink
            markPaint.color = ui.ink
            val eh = eyeHeight(blockH, blockW) * k
            eye.draw(canvas, body.centerX(), body.centerY(), eh, la * (1f - clock))
            if (clock > 0.01f) {
                clockPaint.textSize = blockH * 0.53f * k
                val metrics = clockPaint.fontMetrics
                val baseline = body.centerY() - (metrics.ascent + metrics.descent) / 2f
                val scale = 0.92f + 0.08f * clock
                canvas.save()
                canvas.scale(scale, scale, body.centerX(), body.centerY())
                clockPaint.alpha = (255 * la * clock).toInt().coerceIn(0, 255)
                canvas.drawText(clockText, body.centerX(), baseline, clockPaint)
                canvas.restore()
            }
            val ma = markA.value.coerceIn(0f, 1f) * (1f - clock)
            if (ma > 0.01f && eh > 0f) {
                markPaint.textSize = blockH * 0.62f * k
                val metrics = markPaint.fontMetrics
                val bob = sin(markPhase.toDouble()).toFloat() * blockH * 0.12f
                val baseline = body.centerY() - (metrics.ascent + metrics.descent) / 2f + bob
                val cx = body.centerX() + eye.pairWidth(eh) / 2f + blockH * 0.20f
                markPaint.alpha = (255 * la * ma).toInt().coerceIn(0, 255)
                canvas.drawText("!", cx, baseline, markPaint)
            }
            canvas.restore()
        }

        // A tap, and only a tap.
        //
        // The notch hangs from the top edge, which is also where the shade is
        // pulled down from. The window cannot hand a gesture back to the
        // system once it has taken the touch down, so the least it can do is
        // refuse to act on one: a finger that travels is someone swiping past,
        // not tapping. The window is also no bigger than the block itself, so
        // the rest of the edge was never the notch's to take.
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(e.rawX - downX, e.rawY - downY) > ui.dp(16f)) moved = true
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        // The switch is read here, not stored: it may be
                        // flipped while the notch is up
                        if (prefs.notchOpens) onOpen() else eye.happy()
                        kick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private val face = Face(context)

    // How tall each eye is drawn, against the block it sits in.
    //
    // Two ceilings, and neither is the slider's top value.
    //
    // The depth is the tight one. A fright grows the eyes past their resting
    // height, and the crescents reach further still — the scowl's floor bows
    // below where the oval's edge would be — so the largest setting has to
    // leave room for the biggest shape it is about to become, not for the calm
    // one. The factors that make that up are asked of Eye rather than written
    // out again here, so the two files cannot drift apart.
    //
    // The length is the loose one, and it only exists because there are two
    // eyes now: a pair is roughly three times as wide as one, which is still
    // comfortable inside a block four times longer than it is deep, but the
    // check costs nothing — and the pair now spreads as the crescents widen,
    // so the widest it ever gets is worth asking for rather than assuming.
    private fun eyeHeight(depth: Float, length: Float): Float {
        val scale = prefs.notchEye.coerceIn(Prefs.NOTCH_EYE_MIN, Prefs.NOTCH_EYE_MAX) / 100f
        val tall = depth * EYE_ROOM / (Eye.MAX_GROWTH * Eye.MAX_EXTENT)
        // pairWidth is for one unit of resting height, so dividing gives the
        // height the available length can afford
        val long = length * EYE_SPAN / max(1f, eye.pairWidth(1f))
        return min(depth * EYE_H * scale, min(tall, long))
    }

    @Suppress("DEPRECATION")
    private fun place(force: Boolean) {
        val m = DisplayMetrics()
        screen.getRealMetrics(m)
        val screenW = m.widthPixels.toFloat()
        val screenH = m.heightPixels.toFloat()
        if (screenW <= 0f || screenH <= 0f) return
        val rot = screen.rotation
        val size = prefs.notchSize.coerceIn(Prefs.NOTCH_SIZE_MIN, Prefs.NOTCH_SIZE_MAX)
        // show() is called from the island's refresh, which runs on every
        // scene change; the window is only moved when the rotation, the
        // display or the size setting has actually changed, so the common case
        // costs no binder call
        val sig = "$rot:${m.widthPixels}:${m.heightPixels}:$size"
        if (!force && sig == placedFor) return
        placedFor = sig
        val length = length(ui, prefs, screenW)
        val depth = depth(ui, prefs, screenH)
        blockW = length
        blockH = depth
        // The window is the block plus a flare on each side, because the flare
        // is drawn outside the block's own corners and a window sized to the
        // block alone would clip it. Nothing else is added: the rest of the
        // edge stays the system's.
        val f = ui.glassFlare
        val wide = length + f * 2
        lp.width = max(1, wide.toInt())
        lp.height = max(1, depth.toInt())
        lp.x = max(0, ((screenW - wide) / 2f).toInt())
        lp.y = 0
        lp.flags = flagsNow()
        if (attached) wm.updateViewLayout(face, lp)
    }

    // A shape on its way out stops taking touches.
    //
    // For the couple of hundred milliseconds it takes to shrink, this window
    // is still stacked over the panel growing underneath it. Refusing the
    // event in onTouchEvent is not enough: a window that has taken the touch
    // down has taken it, and returning false only throws it away rather than
    // passing it on. The flag is the only thing that makes the tap fall
    // through to the island below.
    private fun flagsNow() = baseFlags or
        if (wanted && !showPending) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    private fun retouch() {
        if (!attached) return
        val f = flagsNow()
        if (lp.flags == f) return
        lp.flags = f
        try { wm.updateViewLayout(face, lp) } catch (_: Exception) { }
    }

    private var placedFor = ""

    // Put the notch up.
    //
    // grown says the edge already has this shape on it, because the island
    // just finished shrinking its own panel into it: the block is taken over
    // as it stands and only the eyes fade in. Otherwise — a rotation, a switch
    // flipped, an hour of quiet ending — there is nothing on the edge and the
    // block grows out of the glass.
    fun show(grown: Boolean) {
        wanted = true
        if (attached) {
            if (showPending) return
            grow.target = 1f
            look.target = 1f
            place(false)
            retouch()
            kick()
            return
        }
        place(true)
        // Arriving from nothing: start collapsed into the glass so the first
        // frame is the shape opening rather than the shape appearing.
        //
        // Snapped before the targets are set, never after. snap() takes the
        // target with it, so snapping last parked both springs where they
        // stood and left them there: handed a grown shape, that was a black
        // block with no face on it until something else happened to ask for a
        // refresh. Closing the panel by tapping the island again asked twice
        // and so repaired itself; tapping outside it asked once and did not.
        showPending = !grown
        grow.snap(if (grown) 1f else 0f)
        look.snap(0f)
        grow.target = if (grown) 1f else 0f
        look.target = if (grown) 1f else 0f
        lp.flags = flagsNow()
        try {
            wm.addView(face, lp)
            attached = true
        } catch (_: Exception) {
            attached = false
            placedFor = ""
            return
        }
        if (showPending) main.postDelayed(beginShape, SHAPE_DELAY_MS)
        else { grow.target = 1f; look.target = 1f }
        kick()
    }

    fun clock(enabled: Boolean) {
        if (clockEnabled == enabled) return
        clockEnabled = enabled
        clockUntil = 0L
        nextClockAt = if (enabled) SystemClock.uptimeMillis() + CLOCK_INTERVAL else 0L
        clockMix.target = 0f
        if (attached) kick()
    }

    // Shrink the notch back into the edge, and take the window down when it
    // gets there. Asking twice is harmless; the window is only removed once.
    fun close() {
        if (!attached) { drop(); return }
        if (!wanted) return
        main.removeCallbacks(beginShape)
        showPending = false
        wanted = false
        grow.target = 0f
        look.target = 0f
        retouch()
        kick()
    }

    // Straight down, no animation: for teardown, where there is nobody left
    // to watch the shape go
    fun hide() {
        wanted = false
        grow.snap(0f)
        look.snap(0f)
        clockMix.snap(0f)
        markA.snap(0f)
        drop()
    }

    private fun drop() {
        main.removeCallbacks(wake)
        main.removeCallbacks(beginShape)
        showPending = false
        if (!attached) return
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
        try { wm.removeView(face) } catch (_: Exception) { }
        attached = false
        placedFor = ""
    }



    fun battery(level: Int, charging: Boolean) {
        eye.battery(level, charging)
        if (attached) kick()
    }

    // Something is playing, or it has stopped. The eyes sway while it does,
    // which is the only thing the notch can say about music: there is no room
    // out here for a title, and a title is not what someone watching sideways
    // with a song on is missing.
    fun music(playing: Boolean) {
        eye.music(playing)
        if (attached) kick()
    }

    // Something from another app arrived
    fun startled() {
        if (!attached || !wanted) return
        eye.startled()
        markAt = SystemClock.uptimeMillis() + MARK_DELAY
        markUntil = markAt + MARK_DURATION
        markA.snap(0f)
        kick()
    }

    // Frame loop. The eye is on a seven-second timer, so once its springs have
    // settled the loop is stopped and a single delayed wake is posted for the
    // next blink instead: holding a Choreographer callback open through seven
    // seconds of nothing would be a frame every sixtieth of a second, all day.
    private var ticking = false
    private var lastNs = 0L
    private val frame = Choreographer.FrameCallback { ns -> tick(ns) }
    private val wake = Runnable { kick() }

    private fun updateEvents(now: Long) {
        if (clockEnabled) {
            if (clockUntil > 0L && now >= clockUntil) {
                clockUntil = 0L
                nextClockAt = now + CLOCK_INTERVAL
                clockMix.target = 0f
            }
            if (clockUntil == 0L && now >= nextClockAt) {
                clockUntil = now + CLOCK_DURATION
                clockText = timeFormat.format(Date())
                clockMix.target = 1f
            }
        } else {
            clockMix.target = 0f
        }
        if (markAt > 0L && now >= markAt) {
            markAt = 0L
            markUntil = now + MARK_DURATION
            markA.target = 1f
        }
        if (markUntil > 0L && now >= markUntil) {
            markUntil = 0L
            markA.target = 0f
        }
    }

    private fun idleWait(): Long {
        val now = SystemClock.uptimeMillis()
        val nextEvent = listOfNotNull(
            nextClockAt.takeIf { clockEnabled && clockUntil == 0L && it > now },
            clockUntil.takeIf { clockEnabled && it > now },
            markAt.takeIf { it > now },
            markUntil.takeIf { it > now },
        ).minOrNull() ?: Long.MAX_VALUE
        return minOf(eye.idleWait(), (nextEvent - now).coerceIn(16L, 120_000L))
    }

    private fun kick() {
        if (!attached || ticking) return
        ticking = true
        lastNs = 0
        main.removeCallbacks(wake)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick(ns: Long) {
        if (!attached) { ticking = false; return }
        val now = SystemClock.uptimeMillis()
        updateEvents(now)
        val dt = if (lastNs == 0L) 1f / 60f else ((ns - lastNs) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNs = ns
        var moving = grow.step(dt)
        moving = look.step(dt) or moving
        moving = clockMix.step(dt) or moving
        moving = markA.step(dt) or moving
        if (markUntil > now && markA.value > 0.01f) {
            markPhase = (markPhase + dt * MARK_RATE) % (Math.PI.toFloat() * 2f)
            moving = true
        }
        // A shape on its way out has nothing to express, and stepping the eye
        // through the exit would start a blink nobody can see
        if (wanted) moving = eye.step(dt) or moving
        face.invalidate()
        // Gone: take the window down rather than holding a surface open for a
        // shape of zero size
        if (!wanted && grow.value <= 0.01f) {
            ticking = false
            drop()
            return
        }
        if (moving) Choreographer.getInstance().postFrameCallback(frame)
        else {
            ticking = false
            // A shape that is staying still is still on the blink timer
            if (wanted) main.postDelayed(wake, idleWait())
        }
    }

    companion object {
        // The block's two dimensions, for whoever needs to know where the
        // notch sits without owning one.
        //
        // The island asks for these in landscape: it collapses its own panel
        // down to exactly this silhouette as it closes, so the panel shrinking
        // away and the notch growing back are the same shape in the same place
        // rather than two shapes swapping. One copy of the arithmetic, so they
        // cannot disagree.
        //
        // Wide and shallow, and capped against the screen as well as the dp,
        // so a small or oddly shaped display gets a smaller notch rather than
        // a bar across the top.
        fun length(ui: Ui, prefs: Prefs, screenW: Float): Float {
            val scale = prefs.notchSize.coerceIn(Prefs.NOTCH_SIZE_MIN, Prefs.NOTCH_SIZE_MAX) / 100f
            return min(ui.dp(LENGTH * scale), screenW * MAX_LONG)
        }

        fun depth(ui: Ui, prefs: Prefs, screenH: Float): Float {
            val scale = prefs.notchSize.coerceIn(Prefs.NOTCH_SIZE_MIN, Prefs.NOTCH_SIZE_MAX) / 100f
            return min(ui.dp(DEPTH * scale), screenH * MAX_DEEP)
        }

        // The two free corners, as a fraction of the depth: just under half,
        // which is the roundest a pair of corners can be without meeting.
        // Shared for the same reason the dimensions are — the island closes
        // into this exact silhouette sideways. The two corners on the glass
        // are not corners at all any more: they flare outwards, and that
        // dimension lives in Ui because the panel uses the same one.
        const val CORNER = 0.42f
        private const val CLOCK_INTERVAL = 120_000L
        private const val CLOCK_DURATION = 20_000L
        private const val MARK_DELAY = 220L
        private const val MARK_DURATION = 1_800L
        private const val MARK_RATE = 6.2f
        private const val SHAPE_DELAY_MS = 120L

        // The block at the hundred per cent setting: how far it hangs down from
        // the top edge, and how wide it is along it. The size slider scales
        // both together, so the silhouette is the same shape at every setting.
        private const val DEPTH = 26f
        private const val LENGTH = 100f


        // Never more than this much of either edge, whatever the dp works out
        // to. Length is the one that matters — a block across a third of the
        // top is a bar, not a notch — and depth is a guard for small screens.
        private const val MAX_LONG = 0.32f
        private const val MAX_DEEP = 0.12f

        // Eye height against the block's own depth, at the hundred per cent
        // setting. One eye of a pair, not the pair: the pair's width follows
        // from it. Smaller than the single eye was, because two of them in the
        // same depth want a little more air around them than one did.
        private const val EYE_H = 0.44f
        // The most of the depth the eyes may take at their widest moment,
        // whatever the slider says: the rest stays black, which is what makes
        // it read as eyes in a notch rather than a notch full of eye.
        private const val EYE_ROOM = 0.84f
        // Same for the length. A pair needs checking against it where a single
        // eye never did.
        private const val EYE_SPAN = 0.82f
    }
}
