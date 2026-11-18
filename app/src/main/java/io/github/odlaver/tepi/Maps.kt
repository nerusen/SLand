package io.github.odlaver.tepi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

// One slippy-map tile, named by where it is
private class Key(val z: Int, val x: Int, val y: Int) {
    val name get() = "$z-$x-$y.png"
    override fun equals(other: Any?) = other is Key && other.z == z && other.x == x && other.y == y
    override fun hashCode() = (z * 31 + x) * 31 + y
}

// The monochrome basemap behind the Maps tab.
//
// Not a MapView. The island is a raw Canvas inside a WindowManager overlay
// with no view hierarchy in it at all: every pixel in this app is a drawPath
// or a drawBitmap, and a real map widget would mean a SurfaceView child, a GL
// context, an API key, and a second rendering model living inside a window
// that was built not to have one. What a slippy map actually is, underneath
// every one of those libraries, is a grid of 256-pixel PNGs named by zoom and
// position — and drawing a grid of bitmaps is something this canvas already
// does well.
//
// The tiles come from CARTO's dark_all basemap, which is OpenStreetMap data
// rendered grey: roads, footpaths, rivers, coastline, woodland, parks and
// administrative boundaries, all as flat tone. That is deliberately the same
// thing Google Maps shows in its normal view rather than its satellite one —
// an aerial photograph is megabytes per screen and tells a pedestrian less
// than a drawn road does. No key and no account, against an attribution line
// the tab draws in the corner.
//
// Everything is cached to disk and kept. A tile is a picture of a piece of the
// world that does not move, so there is no reason to fetch the same square
// twice: the first look at a neighbourhood costs about fifteen tiles, and
// every look after that costs nothing. The cache is capped by count rather
// than age for the same reason — old tiles are not wrong, they are just old.
class Maps(cacheDir: File) {
    private val dir = File(cacheDir, "tiles").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    // Decoded tiles, by key. Small: a 16:6 panel at one zoom is about fifteen
    // of them, and the whole point of the disk cache is that dropping one
    // costs a decode rather than a download.
    private val live = LinkedHashMap<Key, Bitmap>()
    private val wanted = HashSet<Key>()
    private var fetching = 0
    private var trimmedAt = 0L

    // Whether anything has been handed over yet, so the tab can say it is
    // loading rather than drawing an empty grid
    var any = false
        private set

    fun clear() {
        for (b in live.values) if (!b.isRecycled) b.recycle()
        live.clear()
        wanted.clear()
        any = false
    }

    fun wipe() {
        clear()
        try { dir.listFiles()?.forEach { it.delete() } } catch (_: Exception) { }
    }

    // A tile, if it is already to hand. Null starts a fetch and returns:
    // nothing here ever blocks, because this is called from inside onDraw.
    fun tile(z: Int, x: Int, y: Int, changed: () -> Unit): Bitmap? {
        val n = 1 shl z
        // Wrap round the date line, clamp at the poles: the y range simply
        // does not exist outside it
        if (y < 0 || y >= n) return null
        val key = Key(z, ((x % n) + n) % n, y)
        live[key]?.let { if (!it.isRecycled) return it }
        val f = File(dir, key.name)
        if (f.exists() && f.length() > 0L) {
            val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
            if (b != null) {
                live[key] = b
                any = true
                return b
            }
            // A half-written file from a fetch that died
            try { f.delete() } catch (_: Exception) { }
        }
        fetch(key, changed)
        return null
    }

    // At most a few downloads at once, and never the same tile twice. A panel
    // asks for its whole grid on the frame it opens, so without the in-flight
    // set that would be fifteen simultaneous connections, and again on the
    // next frame.
    private fun fetch(key: Key, changed: () -> Unit) {
        if (key in wanted || fetching >= PARALLEL) return
        wanted.add(key)
        fetching++
        Thread {
            val ok = download(key)
            main.post {
                fetching--
                wanted.remove(key)
                if (ok) changed()
            }
        }.start()
    }

