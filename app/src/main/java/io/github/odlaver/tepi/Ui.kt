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
class Ui(context: Context, private val prefs: Prefs? = null) {
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

    // Tone.Dark from Tepi Windows, as the island wore it before themes.
    //
    // These are no longer constants: the theme moves them. Everything that
    // draws reads them per frame already, so a theme change is a reseed and a
    // repaint rather than a rebuilt kit — the paints below are mutated in
    // place for the same reason, since scenes hold references to them.
    //
    // ink, sub and faint stay white in every theme. A coloured ground is a
    // ground, not a licence to tint the text on it: the accent has a job and
    // body copy is not it.
    var ink = WHITE
        private set
    var sub = Color.argb(158, 245, 245, 247)
        private set
    var faint = Color.argb(92, 245, 245, 247)
        private set
    var fill = Color.argb(28, 255, 255, 255)
        private set
    var fillStrong = Color.argb(46, 255, 255, 255)
        private set
    var line = Color.argb(23, 255, 255, 255)
        private set
    // Opaque dark grey, a step up from the island's black. The tab bar sits on
    // the island itself, so a translucent white would read as a smudge against
    // whatever is behind the overlay; this is a flat tone instead.
    var tray = Color.rgb(34, 34, 36)
        private set

    // The island's own ground, and what the theme wants its lead surfaces
    // painted with. A lead surface is the one thing on a panel meant to be
    // looked at first: the primary button, the page in use, a lit tile. In a
    // monochrome theme that is white with black on it, exactly as before; in
    // a combination theme it is the accent.
    var back = Color.BLACK
        private set
    var lead = WHITE
        private set
    var onLead = Color.BLACK
        private set

    // Whether this theme has an accent at all, for the few places that want
    // to say something different when it does
    var coloured = false
        private set

    var theme: Theme = Theme.all[0]
        private set

