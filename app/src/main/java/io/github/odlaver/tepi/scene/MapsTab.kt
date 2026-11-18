package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.Maps
import io.github.odlaver.tepi.Prefs
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Steps
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// Where the phone is, and how far it has walked today.
//
// The map is a 16:6 letterbox, which is the shape that fits across a panel
// without turning it into a page: tall enough to show the streets around the
// dot, short enough that the figures underneath are on screen at the same
// time. Underneath it, four readings — distance, steps, time moving, and the
// energy that cost — because a distance on its own does not answer the
// question people actually open this for.
//
// Grey rather than colour, and drawn rather than photographed. A monochrome
// basemap is about fifteen small PNGs per view and reads at a glance; an
// aerial view is megabytes and tells a walker less. The one coloured thing on
// it is the dot, which is the point of looking.
class MapsTab(
    host: Host,
    private val maps: Maps,
    private val steps: Steps,
    private val prefs: Prefs,
    private val settings: () -> Unit,
    private val grant: () -> Unit,
) : TabScene(host) {
    override val id = "maps"
    // Where somebody is, and how far they walked: not for a locked phone
    override val personal = true
    override val priority = 8
    override val order = 10

    // How far in. Street level by default: a neighbourhood is what a walking
    // distance means, and a city at this aspect is a grey smear.
    private var zoom = ZOOM_DEFAULT
    private val frame = RectF()
    private val cell = RectF()
    private val hitIn = RectF()
    private val hitOut = RectF()
    private var openedAt = 0L

    // A phone with no step counter and no location permission has nothing to
    // put on this page at all, so it is not listed; one with either gets the
    // page, and whichever half is missing says what it needs
    override fun tab() = prefs.steps && (steps.hasCounter() || steps.canLocate())

    // The map only asks for fixes while it is the page being looked at. A
    // panel closing is reported by the island through its closers list; a
    // swipe to another page is reported by away() being called on every tab
    // but this one. So this is simply "I am being drawn", and the stopping is
    // somebody else's job — which is the right way round, because a page
    // cannot notice that it has stopped being drawn.
    private fun looking() {
        openedAt = SystemClock.uptimeMillis()
        if (!steps.tracking) {
            steps.locate(true)
            steps.poke()
        }
    }

    // Called by the island when the panel closes or the page changes. Also
    // the moment the decoded tiles are dropped: the files stay on disk, so
    // coming back is a decode rather than a download, and the bitmaps are the
    // expensive thing to keep.
    fun away() {
        steps.locate(false)
        maps.compact()
        openedAt = 0L
    }

    private fun mapH(w: Float) = w * MAP_H / MAP_W

    override fun panelHeight(w: Float): Float {
        val head = ui.dp(18f) + ui.s12
        if (!steps.canLocate()) return head + ui.dp(36f) + ui.s12 + ui.btnH
        var h = head + mapH(w) + ui.s12
        h += ui.dp(STAT_H)
        h += ui.s12 + ui.btnH
        return h
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        var y = head(c, r, Icon.Pin, host.ctx.getString(R.string.tab_maps), null, a)
        if (!steps.canLocate()) {
            empty(c, r, y, host.ctx.getString(R.string.maps_need), a)
            actions(c, r, y + ui.dp(36f) + ui.s12, listOf(
                Action(host.ctx.getString(R.string.grant), true, Icon.Pin) { grant(); host.collapse() },
            ), a)
            return
        }
        looking()
        val h = mapH(r.width())
        frame.set(r.left, y, r.right, y + h)
        drawMap(c, frame, a)
        y = frame.bottom + ui.s12
        cell.set(r.left, y, r.right, y + ui.dp(STAT_H))
        drawStats(c, cell, a)
        y = cell.bottom + ui.s12
        actions(c, r, y, listOf(
            Action(host.ctx.getString(R.string.maps_open), true, Icon.Nav) { host.open(openHere()); host.collapse() },
            Action(host.ctx.getString(R.string.maps_body), false, Icon.Gear) { settings(); host.collapse() },
        ), a)
    }

    // The position, handed to whatever map app the phone has.
    //
    // A geo: URI with a q of the same coordinates, not just the coordinates.
    // geo:lat,lon alone centres the map there and marks nothing, which looks
    // like the app opened on the wrong screen; the q parameter is what puts a
    // pin on the spot, and every map app on Android understands it. The zoom
    // is carried across too, so the app opens at the scale that was being
    // looked at rather than at its own default.
    //
    // With no fix yet there is nothing to point at, so this falls back to the
    // map app's own idea of where it is: geo:0,0 is the documented way of
    // saying "open, I have no opinion".
    private fun openHere(): android.content.Intent {
        val loc = steps.here
        val uri = if (loc == null) "geo:0,0"
        else "geo:%f,%f?q=%f,%f&z=%d".format(
            java.util.Locale.US, loc.latitude, loc.longitude, loc.latitude, loc.longitude, zoom,
        )
        return android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
    }

    // The basemap, the dot, the scale and the credit.
    //
    // The grid is worked out from the centre outwards: the tile the centre
    // falls in, how far into that tile it is, and then enough tiles either
    // side to cover the frame. Missing tiles are simply not drawn — the ground
    // under them is the island's own fill, so a map still loading reads as a
    // map loading rather than as a hole.
    private fun drawMap(c: Canvas, at: RectF, a: Float) {
        val radius = ui.dp(12f)
        ui.fill(c, at, radius, ui.fill, a)
        val loc = steps.here
        if (loc == null) {
            ui.centered(c, host.ctx.getString(R.string.maps_finding), ui.body, at.centerX(), at.centerY(), at.width() - ui.s24, a)
            return
        }
        // Retina tiles: one tile is TILE map pixels, and the image behind it
        // is twice that. Drawn at the map size, so the extra pixels are
        // sharpness rather than scale.
        val scale = ui.dp(1f).coerceAtLeast(1f)
        val side = Maps.TILE * scale
        val cx = Maps.xOf(loc.longitude, zoom)
        val cy = Maps.yOf(loc.latitude, zoom)
        // Where the centre tile's own top-left corner lands on the canvas
        val originX = at.centerX() - (cx - floor(cx)).toFloat() * side
        val originY = at.centerY() - (cy - floor(cy)).toFloat() * side
        val left = ceil((originX - at.left) / side).toInt()
        val right = ceil((at.right - originX) / side).toInt()
        val up = ceil((originY - at.top) / side).toInt()
        val down = ceil((at.bottom - originY) / side).toInt()

        c.save()
        // Rounded corners on the map itself, so the tiles do not square off
        // the panel's own shape
        tmp.set(at)
        clip(c, tmp, radius)
        for (dx in -left..right) for (dy in -up..down) {
            val tx = Maps.floorInt(cx) + dx
            val ty = Maps.floorInt(cy) + dy
            val bmp = maps.tile(zoom, tx, ty) { host.refresh() } ?: continue
            cell.set(originX + dx * side, originY + dy * side, originX + (dx + 1) * side, originY + (dy + 1) * side)
            ui.image(c, bmp, cell, a)
        }
        // The dot: a white ring round a coloured core, which is how a position
        // marker stays visible over both a pale street and a dark park
        val dr = ui.dp(5f)
        ui.dot(c, at.centerX(), at.centerY(), dr + ui.dp(2f), Color.WHITE, a)
        ui.dot(c, at.centerX(), at.centerY(), dr, ui.lead, a)
        // Accuracy, as the circle it actually is. Only when it is big enough
        // to mean something: a three-metre circle at street zoom is smaller
        // than the dot inside it.
        if (loc.hasAccuracy()) {
            val px = loc.accuracy / Maps.metresPerPx(loc.latitude, zoom).toFloat() * scale
            if (px > dr * 2.5f && px < at.width()) {
                ui.stroke.strokeWidth = ui.dp(1f)
                ui.stroke.color = ui.alpha(ui.lead, a * 0.5f)
                c.drawCircle(at.centerX(), at.centerY(), px, ui.stroke)
            }
        }
        scaleBar(c, at, loc.latitude, a)
        // Required by both the data and the rendering, and drawn small in the
        // corner the way every map does it
        ui.textEnd(c, Maps.CREDIT, ui.small, at.right - ui.s8, at.bottom - ui.dp(6f), a * 0.8f)
        c.restore()

        // Zoom, as two taps rather than a pinch: the island reads a drag as
        // its own gesture, and taking two fingers off it for a map would mean
        // the panel could no longer be swiped away from
        val bs = ui.dp(26f)
        hitIn.set(at.right - ui.s8 - bs, at.top + ui.s8, at.right - ui.s8, at.top + ui.s8 + bs)
        hitOut.set(hitIn.left, hitIn.bottom + ui.s4, hitIn.right, hitIn.bottom + ui.s4 + bs)
        zoomButton(c, hitIn, true, zoom < ZOOM_MAX, a)
        zoomButton(c, hitOut, false, zoom > ZOOM_MIN, a)
        // The clamp is here rather than in the lambda so these stay () -> Unit:
        // an increment is an expression and a lambda whose last statement is
        // one has that as its return type, which does not fit the hit list.
        hits.add(RectF(hitIn) to { zoomBy(1) })
        hits.add(RectF(hitOut) to { zoomBy(-1) })
    }

    private fun zoomBy(step: Int) {
        zoom = (zoom + step).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    private fun zoomButton(c: Canvas, at: RectF, plus: Boolean, on: Boolean, a: Float) {
        ui.fill(c, at, at.width() / 2, ui.tray, a * if (on) 0.92f else 0.5f)
        val s = at.width() * 0.4f
        ui.stroke.strokeWidth = ui.dp(1.6f)
        ui.stroke.color = ui.alpha(if (on) ui.ink else ui.faint, a)
        c.drawLine(at.centerX() - s / 2, at.centerY(), at.centerX() + s / 2, at.centerY(), ui.stroke)
        if (plus) c.drawLine(at.centerX(), at.centerY() - s / 2, at.centerX(), at.centerY() + s / 2, ui.stroke)
    }

    // A bar of a round number of metres, so the map can be read for distance
    // rather than only for shape. The number is picked to make the bar roughly
    // a quarter of the width, which is the length that is long enough to
    // compare against and short enough not to cross the dot.
    private fun scaleBar(c: Canvas, at: RectF, lat: Double, a: Float) {
        val perPx = Maps.metresPerPx(lat, zoom) / ui.dp(1f).coerceAtLeast(1f)
        val want = at.width() * 0.26f * perPx
        val metres = NICE.lastOrNull { it <= want } ?: NICE.first()
        val px = (metres / perPx).toFloat()
        if (px < ui.dp(24f) || px > at.width() * 0.6f) return
        val y = at.bottom - ui.dp(20f)
        val x = at.left + ui.s8
        ui.stroke.strokeWidth = ui.dp(1.6f)
        ui.stroke.color = ui.alpha(Color.WHITE, a * 0.85f)
        c.drawLine(x, y, x + px, y, ui.stroke)
        c.drawLine(x, y - ui.dp(3f), x, y + ui.dp(3f), ui.stroke)
        c.drawLine(x + px, y - ui.dp(3f), x + px, y + ui.dp(3f), ui.stroke)
        // NICE holds whole metres, so the kilometre label is the only one that
        // needs a decimal and the metre one is already an Int
        val label = if (metres >= 1000) host.ctx.getString(R.string.n_km, fmt(metres / 1000.0))
        else host.ctx.getString(R.string.n_m, metres)
        ui.text(c, label, ui.small, x, y - ui.dp(6f), px * 2, a)
    }

    // Four figures in a row: the distance large, the rest beside it.
    //
    // The distance is the one the whole block exists for, so it gets the big
    // face and the others are the same size as each other. Every one of them
    // is honest about where it came from — the distance is the step count
    // times the user's own stride, corrected upwards by the GPS trace while
    // the map is open, and the energy is weight times distance times a
    // published constant.
    private fun drawStats(c: Canvas, at: RectF, a: Float) {
        ui.fill(c, at, ui.radiusRow, ui.fill, a)
        if (!steps.canCount()) {
            ui.text(c, host.ctx.getString(R.string.maps_steps_need), ui.body, at.left + ui.s12, at.centerY() - ui.dp(6f), at.width() - ui.s24, a)
            val b = RectF(at.left + ui.s12, at.centerY() + ui.s4, at.left + ui.s12 + ui.dp(110f), at.centerY() + ui.s4 + ui.btnH)
            if (b.bottom <= at.bottom) {
                ui.button(c, b, host.ctx.getString(R.string.grant), a, true, Icon.Stretch)
                hits.add(RectF(b) to { grant(); host.collapse() })
            }
            return
        }
        val km = steps.km()
        ui.text(c, host.ctx.getString(R.string.act_walk), ui.body, at.left + ui.s12, at.top + ui.dp(16f), at.width() - ui.s24, a)
        ui.text(c, fmt(km.toDouble()), ui.big, at.left + ui.s12, at.top + ui.dp(44f), at.width() / 2, a)
        ui.text(c, host.ctx.getString(R.string.unit_km), ui.body, at.left + ui.s12 + ui.big.measureText(fmt(km.toDouble())) + ui.s4, at.top + ui.dp(44f), ui.dp(30f), a)
        // The other three, right of the distance, in even columns
        val startX = at.left + at.width() * 0.46f
        val w = (at.right - ui.s12 - startX) / 3f
        val cols = listOf(
            host.ctx.getString(R.string.unit_steps) to steps.today.toString(),
            host.ctx.getString(R.string.unit_time) to time(steps.movingMin()),
            host.ctx.getString(R.string.unit_kcal) to steps.kcal().toString(),
        )
        cols.forEachIndexed { i, (label, value) ->
            val x = startX + i * w
            ui.text(c, label, ui.small, x, at.top + ui.dp(26f), w - ui.s4, a)
            ui.text(c, value, ui.label, x, at.top + ui.dp(44f), w - ui.s4, a)
        }
        // Where the figure came from, said once rather than implied: a step
        // estimate and a measured trace are different things and the user is
        // entitled to know which is on screen
        val note = if (steps.tracking) R.string.maps_from_gps else R.string.maps_from_steps
        ui.text(c, host.ctx.getString(note), ui.small, at.left + ui.s12, at.bottom - ui.dp(9f), at.width() - ui.s24, a)
    }

    private fun time(min: Int): String {
        if (min < 60) return host.ctx.getString(R.string.t_min_n, min)
        return host.ctx.getString(R.string.in_hm, min / 60, min % 60)
    }

    // One decimal, and no decimal at all past ten: "1.4 km" is a distance,
    // "12.3 km" is a measurement nobody asked for
    private fun fmt(v: Double): String {
        val x = abs(v)
        return when {
            x >= 100 -> x.roundToInt().toString()
            x >= 10 -> "%.1f".format(x)
            else -> "%.2f".format(x).trimEnd('0').trimEnd('.')
        }
    }

    private fun clip(c: Canvas, at: RectF, radius: Float) {
        round.reset()
        round.addRoundRect(at, radius, radius, android.graphics.Path.Direction.CW)
        c.clipPath(round)
    }

    private val round = android.graphics.Path()

    override fun describe(): String =
        host.ctx.getString(R.string.tab_maps) + ": " + fmt(steps.km().toDouble()) + " " + host.ctx.getString(R.string.unit_km)

    companion object {
        // Letterbox, as asked: wide enough to show the streets either side of
        // the dot, short enough to leave the figures on the same screen
        private const val MAP_W = 16f
        private const val MAP_H = 6f

        // The activity block's height: a big figure and three columns beside
        // it, with the source line under them
        private const val STAT_H = 72f

        // Street level. Fourteen is a few blocks across a phone panel, which
        // is the scale a walking distance happens at; the range stops short of
        // building level on one side and city level on the other, because
        // neither tells a walker anything the other does not.
        private const val ZOOM_DEFAULT = 16
        private const val ZOOM_MIN = 11
        private const val ZOOM_MAX = 18

        // Round numbers a scale bar may be, in metres
        private val NICE = listOf(10, 20, 50, 100, 200, 500, 1000, 2000, 5000)
    }
}