    private fun download(key: Key): Boolean {
        val url = "https://$SUB.basemaps.cartocdn.com/$STYLE/${key.z}/${key.x}/${key.y}@2x.png"
        val tmp = File(dir, key.name + ".part")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            // CARTO asks callers to identify themselves, and a tile server
            // that cannot tell who is asking is entitled to refuse
            c.setRequestProperty("User-Agent", "SLand/${BuildConfig.VERSION_NAME} (github.com/${BuildConfig.REPO})")
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val code = c.responseCode
            if (code !in 200..299) { c.disconnect(); return false }
            c.inputStream.use { s -> tmp.outputStream().use { o -> s.copyTo(o) } }
            c.disconnect()
            if (tmp.length() <= 0L) { tmp.delete(); return false }
            val out = File(dir, key.name)
            if (out.exists()) out.delete()
            val moved = tmp.renameTo(out)
            if (!moved) tmp.delete()
            if (moved) trim()
            moved
        } catch (_: Throwable) {
            try { tmp.delete() } catch (_: Exception) { }
            false
        }
    }

    // Cap the cache, oldest first. Checked on a timer rather than per tile:
    // listing a directory on every download would be the expensive part of a
    // cheap operation.
    private fun trim() {
        val now = SystemClock.uptimeMillis()
        if (now - trimmedAt < TRIM_MS) return
        trimmedAt = now
        try {
            val files = dir.listFiles() ?: return
            if (files.size <= DISK_MAX) return
            files.sortedBy { it.lastModified() }.take(files.size - DISK_MAX).forEach { it.delete() }
        } catch (_: Exception) { }
    }

    // Drop decoded tiles once the tab is closed; the files stay
    fun compact() {
        if (live.size <= LIVE_MAX) return
        val drop = live.keys.take(live.size - LIVE_MAX)
        for (k in drop) live.remove(k)?.let { if (!it.isRecycled) it.recycle() }
    }

    companion object {
        // CARTO's grey basemap. dark_all rather than dark_nolabels: a map of a
        // neighbourhood with the street names taken off it is a pattern, not a
        // map, and the labels are the part a person actually reads.
        private const val STYLE = "dark_all"
        // The service answers on a, b and c alike; one is picked and kept so
        // the connection is reused across a grid
        private const val SUB = "a"

        // Retina tiles are asked for, so one tile is 512 px of image for one
        // 256 px of map. On a phone at 3x density a 256 px tile is visibly
        // soft, and the file is only about twice the size.
        const val TILE = 256

        private const val PARALLEL = 4
        // A tile is about 15 KB, so this is a few megabytes at most
        private const val DISK_MAX = 400
        private const val LIVE_MAX = 24
        private const val TRIM_MS = 30_000L

        // Who the data and the rendering belong to. Drawn in the corner of
        // every map: it is a condition of using either, and it is also simply
        // true.
        const val CREDIT = "© OpenStreetMap · CARTO"

        // Web Mercator, which is what every slippy map is in.
        //
        // The world is one tile at zoom 0 and 2^z tiles across at zoom z.
        // Longitude is linear, latitude is not: the projection stretches
        // towards the poles, which is the whole reason Greenland looks like
        // that on this kind of map.
        fun xOf(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)

        fun yOf(lat: Double, z: Int): Double {
            val r = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
            return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
        }

        fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0

        fun latOf(y: Double, z: Int): Double {
            val n = PI - 2.0 * PI * y / (1 shl z)
            return 180.0 / PI * atan(sinh(n))
        }

        // Metres per pixel at a latitude and zoom, for the scale bar
        fun metresPerPx(lat: Double, z: Int) =
            156543.03392 * cos(lat * PI / 180.0) / 2.0.pow(z.toDouble())

        fun floorInt(v: Double) = floor(v).toInt()
    }
}