    val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(14f); typeface = Typeface.DEFAULT_BOLD }
    val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sub; textSize = sp(12f) }
    val bodyInk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12f) }
    val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; textSize = sp(10f); fontFeatureSettings = "tnum" }
    val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD }
    // "Dark" by name but by role: the two paints used on top of a lead
    // surface. On a monochrome theme that surface is white and these are
    // black, which is where the names come from; on a combination theme they
    // follow whatever reads against the accent.
    val bodyDark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0); textSize = sp(12f) }
    val labelDark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD }
    val compact = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD; fontFeatureSettings = "tnum" }
    val lyric = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(12.5f); typeface = Typeface.DEFAULT_BOLD }
    val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(26f); typeface = Typeface.DEFAULT_BOLD; fontFeatureSettings = "tnum" }
    val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(16f); typeface = Typeface.DEFAULT_BOLD }

    init {
        reseed()
    }

    // Take the colours from the theme the user has picked.
    //
    // Called at build and again whenever the setting changes. The two fill
    // tiers are the interesting part: a dark theme has nothing to lift off
    // except itself, so a translucent white is right and is what the island
    // always used. A theme that names its own tiers has them because its
    // ground is a colour and white over a colour is a wash rather than a
    // step — Violet's rows are violet, not violet seen through gauze.
    fun reseed() {
        val t = Theme.of(prefs ?: return)
        adopt(t)
    }

    // Deliberately not called apply(). A member named apply shadows Kotlin's
    // own scope function for every call inside this class, and this class is
    // built almost entirely out of `Paint(...).apply { }` — the paints above
    // would stop compiling, and not at this line.
    private fun adopt(t: Theme) {
        theme = t
        back = t.back
        coloured = t.coloured
        lead = t.lead
        onLead = t.onLead
        ink = WHITE
        sub = Color.argb(158, 245, 245, 247)
        faint = Color.argb(92, 245, 245, 247)
        fill = if (t.tier != 0) t.tier else Color.argb(28, 255, 255, 255)
        fillStrong = if (t.lift != 0) t.lift else Color.argb(46, 255, 255, 255)
        line = Color.argb(23, 255, 255, 255)
        // The tab bar is a strip lying on the island, so it has to be a step
        // off it in the same direction the fills go. A theme with its own
        // tiers uses the upper one; a dark theme keeps the flat grey, which is
        // lighter than any of them.
        tray = if (t.lift != 0) t.lift else Color.rgb(34, 34, 36)
        title.color = ink
        body.color = sub
        bodyInk.color = ink
        small.color = faint
        label.color = ink
        compact.color = ink
        lyric.color = ink
        big.color = ink
        heading.color = ink
        labelDark.color = onLead
        bodyDark.color = alpha(onLead, 0.67f)
    }

    val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderM = Matrix()
    private val glyph = Path()
    private val tmp = RectF()
    private val cell = RectF()
    private val slot = RectF()
    private val fillA = Path()
    private val fillB = Path()
    private val corners = FloatArray(8)

    // How far a shape flares out where it meets the glass. One dimension for
    // the notch and for the landscape panel both: the flare belongs to the
    // junction with the bezel rather than to the size of whatever is hanging
    // off it, so it stays put while a panel grows past it.
    val glassFlare = dp(11f)

    // A block hanging from the top edge of the screen: the notch, and the
    // landscape panel it grows into.
    //
    // Square where it meets the glass, rounded on the two free corners below,
    // and flared outwards at the join. The top two corners must be square:
    // rounding them curves the shape away from the glass just where the flare
    // is sweeping outwards to meet it, and the two leave a notch of wallpaper
    // between them — the shape reads as holed rather than as joined. There is
    // nothing for a radius to do up there anyway, because that edge is off the
    // screen.
    //
    // The caller asks for a radius and a flare and gets the largest pair the
    // box can actually carry: the flare is also kept clear of the bottom
    // rounding, since a flare that reached past where the side starts curving
    // would meet it at an angle instead of running into it.
    fun block(out: Path, r: RectF, radius: Float, flare: Float) {
        if (r.width() < 1f || r.height() < 1f) return
        val rad = max(0f, min(radius, min(r.width(), r.height()) / 2f))
        corners[0] = 0f; corners[1] = 0f
        corners[2] = 0f; corners[3] = 0f
        corners[4] = rad; corners[5] = rad
        corners[6] = rad; corners[7] = rad
        out.addRoundRect(r, corners, Path.Direction.CW)
        flare(out, r, min(flare, min(r.width() / 2f, max(0f, r.height() - rad))))
    }

    // Concave flares at the top two corners of a shape attached to the top
    // edge of the screen.
    //
    // Not a rounded corner: a rounded corner curves away from the glass and
    // leaves a sliver of wallpaper in the gap, which is what makes a drawn
    // shape look stuck on top of the screen instead of cut out of it. This
    // curves the other way — the sides sweep outwards as they reach the edge
    // and meet it square, so the shape appears to grow out of the bezel. It is
    // the same fillet the island already uses where its neck meets its body,
    // turned to face the glass.
    //
    // Each corner is a square of side f sticking out from the shape, with a
    // disc of radius f taken out of its outer bottom corner. What is left is a
    // wedge with a concave edge, unioned onto the shape. Its arc leaves the
    // glass horizontally and meets the shape's side vertically, so both joins
    // are smooth as long as the side is still straight where it lands.
    private fun flare(out: Path, r: RectF, f: Float) {
        if (f < 1f) return
        for (side in intArrayOf(-1, 1)) {
            val x = if (side < 0) r.left else r.right
            fillA.reset()
            fillA.addRect(min(x, x + side * f), r.top, max(x, x + side * f), r.top + f, Path.Direction.CW)
            fillB.reset()
            fillB.addCircle(x + side * f, r.top + f, f, Path.Direction.CW)
            fillA.op(fillB, Path.Op.DIFFERENCE)
            out.op(fillA, Path.Op.UNION)
        }
    }

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

    // A bitmap stretched to fill a rectangle exactly, square-cornered.
    //
    // Not art(): that one centre-crops to preserve the aspect and rounds the
    // corners, which is right for album covers and wrong for map tiles. A tile
    // is a square of the world and has to land on its own square of canvas or
    // the grid does not line up with itself.
    fun image(c: Canvas, bmp: Bitmap, at: RectF, a: Float) {
        if (bmp.isRecycled || at.width() <= 0f || at.height() <= 0f) return
        img.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.drawBitmap(bmp, null, at, img)
    }

    // Chip: optional icon, then label; primary is painted with the theme's
    // lead colour, which is white on a monochrome theme and the accent on a
    // combination one. The text on it follows: black on a bright lead, white
    // on a dark one, decided once in Theme rather than guessed here.
    fun button(c: Canvas, r: RectF, text: String, a: Float, primary: Boolean = false, icon: Icon? = null) {
        fill(c, r, radiusButton, if (primary) lead else fill, a)
        val p = if (primary) labelDark else label
        val fg = if (primary) onLead else ink
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
        stroke.color = alpha(lead, a)
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
        bolt(glyph, cx, cy, size)
        c.drawPath(glyph, solid)
    }

    // The same lightning glyph as a path, for the battery mark that punches it
    // out of its own fill rather than drawing it on top
    private fun bolt(out: Path, cx: Float, cy: Float, size: Float) {
        val h = size / 2
        out.reset()
        out.moveTo(cx + h * 0.15f, cy - h)
        out.lineTo(cx - h * 0.55f, cy + h * 0.12f)
        out.lineTo(cx - h * 0.02f, cy + h * 0.12f)
        out.lineTo(cx - h * 0.15f, cy + h)
        out.lineTo(cx + h * 0.55f, cy - h * 0.12f)
        out.lineTo(cx + h * 0.02f, cy - h * 0.12f)
        out.close()
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
    //
    // Charging puts a bolt through it, the way the status bar does. The bolt
    // is punched out of the fill rather than drawn over it: a white bolt on a
    // green fill is two marks at the same place and at this size the smaller
    // one wins and the whole thing reads as a smudge. A hole in the fill reads
    // as a bolt at any size, because its edge is the contrast.
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
        val fr = slot.height() * 0.3f
        if (!charging) {
            solid.color = alpha(batteryTint(level, charging), a)
            c.drawRoundRect(slot, fr, fr, solid)
            return
        }
        // Charging: the fill with a bolt taken out of it. Both paths are
        // built in the shape scratch pads, so nothing is allocated per frame.
        fillA.reset()
        fillA.addRoundRect(slot, fr, fr, Path.Direction.CW)
        fillB.reset()
        bolt(fillB, cell.centerX(), cell.centerY(), cell.height() * BOLT_H)
        fillA.op(fillB, Path.Op.DIFFERENCE)
        solid.color = alpha(batteryTint(level, charging), a)
        c.drawPath(fillA, solid)
    }

    // Battery, as a ring rather than a shell.
    //
    // The arc is the level, swept clockwise from the top, on a grey track that
    // shows what is missing: a ring that only drew the filled part would be a
    // crescent of unknown size rather than a gauge. No figure and no per-cent
    // sign, which is the whole point of picking this over the battery — it is
    // a glance, not a reading. The colours are the system's own three, so a
    // green ring means the same thing a green shell did.
    fun batteryRing(c: Canvas, at: RectF, level: Int, charging: Boolean, a: Float) {
        val d = min(at.width(), at.height())
        if (d <= 0f) return
        val width = max(dp(1.4f), d * RING_W)
        val radius = (d - width) / 2
        if (radius <= 0f) return
        stroke.strokeWidth = width
        stroke.color = alpha(RING_TRACK, a)
        c.drawCircle(at.centerX(), at.centerY(), radius, stroke)
        val frac = level.coerceIn(0, 100) / 100f
        if (frac <= 0f) return
        stroke.color = alpha(batteryTint(level, charging), a)
        tmp.set(at.centerX() - radius, at.centerY() - radius, at.centerX() + radius, at.centerY() + radius)
        // A full battery is a closed circle, not an arc one degree short of
        // one: the round cap would leave a visible nick at the top
        if (frac >= 0.999f) c.drawCircle(at.centerX(), at.centerY(), radius, stroke)
        else c.drawArc(tmp, -90f, 360f * frac, false, stroke)
    }

    // Battery as one dot: green, amber or red, and nothing else. The smallest
    // thing that can still carry the warning.
    fun batteryDot(c: Canvas, cx: Float, cy: Float, radius: Float, level: Int, charging: Boolean, a: Float) {
        dot(c, cx, cy, radius, batteryTint(level, charging), a)
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
        private val WHITE = Color.rgb(245, 245, 247)

        // System greens, ambers and reds, same family as CallScene's
        private val GREEN = Color.rgb(52, 199, 89)
        private val AMBER = Color.rgb(255, 204, 0)
        private val RED = Color.rgb(255, 69, 58)

        // iOS battery shell proportions, width over height
        private const val BATTERY_ASPECT = 1.95f
        private const val NUB_GAP = 0.08f
        private const val NUB_W = 0.1f

        // The bolt punched through a charging battery, against the shell's
        // inner height. Taller than the shell on purpose: the glyph's own
        // bounding box is wider than the stroke it draws, and a bolt that fits
        // inside the fill with room to spare reads as a dash.
        private const val BOLT_H = 1.05f

        // The battery ring: how thick its stroke is against its diameter, and
        // the grey of the part that is empty. The track is a flat grey rather
        // than translucent white, so an empty ring reads the same over a
        // bright wallpaper as over a dark one.
        private const val RING_W = 0.17f
        private val RING_TRACK = Color.rgb(104, 104, 110)

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
