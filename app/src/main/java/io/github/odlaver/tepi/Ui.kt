package io.github.odlaver.tepi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import kotlin.math.max
import kotlin.math.min

// Design tokens and island drawing kit
class Ui(context: Context) {
    private val res = context.resources
    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, res.displayMetrics)
    fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, res.displayMetrics)

    // Spacing scale
    val s4 = dp(4f)
    val s8 = dp(8f)
    val s12 = dp(12f)
    val s16 = dp(16f)
    val s20 = dp(20f)
    val s24 = dp(24f)

    // Island frame
    val edge = s16
    val pad = s16
    val panelMax = dp(380f)
    val radiusPanel = dp(24f)
    val radiusRow = dp(14f)
    val radiusButton = dp(12f)
    val btnH = dp(36f)
    val iconS = dp(18f)

    // Tone.Dark from Tepi Windows
    val ink = Color.rgb(245, 245, 247)
    val sub = Color.argb(158, 245, 245, 247)
    val faint = Color.argb(92, 245, 245, 247)
    val fill = Color.argb(28, 255, 255, 255)
    val fillStrong = Color.argb(46, 255, 255, 255)
    val line = Color.argb(23, 255, 255, 255)

    val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(14f); typeface = Typeface.DEFAULT_BOLD }
    val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sub; textSize = sp(12f) }
    val bodyInk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12f) }
    val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; textSize = sp(10f); fontFeatureSettings = "tnum" }
    val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD }
    val labelDark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD }
    val compact = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD; fontFeatureSettings = "tnum" }
    val lyric = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12.5f); typeface = Typeface.DEFAULT_BOLD }
    val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(26f); typeface = Typeface.DEFAULT_BOLD; fontFeatureSettings = "tnum" }
    val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(16f); typeface = Typeface.DEFAULT_BOLD }

    val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderM = Matrix()
    private val glyph = Path()
    private val tmp = RectF()
    private val cell = RectF()
    private val slot = RectF()

    fun alpha(color: Int, a: Float) = Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun fill(c: Canvas, r: RectF, radius: Float, color: Int, a: Float) {
        solid.color = alpha(color, a)
        c.drawRoundRect(r, radius, radius, solid)
    }

    fun text(c: Canvas, s: CharSequence, p: TextPaint, x: Float, baseline: Float, width: Float, a: Float) {
        val t = TextUtils.ellipsize(s, p, max(0f, width), TextUtils.TruncateAt.END)
        val base = p.alpha
        p.alpha = (base * a).toInt()
        c.drawText(t, 0, t.length, x, baseline, p)
        p.alpha = base
    }

    // Right-aligned at x
    fun textEnd(c: Canvas, s: CharSequence, p: TextPaint, x: Float, baseline: Float, a: Float) {
        val base = p.alpha
        p.alpha = (base * a).toInt()
        c.drawText(s, 0, s.length, x - p.measureText(s, 0, s.length), baseline, p)
        p.alpha = base
    }

    // Vertically centred on cy
    fun centered(c: Canvas, s: CharSequence, p: TextPaint, cx: Float, cy: Float, width: Float, a: Float) {
        val t = TextUtils.ellipsize(s, p, max(0f, width), TextUtils.TruncateAt.END)
        val w = p.measureText(t, 0, t.length)
        val base = p.alpha
        p.alpha = (base * a).toInt()
        c.drawText(t, 0, t.length, cx - w / 2, cy - (p.ascent() + p.descent()) / 2, p)
        p.alpha = base
    }

    fun baseline(p: TextPaint, cy: Float) = cy - (p.ascent() + p.descent()) / 2

    fun art(c: Canvas, art: Bitmap?, at: RectF, a: Float, k: Float = 0.24f) {
        val radius = at.width() * k
        if (art == null) {
            fill(c, at, radius, fill, a)
            solid.color = alpha(sub, a)
            play(c, at.centerX(), at.centerY(), at.width() * 0.36f)
            return
        }
        val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val s = max(at.width() / art.width, at.height() / art.height)
        shaderM.setScale(s, s)
        shaderM.postTranslate(at.centerX() - art.width * s / 2, at.centerY() - art.height * s / 2)
        shader.setLocalMatrix(shaderM)
        img.shader = shader
        img.alpha = (255 * a).toInt()
        c.drawRoundRect(at, radius, radius, img)
        img.shader = null
    }

    fun icon(c: Canvas, i: Icon, cx: Float, cy: Float, size: Float, color: Int, a: Float) = Icons.draw(c, i, cx, cy, size, alpha(color, a))

    // Chip: optional icon, then label; primary is ink-filled
    fun button(c: Canvas, r: RectF, text: String, a: Float, primary: Boolean = false, icon: Icon? = null) {
        fill(c, r, radiusButton, if (primary) ink else fill, a)
        val p = if (primary) labelDark else label
        val fg = if (primary) Color.BLACK else ink
        if (icon == null) { centered(c, text, p, r.centerX(), r.centerY(), r.width() - s16, a); return }
        if (text.isEmpty()) { this.icon(c, icon, r.centerX(), r.centerY(), iconS, fg, a); return }
        val gap = dp(6f)
        val room = r.width() - s16 - iconS - gap
        val t = TextUtils.ellipsize(text, p, max(0f, room), TextUtils.TruncateAt.END)
        val w = iconS + gap + p.measureText(t, 0, t.length)
        val x = r.centerX() - w / 2
        this.icon(c, icon, x + iconS / 2, r.centerY(), iconS, fg, a)
        val base = p.alpha
        p.alpha = (255 * a).toInt()
        c.drawText(t, 0, t.length, x + iconS + gap, baseline(p, r.centerY()), p)
        p.alpha = base
    }

    // Progress ring, frac 0..1 clockwise from top
    fun ring(c: Canvas, cx: Float, cy: Float, radius: Float, width: Float, frac: Float, a: Float) {
        stroke.strokeWidth = width
        stroke.color = alpha(fill, a * 1.6f)
        c.drawCircle(cx, cy, radius, stroke)
        if (frac <= 0f) return
        stroke.color = alpha(ink, a)
        tmp.set(cx - radius, cy - radius, cx + radius, cy + radius)
        c.drawArc(tmp, -90f, 360f * frac.coerceIn(0f, 1f), false, stroke)
    }

    // Glyphs use solid's current colour
    fun play(c: Canvas, cx: Float, cy: Float, size: Float) {
        glyph.reset()
        val h = size * 0.5f
        glyph.moveTo(cx - h * 0.7f, cy - h)
        glyph.lineTo(cx + h, cy)
        glyph.lineTo(cx - h * 0.7f, cy + h)
        glyph.close()
        c.drawPath(glyph, solid)
    }

    fun pause(c: Canvas, cx: Float, cy: Float, size: Float) {
        val w = size * 0.26f
        val h = size * 0.5f
        val g = size * 0.14f
        tmp.set(cx - g - w, cy - h, cx - g, cy + h)
        c.drawRoundRect(tmp, w * 0.3f, w * 0.3f, solid)
        tmp.set(cx + g, cy - h, cx + g + w, cy + h)
        c.drawRoundRect(tmp, w * 0.3f, w * 0.3f, solid)
    }

    fun skip(c: Canvas, cx: Float, cy: Float, size: Float, back: Boolean) {
        val h = size * 0.42f
        val dir = if (back) -1f else 1f
        glyph.reset()
        glyph.moveTo(cx - dir * h, cy - h)
        glyph.lineTo(cx + dir * h * 0.6f, cy)
        glyph.lineTo(cx - dir * h, cy + h)
        glyph.close()
        c.drawPath(glyph, solid)
        val bx = cx + dir * h * 0.7f
        val bw = size * 0.11f
        tmp.set(min(bx, bx + dir * bw), cy - h, max(bx, bx + dir * bw), cy + h)
        c.drawRoundRect(tmp, bw / 2, bw / 2, solid)
    }

    fun stop(c: Canvas, cx: Float, cy: Float, size: Float) {
        val h = size * 0.36f
        tmp.set(cx - h, cy - h, cx + h, cy + h)
        c.drawRoundRect(tmp, h * 0.3f, h * 0.3f, solid)
    }

    fun bolt(c: Canvas, cx: Float, cy: Float, size: Float) {
        val h = size / 2
        glyph.reset()
        glyph.moveTo(cx + h * 0.15f, cy - h)
        glyph.lineTo(cx - h * 0.55f, cy + h * 0.12f)
        glyph.lineTo(cx - h * 0.02f, cy + h * 0.12f)
        glyph.lineTo(cx - h * 0.15f, cy + h)
        glyph.lineTo(cx + h * 0.55f, cy - h * 0.12f)
        glyph.lineTo(cx + h * 0.02f, cy - h * 0.12f)
        glyph.close()
        c.drawPath(glyph, solid)
    }

    fun dot(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float) {
        solid.color = alpha(color, a)
        c.drawCircle(cx, cy, r, solid)
    }

    // Nelsen mark, from assets/nelsen-logo.svg. The source is a single closed
    // polygon of straight segments, so these are its points normalised to its
    // own bounding box: an exact copy, not an approximation. Keep them in sync
    // with the SVG if the mark is ever redrawn.
    fun logo(c: Canvas, at: RectF, color: Int, a: Float) {
        if (at.width() <= 0f || at.height() <= 0f) return
        glyph.reset()
        var i = 0
        while (i < LOGO.size) {
            val x = at.left + LOGO[i] * at.width()
            val y = at.top + LOGO[i + 1] * at.height()
            if (i == 0) glyph.moveTo(x, y) else glyph.lineTo(x, y)
            i += 2
        }
        glyph.close()
        solid.color = alpha(color, a)
        c.drawPath(glyph, solid)
    }

    // Battery, iOS style: rounded shell, nub on the right, fill inside.
    // Level is 0..100; the fill keeps a visible sliver so a nearly empty
    // battery still reads as a battery.
    fun battery(c: Canvas, at: RectF, level: Int, charging: Boolean, a: Float) {
        if (at.height() <= 0f) return
        val h = at.height()
        val nubGap = h * NUB_GAP
        val nubW = max(dp(1f), h * NUB_W)
        val shellW = h * BATTERY_ASPECT
        // Sit the nub flush against the right edge of the slot
        val right = at.right - nubW - nubGap
        cell.set(right - shellW, at.top, right, at.bottom)
        val radius = h * 0.32f
        val line = max(dp(1f), h * 0.085f)

        // Shell outline, inset by half the stroke so it stays inside the slot
        cell.inset(line / 2, line / 2)
        stroke.strokeWidth = line
        stroke.color = alpha(ink, a * 0.45f)
        c.drawRoundRect(cell, radius, radius, stroke)

        // Nub
        val nubH = h * 0.34f
        solid.color = alpha(ink, a * 0.45f)
        slot.set(at.right - nubW, at.centerY() - nubH / 2, at.right, at.centerY() + nubH / 2)
        c.drawRoundRect(slot, nubW / 2, nubW / 2, solid)

        // Fill
        val inset = line * 1.1f
        val room = cell.width() - inset * 2
        val frac = level.coerceIn(0, 100) / 100f
        val w = max(room * 0.08f, room * frac)
        slot.set(cell.left + inset, cell.top + inset, cell.left + inset + w, cell.bottom - inset)
        solid.color = alpha(batteryTint(level, charging), a)
        val fr = slot.height() * 0.3f
        c.drawRoundRect(slot, fr, fr, solid)
    }

    // Matches what Android itself signals: green while charging, amber at the
    // 20% "low" warning, red at the 10% "critical" one, plain ink otherwise
    fun batteryTint(level: Int, charging: Boolean) = when {
        charging -> GREEN
        level <= 10 -> RED
        level <= 20 -> AMBER
        else -> ink
    }

    // How wide the battery wants to be for a given row height: shell, the gap,
    // and the nub. The nub ends flush with the right edge of the slot.
    fun batteryWidth(h: Float) = h * BATTERY_ASPECT + h * NUB_GAP + max(dp(1f), h * NUB_W)

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        // System greens, ambers and reds, same family as CallScene's
        private val GREEN = Color.rgb(52, 199, 89)
        private val AMBER = Color.rgb(255, 204, 0)
        private val RED = Color.rgb(255, 69, 58)

        // iOS battery shell proportions, width over height
        private const val BATTERY_ASPECT = 1.95f
        private const val NUB_GAP = 0.08f
        private const val NUB_W = 0.1f

        // assets/nelsen-logo.svg, normalised to its content bounding box
        private val LOGO = floatArrayOf(
            0.000000f, 0.965979f,
            0.554167f, 0.000000f,
            0.663450f, 0.581558f,
            0.783082f, 0.372218f,
            1.000000f, 0.372112f,
            0.638729f, 1.000000f,
            0.554626f, 0.554553f,
            0.318777f, 0.966474f,
        )

        // Logo width over height, from the same bounding box
        const val LOGO_ASPECT = 2.047829f

        // Battery slot width over height, for callers reserving room before
        // they know the final height
        const val BATTERY_SLOT_ASPECT = BATTERY_ASPECT + NUB_GAP + NUB_W
    }
}
