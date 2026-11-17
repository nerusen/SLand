package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// The landscape notch.
//
// Turned sideways, the island has nowhere to be: the camera is on a short edge,
// the status bar is gone or shrunk to nothing, and a shape hanging off the top
// of a video is in the way of the video. The old answer was to hide, which left
// the phone looking like Tepi had been switched off.
//
// This is the other answer: a small rounded block flush against the edge the
// camera is on, the shape an iPhone 12's notch has, with one eye in it. It
// says nothing and holds nothing — there is no room for a panel out here and
// no good place to put one — but it is awake, it blinks, and it reacts, so the
// phone still looks like it has Tepi on it. Tapping it either opens the
// island's menu or just makes it smile, which is a switch because the two
// wants are opposite: one is a shortcut, the other must never get in the way
// of a game.
//
// It is a window of its own rather than a mode of the island. The island is
// built around a centre point in the status bar, with springs for a width, a
// neck and two tiers; none of that means anything on a short edge, and bending
// it into that shape would have put a landscape branch through every one of
// its measurements. A second small window costs one surface and keeps both
// shapes simple.
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

    // Which edge the camera is on, in the current rotation. A rotation of 90
    // degrees puts what was the top edge on the left, 270 puts it on the
    // right; both are landscape, and they are the difference between a notch
    // in the right place and one on the wrong side of the phone.
    private enum class Side { Left, Right }
    private var side = Side.Left

    private val lp = WindowManager.LayoutParams(
        1, 1, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Tepi notch"
        // Over the cutout, not politely beside it: the notch is meant to sit
        // exactly where the hardware already interrupts the screen
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private inner class Face(c: Context) : View(c) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val shape = Path()
        private val body = RectF()
        private val radii = FloatArray(8)

        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            body.set(0f, 0f, w, h)

            val r = min(ui.dp(RADIUS), min(body.width(), body.height()) / 2)
            // Square against the glass, rounded away from it. The two corners
            // on the edge would be rounding nothing, and a sliver of
            // background between a curve and the bezel is exactly what makes a
            // shape look stuck on rather than part of the screen.
            shape.reset()
            if (side == Side.Left) fillRadii(0f, r, r, 0f) else fillRadii(r, 0f, 0f, r)
            shape.addRoundRect(body, radii, Path.Direction.CW)
            canvas.drawPath(shape, bg)

            // One eye, centred. Its height is taken from the block's depth,
            // not its length: the eye is a tall oval, so the dimension that
            // can run out is the width, and the width follows the height. At
            // this ratio even a startled eye, a third wider, still has black
            // on both sides of it.
            eye.draw(canvas, body.centerX(), body.centerY(), body.width() * EYE_H, 1f)
        }

        // Corner radii clockwise from the top left, each corner one value
        private fun fillRadii(tl: Float, tr: Float, br: Float, bl: Float) {
            radii[0] = tl; radii[1] = tl
            radii[2] = tr; radii[3] = tr
            radii[4] = br; radii[5] = br
            radii[6] = bl; radii[7] = bl
        }

        // A tap, and only a tap.
        //
        // The notch sits on a side edge, which in gesture navigation is also
        // where the back swipe starts. The window cannot hand a gesture back
        // to the system once it has taken the touch down, so the least it can
        // do is refuse to act on one: a finger that travels is treated as
        // someone swiping past, not tapping. The window is also no bigger than
        // the block itself, so the rest of the edge is still the system's.
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

    @Suppress("DEPRECATION")
    private fun place(force: Boolean) {
        val m = DisplayMetrics()
        screen.getRealMetrics(m)
        val screenW = m.widthPixels
        val screenH = m.heightPixels
        val rot = screen.rotation
        // show() is called from the island's refresh, which runs on every
        // scene change; the window is only moved when the rotation or the
        // display actually changed, so the common case costs no binder call
        val sig = "$rot:$screenW:$screenH"
        if (!force && sig == placedFor) return
        placedFor = sig
        side = if (rot == Surface.ROTATION_270) Side.Right else Side.Left
        // Short and deep: the depth is what reads as a notch, the length is
        // kept well under a third of the edge so it is never a bar
        val depth = ui.dp(DEPTH)
        val length = min(ui.dp(LENGTH), screenH * MAX_EDGE)
        lp.width = max(1, depth.toInt())
        lp.height = max(1, length.toInt())
        lp.x = if (side == Side.Left) 0 else max(0, screenW - lp.width)
        lp.y = max(0, ((screenH - length) / 2f).toInt())
        if (attached) wm.updateViewLayout(face, lp)
    }

    private var placedFor = ""

    fun show() {
        if (attached) {
            place(false)
            kick()
            return
        }
        place(true)
        try {
            wm.addView(face, lp)
            attached = true
        } catch (_: Exception) {
            attached = false
            placedFor = ""
            return
        }
        kick()
    }

    fun hide() {
        main.removeCallbacks(wake)
        if (!attached) return
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
        try { wm.removeView(face) } catch (_: Exception) { }
        attached = false
        placedFor = ""
    }

    fun battery(level: Int, charging: Boolean) {
        eye.battery(level, charging)
        kick()
    }

    // Something from another app arrived
    fun startled() {
        if (!attached) return
        eye.startled()
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

    private fun kick() {
        if (!attached || ticking) return
        ticking = true
        lastNs = 0
        main.removeCallbacks(wake)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick(ns: Long) {
        if (!attached) { ticking = false; return }
        val dt = if (lastNs == 0L) 1f / 60f else ((ns - lastNs) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNs = ns
        val moving = eye.step(dt)
        face.invalidate()
        if (moving) Choreographer.getInstance().postFrameCallback(frame)
        else {
            ticking = false
            main.postDelayed(wake, eye.idleWait())
        }
    }

    companion object {
        // How far the block comes in from the edge, how long it is along the
        // edge, and the radius of the two inner corners
        private const val DEPTH = 22f
        private const val LENGTH = 86f
        private const val RADIUS = 11f
        // Never more than this much of the edge, whatever the dp works out to
        private const val MAX_EDGE = 0.32f
        // Eye height against the block's own depth. The eye is a tall oval, so
        // its width is a fraction of this again: at 0.72 the resting eye is
        // under half the depth across, a startled one about three fifths, and
        // the angry brow — the widest thing drawn — about seven tenths. All of
        // them keep black on both sides, which is what makes it read as an eye
        // in a notch rather than a notch filled with an eye.
        private const val EYE_H = 0.72f
    }
}
