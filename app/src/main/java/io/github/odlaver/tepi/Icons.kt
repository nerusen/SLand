package io.github.odlaver.tepi

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class Icon {
    Play, Pause, Next, Prev, Stop, Plus, Check, Close, Timer, Note, Gear, Home, Hide, Copy, Share, Open, Reply,
    Flag, Music, Lyrics, Bell, Moon, Drop, Eye, Stretch, Bolt, Phone, Headphones, Download, Nav, Camera, Shield,
    Book, Tasks, Shelf, Info, Code, User, Battery, Layers, Hand, Power, Chat, Link, Pin, Trash, Laptop, Globe, Coffee, Chevron, Spark,
    Wifi, Bluetooth, Github, Volume, Mute, Image, Flash, Hotspot, Plane, Leaf, BellOff, Lock, Grid, Palette, Signal,
    Chart, Kanban, Purse
}

// Line icons on a 24-unit grid, drawn with the Ink stroke
object Icons {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        c.save()
        c.translate(cx - 12 * k, cy - 12 * k)
        c.scale(k, k)
        p.color = color
        p.strokeWidth = 2f
        p.style = Paint.Style.STROKE
        shape(c, icon)
        c.restore()
    }

    private fun fill() { p.style = Paint.Style.FILL }
    private fun line() { p.style = Paint.Style.STROKE }
    private fun ln(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
    private fun rr(c: Canvas, l: Float, t: Float, rt: Float, b: Float, rad: Float) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, p) }
    private fun poly(c: Canvas, vararg xy: Float, close: Boolean = false) {
        path.reset()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, p)
    }

    private fun shape(c: Canvas, i: Icon) {
        when (i) {
            Icon.Play -> { fill(); poly(c, 7f, 4.5f, 19f, 12f, 7f, 19.5f, close = true) }
            Icon.Pause -> { fill(); rr(c, 6f, 4.5f, 10f, 19.5f, 1.2f); rr(c, 14f, 4.5f, 18f, 19.5f, 1.2f) }
            Icon.Next -> { fill(); poly(c, 5f, 5f, 15f, 12f, 5f, 19f, close = true); rr(c, 16.5f, 5f, 19f, 19f, 1f) }
            Icon.Prev -> { fill(); poly(c, 19f, 5f, 9f, 12f, 19f, 19f, close = true); rr(c, 5f, 5f, 7.5f, 19f, 1f) }
            Icon.Stop -> { fill(); rr(c, 6f, 6f, 18f, 18f, 2.5f) }
            Icon.Plus -> { ln(c, 12f, 5f, 12f, 19f); ln(c, 5f, 12f, 19f, 12f) }
            Icon.Check -> poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Icon.Close -> { ln(c, 6f, 6f, 18f, 18f); ln(c, 18f, 6f, 6f, 18f) }
            Icon.Timer -> { c.drawCircle(12f, 13.5f, 7.5f, p); ln(c, 12f, 13.5f, 12f, 9.5f); ln(c, 10f, 3f, 14f, 3f); ln(c, 18.5f, 6f, 19.8f, 4.8f) }
            Icon.Note -> { poly(c, 15f, 4f, 20f, 9f, 9f, 20f, 4f, 20f, 4f, 15f, close = true); ln(c, 13f, 6f, 18f, 11f) }
            Icon.Gear -> {
                c.drawCircle(12f, 12f, 3f, p)
                for (a in 0 until 8) {
                    val ang = Math.toRadians(a * 45.0)
                    ln(c, 12f + 6.2f * Math.cos(ang).toFloat(), 12f + 6.2f * Math.sin(ang).toFloat(), 12f + 8.6f * Math.cos(ang).toFloat(), 12f + 8.6f * Math.sin(ang).toFloat())
                }
                c.drawCircle(12f, 12f, 6.2f, p)
            }
            Icon.Home -> { poly(c, 4f, 11f, 12f, 4f, 20f, 11f); poly(c, 6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f); rr(c, 10f, 14f, 14f, 20f, 1f) }
            Icon.Hide -> { path.reset(); path.moveTo(3f, 12f); path.quadTo(12f, 3f, 21f, 12f); path.quadTo(12f, 21f, 3f, 12f); c.drawPath(path, p); ln(c, 4f, 4f, 20f, 20f) }
            Icon.Eye -> { path.reset(); path.moveTo(3f, 12f); path.quadTo(12f, 3f, 21f, 12f); path.quadTo(12f, 21f, 3f, 12f); c.drawPath(path, p); c.drawCircle(12f, 12f, 2.8f, p) }
            Icon.Copy -> { rr(c, 8f, 8f, 19f, 19f, 2f); poly(c, 5f, 15f, 5f, 5f, 15f, 5f) }
            Icon.Share -> { poly(c, 12f, 15f, 12f, 3.5f); poly(c, 7.5f, 8f, 12f, 3.5f, 16.5f, 8f); poly(c, 5f, 12f, 5f, 20f, 19f, 20f, 19f, 12f) }
            Icon.Open -> { poly(c, 13f, 4f, 20f, 4f, 20f, 11f); ln(c, 20f, 4f, 11f, 13f); poly(c, 17f, 14f, 17f, 20f, 4f, 20f, 4f, 7f, 10f, 7f) }
            Icon.Reply -> { poly(c, 9f, 5f, 3.5f, 10.5f, 9f, 16f); path.reset(); path.moveTo(3.5f, 10.5f); path.lineTo(13f, 10.5f); path.quadTo(20.5f, 10.5f, 20.5f, 19f); c.drawPath(path, p) }
            Icon.Flag -> { ln(c, 6f, 21f, 6f, 4f); poly(c, 6f, 4.5f, 18f, 4.5f, 15f, 9f, 18f, 13.5f, 6f, 13.5f) }
            Icon.Music -> { ln(c, 9f, 17f, 9f, 5f); ln(c, 9f, 5f, 19f, 3.5f); ln(c, 19f, 3.5f, 19f, 15f); fill(); c.drawCircle(6.5f, 17.5f, 2.8f, p); c.drawCircle(16.5f, 15.5f, 2.8f, p) }
            Icon.Lyrics -> { ln(c, 4f, 7f, 20f, 7f); ln(c, 4f, 12f, 16f, 12f); ln(c, 4f, 17f, 12f, 17f) }
            Icon.Bell -> { path.reset(); path.moveTo(5f, 17f); path.lineTo(6.5f, 15f); path.lineTo(6.5f, 10.5f); path.quadTo(6.5f, 4.5f, 12f, 4.5f); path.quadTo(17.5f, 4.5f, 17.5f, 10.5f); path.lineTo(17.5f, 15f); path.lineTo(19f, 17f); path.close(); c.drawPath(path, p); ln(c, 10f, 20.5f, 14f, 20.5f) }
            Icon.Moon -> { path.reset(); path.moveTo(15f, 3.5f); path.cubicTo(8f, 4f, 5f, 12f, 9f, 17f); path.cubicTo(12f, 20.5f, 18f, 20.5f, 20.5f, 15.5f); path.cubicTo(14.5f, 17f, 10.5f, 10f, 15f, 3.5f); c.drawPath(path, p) }
            Icon.Drop -> { path.reset(); path.moveTo(12f, 3.5f); path.cubicTo(17f, 9f, 18.5f, 12f, 18.5f, 14.5f); path.cubicTo(18.5f, 18.5f, 15.5f, 20.5f, 12f, 20.5f); path.cubicTo(8.5f, 20.5f, 5.5f, 18.5f, 5.5f, 14.5f); path.cubicTo(5.5f, 12f, 7f, 9f, 12f, 3.5f); c.drawPath(path, p) }
            Icon.Stretch -> { c.drawCircle(12f, 4.8f, 1.8f, p); poly(c, 4f, 9f, 12f, 10f, 20f, 9f); poly(c, 12f, 10f, 12f, 14.5f); poly(c, 8f, 21f, 12f, 14.5f, 16f, 21f) }
            Icon.Bolt -> { fill(); poly(c, 13.5f, 2.5f, 5f, 13.5f, 11f, 13.5f, 10f, 21.5f, 19f, 10f, 13f, 10f, close = true) }
            Icon.Phone -> { path.reset(); path.moveTo(5f, 4f); path.lineTo(9f, 4f); path.lineTo(10.5f, 8.5f); path.lineTo(8.3f, 10f); path.quadTo(10f, 14f, 14f, 15.7f); path.lineTo(15.5f, 13.5f); path.lineTo(20f, 15f); path.lineTo(20f, 19f); path.quadTo(20f, 20.5f, 18f, 20.5f); path.quadTo(4.5f, 19.5f, 3.5f, 6f); path.quadTo(3.5f, 4f, 5f, 4f); path.close(); c.drawPath(path, p) }
            Icon.Headphones -> { path.reset(); path.moveTo(4f, 15f); path.lineTo(4f, 12f); path.quadTo(4f, 4f, 12f, 4f); path.quadTo(20f, 4f, 20f, 12f); path.lineTo(20f, 15f); c.drawPath(path, p); rr(c, 3.5f, 14f, 8f, 20.5f, 1.5f); rr(c, 16f, 14f, 20.5f, 20.5f, 1.5f) }
            Icon.Download -> { ln(c, 12f, 4f, 12f, 15f); poly(c, 7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); ln(c, 5f, 20f, 19f, 20f) }
            Icon.Nav -> { fill(); poly(c, 12f, 3f, 19.5f, 20f, 12f, 16f, 4.5f, 20f, close = true) }
            Icon.Camera -> { rr(c, 3f, 7f, 21f, 19f, 2.5f); poly(c, 8.5f, 7f, 10f, 4.5f, 14f, 4.5f, 15.5f, 7f); c.drawCircle(12f, 13f, 3.3f, p) }
            Icon.Shield -> { path.reset(); path.moveTo(12f, 3f); path.lineTo(19.5f, 6f); path.lineTo(19.5f, 11.5f); path.quadTo(19.5f, 17.5f, 12f, 21f); path.quadTo(4.5f, 17.5f, 4.5f, 11.5f); path.lineTo(4.5f, 6f); path.close(); c.drawPath(path, p); poly(c, 9f, 12f, 11.2f, 14.2f, 15f, 10f) }
            Icon.Book -> { poly(c, 12f, 6.5f, 12f, 20f); path.reset(); path.moveTo(12f, 6.5f); path.quadTo(8f, 4f, 3.5f, 5f); path.lineTo(3.5f, 18.5f); path.quadTo(8f, 17.5f, 12f, 20f); path.quadTo(16f, 17.5f, 20.5f, 18.5f); path.lineTo(20.5f, 5f); path.quadTo(16f, 4f, 12f, 6.5f); c.drawPath(path, p) }
            Icon.Tasks -> { poly(c, 4f, 7f, 5.5f, 8.5f, 8.5f, 5.5f); ln(c, 11.5f, 7f, 20f, 7f); poly(c, 4f, 15f, 5.5f, 16.5f, 8.5f, 13.5f); ln(c, 11.5f, 15f, 20f, 15f) }
            Icon.Shelf -> { poly(c, 3.5f, 13f, 7f, 5f, 17f, 5f, 20.5f, 13f); rr(c, 3.5f, 13f, 20.5f, 19.5f, 2f); poly(c, 3.5f, 13f, 9f, 13f, 10f, 15.5f, 14f, 15.5f, 15f, 13f, 20.5f, 13f) }
            Icon.Info -> { c.drawCircle(12f, 12f, 8.5f, p); ln(c, 12f, 11f, 12f, 16.5f); fill(); c.drawCircle(12f, 7.8f, 1.2f, p) }
            Icon.Code -> { poly(c, 8f, 7f, 3f, 12f, 8f, 17f); poly(c, 16f, 7f, 21f, 12f, 16f, 17f); ln(c, 13.5f, 5f, 10.5f, 19f) }
            Icon.User -> { c.drawCircle(12f, 8.5f, 3.8f, p); path.reset(); path.moveTo(4.5f, 20f); path.quadTo(5.5f, 14f, 12f, 14f); path.quadTo(18.5f, 14f, 19.5f, 20f); c.drawPath(path, p) }
            Icon.Battery -> { rr(c, 3f, 7.5f, 18.5f, 16.5f, 2f); ln(c, 21f, 10.5f, 21f, 13.5f); fill(); rr(c, 5.5f, 10f, 11f, 14f, 0.8f) }
            Icon.Layers -> { poly(c, 12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f, close = true); poly(c, 3f, 13.5f, 12f, 18.5f, 21f, 13.5f) }
            Icon.Hand -> { path.reset(); path.moveTo(8f, 12f); path.lineTo(8f, 5.5f); path.quadTo(8f, 4f, 9.5f, 4f); path.quadTo(11f, 4f, 11f, 5.5f); path.lineTo(11f, 11f); path.lineTo(11f, 4.5f); path.quadTo(11f, 3f, 12.5f, 3f); path.quadTo(14f, 3f, 14f, 4.5f); path.lineTo(14f, 11f); path.lineTo(14f, 6f); path.quadTo(14f, 4.5f, 15.5f, 4.5f); path.quadTo(17f, 4.5f, 17f, 6f); path.lineTo(17f, 14f); path.quadTo(17f, 20.5f, 11.5f, 20.5f); path.quadTo(8f, 20.5f, 5.5f, 16f); path.lineTo(4.5f, 13f); path.quadTo(4.5f, 11f, 6.5f, 11.5f); path.lineTo(8f, 13.5f); c.drawPath(path, p) }
            Icon.Power -> { ln(c, 12f, 3.5f, 12f, 11.5f); r.set(4.5f, 5.5f, 19.5f, 20.5f); c.drawArc(r, -55f, 290f, false, p) }
            Icon.Chat -> { path.reset(); path.moveTo(4f, 6f); path.quadTo(4f, 4f, 6f, 4f); path.lineTo(18f, 4f); path.quadTo(20f, 4f, 20f, 6f); path.lineTo(20f, 14f); path.quadTo(20f, 16f, 18f, 16f); path.lineTo(10f, 16f); path.lineTo(5.5f, 20f); path.lineTo(5.5f, 16f); path.quadTo(4f, 16f, 4f, 14f); path.close(); c.drawPath(path, p) }
            Icon.Link -> { rr(c, 3f, 9f, 13f, 15f, 3f); rr(c, 11f, 9f, 21f, 15f, 3f) }
            Icon.Pin -> { path.reset(); path.moveTo(12f, 21f); path.quadTo(5f, 14f, 5f, 9.5f); path.quadTo(5f, 3f, 12f, 3f); path.quadTo(19f, 3f, 19f, 9.5f); path.quadTo(19f, 14f, 12f, 21f); c.drawPath(path, p); c.drawCircle(12f, 9.5f, 2.5f, p) }
            Icon.Trash -> { ln(c, 4f, 6.5f, 20f, 6.5f); poly(c, 9f, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f, 15f, 6.5f); poly(c, 6f, 6.5f, 7f, 20.5f, 17f, 20.5f, 18f, 6.5f) }
            Icon.Laptop -> { rr(c, 5f, 5f, 19f, 15f, 1.5f); ln(c, 2.5f, 19f, 21.5f, 19f) }
            Icon.Globe -> { c.drawCircle(12f, 12f, 8.5f, p); ln(c, 3.5f, 12f, 20.5f, 12f); r.set(8f, 3.5f, 16f, 20.5f); c.drawOval(r, p) }
            Icon.Coffee -> { poly(c, 4f, 9f, 16f, 9f, 15f, 19f, 5f, 19f, close = true); path.reset(); path.moveTo(16f, 11f); path.quadTo(20.5f, 11f, 20.5f, 13.5f); path.quadTo(20.5f, 16f, 15.5f, 16f); c.drawPath(path, p); ln(c, 8f, 3.5f, 8f, 6f); ln(c, 12f, 3.5f, 12f, 6f) }
            Icon.Chevron -> poly(c, 9f, 5f, 16f, 12f, 9f, 19f)
            Icon.Spark -> { ln(c, 12f, 3f, 12f, 21f); ln(c, 3f, 12f, 21f, 12f); ln(c, 6f, 6f, 18f, 18f); ln(c, 18f, 6f, 6f, 18f) }
            // Three quarter-arcs around the dot they fan out from
            Icon.Wifi -> {
                for (rad in floatArrayOf(4.5f, 9f, 13.5f)) { r.set(12f - rad, 19.5f - rad, 12f + rad, 19.5f + rad); c.drawArc(r, 225f, 90f, false, p) }
                fill(); c.drawCircle(12f, 19.5f, 1.4f, p)
            }
            // The rune, drawn as one stroke
            Icon.Bluetooth -> poly(c, 6.5f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 6.5f, 16.5f)
            // GitHub's mark, from simple-icons' github.svg on the same 24 grid.
            // It is a solid brand mark, so it is filled rather than stroked.
            Icon.Github -> {
                fill()
                path.reset()
                path.moveTo(12f, 0.297f); path.cubicTo(5.37f, 0.297f, 0f, 5.67f, 0f, 12.297f); path.cubicTo(0f, 17.6f, 3.438f, 22.097f, 8.205f, 23.682f)
                path.cubicTo(8.805f, 23.795f, 9.025f, 23.424f, 9.025f, 23.105f); path.cubicTo(9.025f, 22.82f, 9.015f, 22.065f, 9.01f, 21.065f)
                path.cubicTo(5.672f, 21.789f, 4.968f, 19.455f, 4.968f, 19.455f); path.cubicTo(4.422f, 18.07f, 3.633f, 17.7f, 3.633f, 17.7f)
                path.cubicTo(2.546f, 16.956f, 3.717f, 16.971f, 3.717f, 16.971f); path.cubicTo(4.922f, 17.055f, 5.555f, 18.207f, 5.555f, 18.207f)
                path.cubicTo(6.625f, 20.042f, 8.364f, 19.512f, 9.05f, 19.205f); path.cubicTo(9.158f, 18.429f, 9.467f, 17.9f, 9.81f, 17.6f)
                path.cubicTo(7.145f, 17.3f, 4.344f, 16.268f, 4.344f, 11.67f); path.cubicTo(4.344f, 10.36f, 4.809f, 9.29f, 5.579f, 8.45f)
                path.cubicTo(5.444f, 8.147f, 5.039f, 6.927f, 5.684f, 5.274f); path.cubicTo(5.684f, 5.274f, 6.689f, 4.952f, 8.984f, 6.504f)
                path.cubicTo(9.944f, 6.237f, 10.964f, 6.105f, 11.984f, 6.099f); path.cubicTo(13.004f, 6.105f, 14.024f, 6.237f, 14.984f, 6.504f)
                path.cubicTo(17.264f, 4.952f, 18.269f, 5.274f, 18.269f, 5.274f); path.cubicTo(18.914f, 6.927f, 18.509f, 8.147f, 18.389f, 8.45f)
                path.cubicTo(19.154f, 9.29f, 19.619f, 10.36f, 19.619f, 11.67f); path.cubicTo(19.619f, 16.28f, 16.814f, 17.295f, 14.144f, 17.59f)
                path.cubicTo(14.564f, 17.95f, 14.954f, 18.686f, 14.954f, 19.81f); path.cubicTo(14.954f, 21.416f, 14.939f, 22.706f, 14.939f, 23.096f)
                path.cubicTo(14.939f, 23.411f, 15.149f, 23.786f, 15.764f, 23.666f); path.cubicTo(20.565f, 22.092f, 24f, 17.592f, 24f, 12.297f)
                path.cubicTo(24f, 5.67f, 18.627f, 0.297f, 12f, 0.297f); path.close()
                c.drawPath(path, p)
            }
            // Cone plus two waves; the mute twin swaps the waves for a cross
            Icon.Volume -> {
                fill(); poly(c, 3f, 9.5f, 7f, 9.5f, 11.5f, 5f, 11.5f, 19f, 7f, 14.5f, 3f, 14.5f, close = true)
                line()
                // Arc boxes are kept clear of the grid edge: the stroke is two
                // units wide and straddles the path, so a wave drawn out to 23
                // would have its outer half clipped
                r.set(8f, 8f, 17f, 16f); c.drawArc(r, -55f, 110f, false, p)
                r.set(6f, 5f, 21f, 19f); c.drawArc(r, -50f, 100f, false, p)
            }
            Icon.Mute -> {
                fill(); poly(c, 3f, 9.5f, 7f, 9.5f, 11.5f, 5f, 11.5f, 19f, 7f, 14.5f, 3f, 14.5f, close = true)
                line()
                ln(c, 14.5f, 9.5f, 20.5f, 14.5f); ln(c, 20.5f, 9.5f, 14.5f, 14.5f)
            }
            // Picture frame: sun in one corner, hills across the bottom
            Icon.Image -> {
                rr(c, 3f, 5f, 21f, 19f, 2.5f)
                c.drawCircle(8.5f, 9.5f, 1.6f, p)
                poly(c, 4.5f, 17.5f, 9.5f, 12.5f, 13f, 15.5f, 16f, 12f, 20f, 17.5f)
            }
            // Torch, head up, with the beam as three short rays
            Icon.Flash -> {
                rr(c, 8.5f, 6.5f, 15.5f, 10f, 1f)
                poly(c, 9.5f, 10f, 14.5f, 10f, 13.8f, 20.5f, 10.2f, 20.5f, close = true)
                ln(c, 12f, 2.5f, 12f, 4.5f); ln(c, 6.5f, 4f, 7.8f, 5.3f); ln(c, 17.5f, 4f, 16.2f, 5.3f)
            }
            // Access point: waves fanning off a dot, on a stand
            Icon.Hotspot -> {
                for (rad in floatArrayOf(5f, 8.5f)) { r.set(12f - rad, 11.5f - rad, 12f + rad, 11.5f + rad); c.drawArc(r, 215f, 110f, false, p) }
                ln(c, 12f, 13.5f, 12f, 19f); ln(c, 8.5f, 20.5f, 15.5f, 20.5f)
                fill(); c.drawCircle(12f, 11.5f, 1.8f, p)
            }
            // Airliner from above
            Icon.Plane -> {
                fill()
                poly(
                    c, 12f, 2.5f, 13.2f, 4f, 13.2f, 9.5f, 21f, 14.5f, 21f, 16.5f, 13.2f, 14f, 13.2f, 18.5f,
                    15.5f, 20.5f, 15.5f, 21.8f, 12f, 20.8f, 8.5f, 21.8f, 8.5f, 20.5f, 10.8f, 18.5f, 10.8f, 14f,
                    3f, 16.5f, 3f, 14.5f, 10.8f, 9.5f, 10.8f, 4f, close = true,
                )
            }
            // Leaf, the mark Android itself uses for battery saver
            Icon.Leaf -> {
                path.reset()
                path.moveTo(4.5f, 19.5f)
                path.cubicTo(3.5f, 10f, 10f, 3.5f, 19.5f, 4f)
                path.cubicTo(20f, 13.5f, 14f, 20.5f, 4.5f, 19.5f)
                path.close()
                c.drawPath(path, p)
                ln(c, 5.5f, 18.5f, 16f, 8f)
            }
            // Bell, struck through: silent and do not disturb
            Icon.BellOff -> {
                path.reset()
                path.moveTo(5f, 17f); path.lineTo(6.5f, 15f); path.lineTo(6.5f, 10.5f)
                path.quadTo(6.5f, 4.5f, 12f, 4.5f); path.quadTo(17.5f, 4.5f, 17.5f, 10.5f)
                path.lineTo(17.5f, 15f); path.lineTo(19f, 17f); path.close()
                c.drawPath(path, p)
                ln(c, 10f, 20.5f, 14f, 20.5f)
                ln(c, 4f, 3.5f, 20f, 20.5f)
            }
            // Padlock, shut: the body as a rounded box and the shackle as an
            // arch on top of it
            Icon.Lock -> {
                rr(c, 5f, 10.5f, 19f, 20.5f, 2.5f)
                path.reset()
                path.moveTo(8.5f, 10.5f)
                path.lineTo(8.5f, 8f)
                path.quadTo(8.5f, 3.5f, 12f, 3.5f)
                path.quadTo(15.5f, 3.5f, 15.5f, 8f)
                path.lineTo(15.5f, 10.5f)
                c.drawPath(path, p)
                fill(); c.drawCircle(12f, 15.5f, 1.6f, p)
            }
            // Nine squares: the mark for a grid of apps
            Icon.Grid -> {
                for (row in 0 until 3) for (col in 0 until 3) {
                    val x = 4.5f + col * 5.25f
                    val y = 4.5f + row * 5.25f
                    rr(c, x, y, x + 3.5f, y + 3.5f, 1f)
                }
            }
            // An artist's palette: the blob with a thumb hole and three wells
            Icon.Palette -> {
                path.reset()
                path.moveTo(12f, 3.5f)
                path.cubicTo(17.5f, 3.5f, 21f, 7f, 21f, 11.5f)
                path.cubicTo(21f, 15f, 18f, 15.5f, 16f, 15.5f)
                path.cubicTo(14.5f, 15.5f, 13.5f, 16.5f, 13.5f, 18f)
                path.cubicTo(13.5f, 19.5f, 12.5f, 20.5f, 11f, 20.5f)
                path.cubicTo(6.5f, 20.5f, 3f, 16.5f, 3f, 12f)
                path.cubicTo(3f, 7f, 6.5f, 3.5f, 12f, 3.5f)
                c.drawPath(path, p)
                fill()
                c.drawCircle(8f, 9f, 1.4f, p)
                c.drawCircle(12.5f, 7.5f, 1.4f, p)
                c.drawCircle(16.5f, 10f, 1.4f, p)
            }
            // Four climbing bars, the way a phone shows cellular signal. This
            // is mobile data rather than the hotspot that shares it, and the
            // two are different things: one is the radio, the other is giving
            // it away.
            Icon.Signal -> {
                fill()
                var x = 4f
                for (h in floatArrayOf(5f, 8.5f, 12f, 15.5f)) {
                    rr(c, x, 19.5f - h, x + 3.2f, 19.5f, 1f)
                    x += 4.6f
                }
            }
            // Axes with a rising line and a marker at each vertex: the shape
            // used for a trend, not for the bars of Signal
            Icon.Chart -> {
                poly(c, 4f, 3.5f, 4f, 20f, 20.5f, 20f)
                poly(c, 7f, 15.5f, 11f, 11f, 14.5f, 13.5f, 19.5f, 6.5f)
                fill()
                c.drawCircle(11f, 11f, 1.3f, p)
                c.drawCircle(19.5f, 6.5f, 1.3f, p)
            }
            // Three columns of unequal fill: a board whose lanes hold
            // different amounts of work
            Icon.Kanban -> {
                rr(c, 3f, 4.5f, 21f, 19.5f, 2.5f)
                ln(c, 9f, 4.5f, 9f, 19.5f)
                ln(c, 15f, 4.5f, 15f, 19.5f)
                fill()
                rr(c, 4.5f, 7f, 7.5f, 13f, 0.8f)
                rr(c, 10.5f, 7f, 13.5f, 16.5f, 0.8f)
                rr(c, 16.5f, 7f, 19.5f, 10f, 0.8f)
            }
            // A folded billfold with the clasp as a dot on its inner edge
            Icon.Purse -> {
                rr(c, 3f, 6.5f, 21f, 19f, 2.5f)
                path.reset()
                path.moveTo(3f, 9.5f)
                path.lineTo(16f, 9.5f)
                path.quadTo(17.5f, 9.5f, 17.5f, 11f)
                path.lineTo(17.5f, 14.5f)
                path.quadTo(17.5f, 16f, 16f, 16f)
                path.lineTo(3f, 16f)
                c.drawPath(path, p)
                fill(); c.drawCircle(14.5f, 12.75f, 1.3f, p)
            }
        }
        line()
    }
}

// Icon as a Drawable; optional rounded tile behind it
class IconDrawable(private val icon: Icon, private val color: Int, private val sizePx: Int, private val tile: Int = 0, private val radius: Float = 0f) : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (tile != 0) {
            bg.color = tile
            box.set(b)
            canvas.drawRoundRect(box, radius, radius, bg)
            Icons.draw(canvas, icon, b.exactCenterX(), b.exactCenterY(), s * 0.6f, color)
        } else Icons.draw(canvas, icon, b.exactCenterX(), b.exactCenterY(), s, color)
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
