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
// This is the other answer: a small rounded block hanging from the middle of
// the top edge, the shape an iPhone 12's notch has, with one eye in it. It
// says nothing and holds nothing — there is no room for a panel out here and
// no good place to put one — but it is awake, it blinks, and it reacts, so the
// phone still looks like it has Tepi on it. Tapping it either opens the
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
        // Flush with the glass rather than politely inside the insets: the
        // notch is meant to start exactly at the top of the screen, cutout or
        // no cutout
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

            val cap = min(w, h) / 2f
            // Both radii are fractions of the depth rather than fixed dp, so
            // the silhouette keeps its proportions at every size the slider
            // can ask for.
            //
            // The two free corners, hanging away from the glass, carry the
            // real radius: they are the whole shape of the thing. The two on
            // the glass get a lip — a right angle there is the one detail that
            // gives a drawn shape away, since real hardware has a radius
            // everywhere however tight, but anything larger than a lip opens a
            // visible wedge of wallpaper between the curve and the bezel and
            // then the block looks stuck on instead of cut out.
            val r = min(h * RADIUS_K, cap)
            val lip = min(h * LIP_K, cap)
            shape.reset()
            fillRadii(lip, lip, r, r)
            shape.addRoundRect(body, radii, Path.Direction.CW)
            canvas.drawPath(shape, bg)

            // One eye, centred. Its height comes from the block's depth, which
            // is now the short dimension and so the only one that can run out;
            // the width follows the height, and the block is four times longer
            // than it is deep, so the width never has to be checked at all.
            eye.draw(canvas, body.centerX(), body.centerY(), eyeHeight(h), 1f)
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

    // How tall the eye is drawn, against the depth of the block it sits in.
    //
    // The ceiling is not the slider's top value. A fright grows the eye past
    // its resting height, and the crescents reach further still — the scowl's
    // floor bows below where the oval's edge would be — so the largest setting
    // has to leave room for the biggest shape it is about to become, not for
    // the calm one. The budget is EYE_ROOM of the depth for that worst case,
    // and the two factors that make it up are asked of Eye rather than
    // written out again here, so the two files cannot drift apart.
    private fun eyeHeight(depth: Float): Float {
        val scale = prefs.notchEye.coerceIn(Prefs.NOTCH_EYE_MIN, Prefs.NOTCH_EYE_MAX) / 100f
        val ceiling = depth * EYE_ROOM / (Eye.MAX_GROWTH * Eye.MAX_EXTENT)
        return min(depth * EYE_H * scale, ceiling)
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
        val scale = size / 100f
        // Wide and shallow, centred on the edge. Both dimensions are also
        // capped against the screen, so a small or oddly shaped display gets a
        // smaller notch rather than a bar across the top.
        val length = min(ui.dp(LENGTH * scale), screenW * MAX_LONG)
        val depth = min(ui.dp(DEPTH * scale), screenH * MAX_DEEP)
        lp.width = max(1, length.toInt())
        lp.height = max(1, depth.toInt())
        lp.x = max(0, ((screenW - length) / 2f).toInt())
        lp.y = 0
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
        // The block at the hundred per cent setting: how far it hangs down from
        // the top edge, and how wide it is along it. The size slider scales
        // both together, so the silhouette is the same shape at every setting.
        private const val DEPTH = 26f
        private const val LENGTH = 100f

        // Corner radii as fractions of the depth. The free pair is just under
        // half the depth, which is the roundest a corner can be without the
        // two of them meeting; the pair on the glass is a lip, no more.
        private const val RADIUS_K = 0.42f
        private const val LIP_K = 0.15f

        // Never more than this much of either edge, whatever the dp works out
        // to. Length is the one that matters — a block across a third of the
        // top is a bar, not a notch — and depth is a guard for small screens.
        private const val MAX_LONG = 0.32f
        private const val MAX_DEEP = 0.12f

        // Eye height against the block's own depth, at the hundred per cent
        // setting. The eye is a tall oval, so its width is a fraction of this
        // again and the length of the block is never tight; it is the depth
        // that has to be budgeted.
        private const val EYE_H = 0.48f
        // The most of the depth the eye may take at its widest moment,
        // whatever the slider says: the rest stays black, which is what makes
        // it read as an eye in a notch rather than a notch full of eye.
        private const val EYE_ROOM = 0.84f
    }
}
