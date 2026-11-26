package io.github.odlaver.tepi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Non-interactive 16:9 live preview of the resting capsule (portrait) and the
// landscape notch inside the Island settings tab.
class IslandPreview(
    context: Context,
    private val prefs: Prefs,
) : View(context) {

    private val ui = Ui(context, prefs)
    private val badges = Badges(context, prefs).apply {
        wire(Radios(context) { if (attached) invalidate() })
    }
    private val eye = Eye(ui)
    private val main = Handler(Looper.getMainLooper())

    private var landscapeMode = false
    private var attached = false
    private var ticking = false
    private var lastFrame = 0L
    private var badgePace = 0L

    private val cardRect = RectF()
    private val screenRect = RectF()
    private val cardPath = Path()
    private val screenPath = Path()
    private val shape = Path()
    private val hole = RectF()
    private val pill = RectF()
    private val now = RectF()
    private val greetingRect = RectF()
    private val notchRect = RectF()
    private val box = RectF()

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wall = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val statusIconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(145, 16, 20, 28)
    }
    private val hintText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(245, 245, 247)
        textAlign = Paint.Align.CENTER
    }

    private var batteryLevel = 84
    private var batteryCharging = false

    private val repaintBadge = Runnable {
        if (attached && !landscapeMode && prefs.idleBar) {
            invalidate()
            armBadge()
        }
    }

    private val wake = Runnable {
        syncLoop()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!attached || !landscapeMode || !prefs.notchBar) {
                ticking = false
                return
            }
            val dt = if (lastFrame == 0L) 0.016f else ((t - lastFrame) / 1e9f).coerceIn(0f, 0.05f)
            lastFrame = t
            val moving = eye.step(dt)
            invalidate()
            if (moving) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                ticking = false
                main.removeCallbacks(wake)
                main.postDelayed(wake, eye.idleWait())
            }
        }
    }

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        refresh()
    }

    fun setLandscape(landscape: Boolean) {
        if (landscapeMode == landscape) return
        landscapeMode = landscape
        if (landscape) main.removeCallbacks(repaintBadge) else armBadge()
        syncLoop()
        invalidate()
    }

    fun refresh() {
        ui.reseed()
        bg.color = ui.back
        shadow.color = ui.back
        if (prefs.islandShadow) {
            val alpha = (255f * prefs.shadowOpacity.coerceIn(Prefs.SHADOW_OPACITY_MIN, Prefs.SHADOW_OPACITY_MAX) / 100f).toInt()
            if (alpha <= 0) shadow.clearShadowLayer()
            else shadow.setShadowLayer(ui.dp(8f), 0f, ui.dp(3f), Color.argb(alpha, 0, 0, 0))
        } else {
            shadow.clearShadowLayer()
        }
        syncBattery()
        syncLoop()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        syncBattery()
        syncLoop()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopLoop()
        main.removeCallbacks(repaintBadge)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rawW = MeasureSpec.getSize(widthMeasureSpec)
        val w = if (rawW > 0) rawW else ui.dp(320f).roundToInt()
        val h = (w * 9f / 16f).roundToInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cardRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val pad = ui.dp(2.5f)
        screenRect.set(pad, pad, w - pad, h - pad)
        val outerR = ui.dp(14f)
        val innerR = ui.dp(11.5f)
        cardPath.reset()
        cardPath.addRoundRect(cardRect, outerR, outerR, Path.Direction.CW)
        screenPath.reset()
        screenPath.addRoundRect(screenRect, innerR, innerR, Path.Direction.CW)

        bezel.color = Color.rgb(24, 25, 29)
        wall.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.rgb(230, 238, 249),
                Color.rgb(212, 226, 244),
                Color.rgb(197, 213, 236),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        canvas.drawPath(cardPath, bezel)
        canvas.save()
        canvas.clipPath(screenPath)
        canvas.drawRect(screenRect, wall)
        if (landscapeMode) drawLandscape(canvas) else drawPortrait(canvas)
        canvas.restore()
    }

    private fun drawPortrait(canvas: Canvas) {
        val simW = ui.dp(390f)
        val scale = screenRect.width() / simW
        if (scale <= 0f) return
        val simH = screenRect.height() / scale

        canvas.save()
        canvas.translate(screenRect.left, screenRect.top)
        canvas.scale(scale, scale)

        val cy = ui.dp(25f)
        val holeR = ui.dp(10f)
        val holeCx = (simW / 2f + ui.dp(prefs.offsetDp.toFloat()))
            .coerceIn(ui.edge + holeR, simW - ui.edge - holeR)
        hole.set(holeCx - holeR, cy - holeR, holeCx + holeR, cy + holeR)
        pill.set(hole)
        val pad = ui.dp(5f)
        pill.inset(-pad, -pad)

        drawPortraitStatus(canvas, simW, cy)

        if (prefs.idleBar) {
            val span = simW - ui.edge * 2f
            val wid = max(pill.width(), ui.dp(prefs.idleWidthDp.toFloat())).coerceIn(pill.width(), max(pill.width(), span))
            val left = (holeCx - wid / 2f).coerceIn(ui.edge.toFloat(), max(ui.edge.toFloat(), simW - ui.edge - wid))
            now.set(left, pill.top, left + wid, pill.bottom)

            shape.reset()
            val radius = pill.height() / 2f
            shape.addRoundRect(now, radius, radius, Path.Direction.CW)

            val greetingOn = prefs.greetingEnabled && prefs.greetingName.isNotBlank()
            greetingRect.setEmpty()
            if (greetingOn) {
                val text = "Hi, ${prefs.greetingName}"
                val wanted = ui.compact.measureText(text) + ui.s16 * 2
                val gw = min(max(pill.width(), wanted), span)
                val gLeft = (now.centerX() - gw / 2f).coerceIn(ui.edge.toFloat(), simW - ui.edge - gw)
                val gTop = now.bottom + ui.s8
                greetingRect.set(gLeft, gTop, gLeft + gw, gTop + pill.height())
                shape.addRoundRect(greetingRect, radius, radius, Path.Direction.CW)
            }

            if (prefs.islandShadow) canvas.drawPath(shape, shadow)
            canvas.drawPath(shape, bg)
            canvas.save()
            canvas.clipPath(shape)
            drawIdleChrome(canvas, now, hole)
            if (greetingOn && !greetingRect.isEmpty) {
                ui.centered(
                    c = canvas,
                    s = "Hi, ${prefs.greetingName}",
                    p = ui.compact,
                    cx = greetingRect.centerX(),
                    cy = greetingRect.centerY(),
                    width = greetingRect.width() - ui.s16,
                    a = 1f,
                )
            }
            canvas.restore()
        } else {
            main.removeCallbacks(repaintBadge)
            badgePace = 0L
            drawHiddenHint(canvas, simW / 2f, simH * 0.62f, context.getString(R.string.preview_capsule_off))
        }

        drawCameraLens(canvas, hole)
        canvas.restore()
    }

    private fun drawLandscape(canvas: Canvas) {
        val simW = ui.dp(760f)
        val scale = screenRect.width() / simW
        if (scale <= 0f) return
        val simH = screenRect.height() / scale

        canvas.save()
        canvas.translate(screenRect.left, screenRect.top)
        canvas.scale(scale, scale)

        if (!prefs.notchBar) {
            drawHiddenHint(canvas, simW / 2f, simH * 0.5f, context.getString(R.string.preview_notch_off))
            canvas.restore()
            return
        }

        val nw = Notch.length(ui, prefs, simW)
        val nh = Notch.depth(ui, prefs, simH)
        val left = (simW - nw) / 2f
        notchRect.set(left, 0f, left + nw, nh)

        val r = min(nh * Notch.CORNER, min(nw, nh) / 2f)
        val flare = min(ui.glassFlare, min(nw / 2f, nh))
        shape.reset()
        ui.block(shape, notchRect, r, flare)
        canvas.drawPath(shape, bg)
        canvas.save()
        canvas.clipPath(shape)
        val eh = Notch.eyeHeight(prefs, eye, nh, nw)
        if (eh > 0f) eye.draw(canvas, notchRect.centerX(), notchRect.centerY(), eh, 1f)
        canvas.restore()

        canvas.restore()
    }

    private fun drawPortraitStatus(canvas: Canvas, simW: Float, cy: Float) {
        val fg = Color.argb(165, 22, 28, 40)
        statusPaint.color = fg
        statusPaint.textSize = ui.sp(12.5f)
        statusPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        statusPaint.textAlign = Paint.Align.LEFT
        val c = java.util.Calendar.getInstance()
        val time = "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        canvas.drawText(time, ui.dp(24f), ui.baseline(statusPaint, cy), statusPaint)

        // Subtle status bar battery glyph on the right edge
        statusIconPaint.color = fg
        statusIconPaint.style = Paint.Style.STROKE
        statusIconPaint.strokeWidth = ui.dp(1.4f)
        val bh = ui.dp(9.5f)
        val bw = ui.dp(18f)
        val bRight = simW - ui.dp(24f)
        box.set(bRight - bw, cy - bh / 2f, bRight, cy + bh / 2f)
        canvas.drawRoundRect(box, ui.dp(2.5f), ui.dp(2.5f), statusIconPaint)
        statusIconPaint.style = Paint.Style.FILL
        val fillInset = ui.dp(2f)
        val lvl = batteryLevel.coerceIn(15, 100) / 100f
        canvas.drawRoundRect(
            box.left + fillInset,
            box.top + fillInset,
            box.left + fillInset + (box.width() - fillInset * 2f) * lvl,
            box.bottom - fillInset,
            ui.dp(1f),
            ui.dp(1f),
            statusIconPaint,
        )
    }

    private fun drawCameraLens(canvas: Canvas, h: RectF) {
        lens.color = Color.rgb(7, 7, 10)
        canvas.drawOval(h, lens)
        lensRing.color = Color.argb(65, 95, 115, 155)
        lensRing.strokeWidth = ui.dp(1.2f)
        box.set(h)
        box.inset(ui.dp(3.5f), ui.dp(3.5f))
        canvas.drawOval(box, lensRing)
        lens.color = Color.argb(90, 140, 170, 220)
        canvas.drawCircle(h.centerX() - h.width() * 0.14f, h.centerY() - h.height() * 0.14f, ui.dp(1.3f), lens)
    }

    private fun drawIdleChrome(canvas: Canvas, cap: RectF, lensHole: RectF) {
        val capH = cap.height()
        if (capH <= 0f) return
        val room = (cap.width() - lensHole.width()) / 2f - ui.s8
        if (room <= 0f) return
        val widest = max(Ui.LOGO_ASPECT, Ui.BATTERY_SLOT_ASPECT)
        val h = min(ui.dp(13f), (room - capH / 2f - ui.dp(1f)) / (widest - 0.5f))
        if (h < ui.dp(7f)) return

        val rr = capH / 2f
        val bulge = rr - sqrt(max(0f, rr * rr - (h / 2f) * (h / 2f)))
        val inset = max((capH - h) / 2f, bulge + ui.s4)
        val top = cap.centerY() - h / 2f

        val logoL = cap.left + inset
        if (prefs.logoMode == "author" && logoL + h * Ui.LOGO_ASPECT <= lensHole.left - ui.s8) {
            box.set(logoL, top, logoL + h * Ui.LOGO_ASPECT, top + h)
            ui.logo(canvas, box, if (ui.coloured) ui.lead else ui.ink, 1f)
        }

        val badge = Badge.of(prefs)
        val pace = badges.pace(badge)
        if (pace != badgePace) {
            badgePace = pace
            armBadge()
        }
        if (badge == Badge.None) return
        val right = cap.right - inset
        val slotW = h * Ui.BATTERY_SLOT_ASPECT
        if (!badge.textual && right - slotW < lensHole.right + ui.s8) return
        when (badge) {
            Badge.Battery -> if (batteryLevel >= 0) {
                box.set(right - ui.batteryWidth(h), top, right, top + h)
                ui.battery(canvas, box, batteryLevel, batteryCharging, 1f)
            }
            Badge.Circle -> if (batteryLevel >= 0) {
                box.set(right - h, top, right, top + h)
                ui.batteryRing(canvas, box, batteryLevel, batteryCharging, 1f)
            }
            Badge.Dot -> if (batteryLevel >= 0) {
                ui.batteryDot(canvas, right - h / 2f, cap.centerY(), h * DOT_R, batteryLevel, batteryCharging, 1f)
            }
            else -> {
                val s = badges.text(badge) ?: return
                val left = lensHole.right + ui.s8
                val textRoom = right - left
                if (textRoom < ui.dp(16f)) return
                val ellipsized = TextUtils.ellipsize(s, ui.compact, max(0f, textRoom), TextUtils.TruncateAt.END)
                ui.textEnd(canvas, ellipsized, ui.compact, right, ui.baseline(ui.compact, cap.centerY()), 1f)
            }
        }
    }

    private fun drawHiddenHint(canvas: Canvas, cx: Float, cy: Float, text: String) {
        hintText.textSize = ui.sp(12f)
        val padH = ui.dp(14f)
        val padV = ui.dp(7f)
        val tw = hintText.measureText(text)
        val fm = hintText.fontMetrics
        val th = fm.descent - fm.ascent
        box.set(cx - tw / 2f - padH, cy - th / 2f - padV, cx + tw / 2f + padH, cy + th / 2f + padV)
        val r = box.height() / 2f
        canvas.drawRoundRect(box, r, r, hintBg)
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, hintText)
    }

    private fun syncBattery() {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {
            null
        } ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level >= 0 && scale > 0) batteryLevel = (level * 100 / scale).coerceIn(0, 100)
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        eye.battery(batteryLevel, batteryCharging)
    }

    private fun armBadge() {
        main.removeCallbacks(repaintBadge)
        if (attached && !landscapeMode && prefs.idleBar && badgePace > 0L) {
            main.postDelayed(repaintBadge, badgePace)
        }
    }

    private fun syncLoop() {
        if (attached && landscapeMode && prefs.notchBar) {
            if (!ticking) {
                ticking = true
                lastFrame = 0L
                main.removeCallbacks(wake)
                Choreographer.getInstance().postFrameCallback(frame)
            }
        } else {
            stopLoop()
        }
    }

    private fun stopLoop() {
        ticking = false
        main.removeCallbacks(wake)
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    companion object {
        private const val DOT_R = 0.33f
    }
}
