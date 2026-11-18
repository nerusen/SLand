package io.github.odlaver.tepi

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

// Why the map is not drawing, when it is not drawing.
//
// Three states rather than a boolean, because the three have different
// answers. A missing key is a build that was made without one and the user
// can do nothing about it; a refused key is a key that exists but is not
// allowed to serve this app, which is a dashboard setting; and a network
// failure is a network failure. Telling them apart is the difference between
// a tab that says what is wrong and a tab that is mysteriously grey.
enum class MapFault { None, NoKey, Refused, Offline }

// The monochrome basemap behind the Maps tab.
//
// Not a MapView. The island is a raw Canvas inside a WindowManager overlay
// with no view hierarchy in it at all: every pixel in this app is a drawPath
// or a drawBitmap, and a real map widget would mean a SurfaceView child, a GL
// context, and a second rendering model living inside a window that was built
// not to have one. What a slippy map actually is, underneath every one of
// those libraries, is a grid of 256-pixel PNGs named by zoom and position —
// and drawing a grid of bitmaps is something this canvas already does well.
//
// The tiles come from CARTO's dark_all basemap, which is OpenStreetMap data
// rendered grey: roads, footpaths, rivers, coastline, woodland, parks and
// administrative boundaries, all as flat tone. That is deliberately the same
// thing Google Maps shows in its normal view rather than its satellite one —
// an aerial photograph is megabytes per screen and tells a pedestrian less
// than a drawn road does.
//
// A key is required, and has been since 25 September 2026. Before that date
// these tiles were served to anyone; now a request without a key is answered
// with HTTP 200 and a tile that reads "API KEY REQUIRED" across it, which is
// the worst possible failure mode for a cache — the response is a valid PNG,
// so nothing errors, and the watermark gets written to disk as though it were
// a map. Both halves of that are handled below: the key goes on every
// request, and a watermark that arrives anyway is detected and never cached.
//
// The key is restricted in CARTO's dashboard to this app's package name and
// signing certificate, so the two headers that prove those are sent with
// every tile. Without them a restricted key is refused with 403, which is
// why they are not optional.
class Maps(private val context: Context, cacheDir: File) {
    // Versioned, so a format or policy change starts from an empty cache
    // rather than from whatever the last version left behind. This is how the
    // watermark tiles written before the key existed get thrown away: they are
    // in v1, and nothing reads v1 any more.
    private val dir = File(cacheDir, "tiles/v2").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())

    // Decoded tiles, by key. Small: a 16:6 panel at one zoom is about fifteen
    // of them, and the whole point of the disk cache is that dropping one
    // costs a decode rather than a download.
    private val live = LinkedHashMap<Key, Bitmap>()
    private val wanted = HashSet<Key>()
    private var fetching = 0
    private var sweptAt = 0L

    // Why nothing is drawing, for the tab to say out loud
    @Volatile var fault = MapFault.None
        private set

    // Whether anything has been handed over yet, so the tab can say it is
    // loading rather than drawing an empty grid
    var any = false
        private set

    val keyed get() = BuildConfig.CARTO_KEY.isNotBlank()

    init {
        // Old cache directories from previous versions of this file, including
        // the unversioned one that holds watermark tiles fetched before the key
        // requirement was handled. Dropped once, in the background: it is file
        // deletion and there is no reason for it to be on the main thread.
        val root = File(cacheDir, "tiles")
        Thread {
            try {
                root.listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name != DIR) f.deleteRecursively()
                    // Files directly in tiles/ are from the unversioned layout
                    else if (f.isFile) f.delete()
                }
            } catch (_: Throwable) { }
        }.start()
        if (!keyed) fault = MapFault.NoKey
    }

    fun clear() {
        for (b in live.values) if (!b.isRecycled) b.recycle()
        live.clear()
        wanted.clear()
        any = false
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
        // A tile older than the cache window is not reused. CARTO's terms set
        // thirty days as the limit for holding map content on a device, so
        // this is a licence condition rather than a tuning choice — and it is
        // also roughly how often a neighbourhood changes enough to matter.
        if (f.exists() && f.length() > 0L) {
            if (System.currentTimeMillis() - f.lastModified() <= KEEP_MS) {
                val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
                if (b != null) {
                    live[key] = b
                    any = true
                    return b
                }
            }
            // Expired, or a half-written file from a fetch that died
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
        if (!keyed) { fault = MapFault.NoKey; return }
        if (key in wanted || fetching >= PARALLEL) return
        wanted.add(key)
        fetching++
        Thread {
            val result = download(key)
            main.post {
                fetching--
                wanted.remove(key)
                if (result != MapFault.None) fault = result
                else {
                    fault = MapFault.None
                    changed()
                }
            }
        }.start()
    }

    private fun download(key: Key): MapFault {
        val url = "https://$SUB.basemaps.cartocdn.com/$STYLE/${key.z}/${key.x}/${key.y}@2x.png" +
            "?key=" + BuildConfig.CARTO_KEY
        val tmp = File(dir, key.name + ".part")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            // CARTO asks callers to identify themselves, and a tile server
            // that cannot tell who is asking is entitled to refuse
            c.setRequestProperty("User-Agent", AGENT)
            // The two headers a key restricted to an Android app is checked
            // against. Same names Google's own APIs use, and CARTO's dashboard
            // names them explicitly: a key locked to a package and a signing
            // certificate refuses every request that does not carry both.
            c.setRequestProperty("X-Android-Package", context.packageName)
            signature()?.let { c.setRequestProperty("X-Android-Cert", it) }
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            val code = c.responseCode
            // 401 and 403 are the key being wrong or not allowed here; both
            // are a dashboard problem and neither is worth retrying
            if (code == 401 || code == 403) { c.disconnect(); return MapFault.Refused }
            if (code !in 200..299) { c.disconnect(); return MapFault.Offline }
            c.inputStream.use { s -> tmp.outputStream().use { o -> s.copyTo(o) } }
            c.disconnect()
            if (tmp.length() <= 0L) { tmp.delete(); return MapFault.Offline }
            // A keyless or rejected request is answered with HTTP 200 and a
            // placeholder PNG, so the status code alone cannot be trusted. The
            // placeholder is the same image for every tile, which is the one
            // thing a real tile server never does: an ocean and a city cannot
            // be byte-identical. So the first placeholder seen is remembered
            // by its size and digest, and matching tiles are thrown away
            // rather than cached — a watermark written to disk would otherwise
            // be served from the cache for a month.
            if (placeholder(tmp)) {
                tmp.delete()
                return MapFault.Refused
            }
            val out = File(dir, key.name)
            if (out.exists()) out.delete()
            val moved = tmp.renameTo(out)
            if (!moved) tmp.delete()
            if (moved) sweep()
            if (moved) MapFault.None else MapFault.Offline
        } catch (_: Throwable) {
            try { tmp.delete() } catch (_: Exception) { }
            MapFault.Offline
        }
    }

    // Is this the "API KEY REQUIRED" tile?
    //
    // Two tests, cheap one first. Every placeholder is the same file, so the
    // first suspicious tile's length and SHA-256 are kept and everything after
    // it is compared against those. The seed is a tile that is both small and
    // low in colour variety, which a real grey basemap tile at 512 pixels
    // never is: a drawn map has labels, roads and coastline in it and does not
    // compress to a few kilobytes.
    @Volatile private var stampLen = -1L
    @Volatile private var stampSum: String? = null

    private fun placeholder(f: File): Boolean {
        val len = f.length()
        if (stampLen == len) {
            val sum = digest(f) ?: return false
            if (sum == stampSum) return true
        }
        if (len > PLACEHOLDER_MAX) return false
        // Small enough to be suspicious. Confirm by decoding and counting how
        // many distinct colours are in it: the watermark is flat grey plus
        // text, a real tile is a photograph's worth of tones.
        val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null } ?: return false
        val flat = try { colours(b) <= PLACEHOLDER_TONES } catch (_: Throwable) { false }
        if (!b.isRecycled) b.recycle()
        if (!flat) return false
        stampLen = len
        stampSum = digest(f)
        return true
    }

    private fun colours(b: Bitmap): Int {
        val seen = HashSet<Int>()
        val stepX = (b.width / SAMPLE).coerceAtLeast(1)
        val stepY = (b.height / SAMPLE).coerceAtLeast(1)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                seen.add(b.getPixel(x, y))
                if (seen.size > PLACEHOLDER_TONES) return seen.size
                x += stepX
            }
            y += stepY
        }
        return seen.size
    }

    private fun digest(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) { null }

    // Drop expired tiles, and cap the cache.
    //
    // The expiry is the licence condition: CARTO's terms allow map content to
    // sit on a device for thirty days and no longer, so a tile older than that
    // is deleted whether or not there is room for it. The count cap is the
    // ordinary housekeeping on top. Both run on a timer rather than per tile,
    // because listing a directory on every download would be the expensive
    // part of a cheap operation.
    private fun sweep() {
        val now = SystemClock.uptimeMillis()
        if (now - sweptAt < SWEEP_MS) return
        sweptAt = now
        try {
            val files = dir.listFiles() ?: return
            val wall = System.currentTimeMillis()
            val fresh = ArrayList<File>()
            for (f in files) {
                if (wall - f.lastModified() > KEEP_MS) f.delete() else fresh.add(f)
            }
            if (fresh.size <= DISK_MAX) return
            fresh.sortedBy { it.lastModified() }.take(fresh.size - DISK_MAX).forEach { it.delete() }
        } catch (_: Exception) { }
    }

    // Drop decoded tiles once the tab is closed; the files stay
    fun compact() {
        if (live.size <= LIVE_MAX) return
        val drop = live.keys.take(live.size - LIVE_MAX)
        for (k in drop) live.remove(k)?.let { if (!it.isRecycled) it.recycle() }
    }

    // The SHA-1 of the certificate this build was signed with, upper-case hex
    // with colons, which is the form CARTO's dashboard and Google's own APIs
    // both expect in X-Android-Cert.
    //
    // Read from the installed package rather than compiled in, so a debug
    // build sends the debug certificate and a release build sends the release
    // one. That matters: the two are different certificates, and a dashboard
    // entry for one does not cover the other.
    private var cert: String? = null
    private var certRead = false

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun signature(): String? {
        if (certRead) return cert
        certRead = true
        cert = try {
            val pm = context.packageManager
            val raw = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
                }?.firstOrNull()
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
            }
            raw?.let {
                MessageDigest.getInstance("SHA-1").digest(it.toByteArray())
                    .joinToString(":") { b -> "%02X".format(b) }
            }
        } catch (_: Throwable) { null }
        return cert
    }

    companion object {
        // The cache directory version. Bumped when the stored tiles are no
        // longer the right tiles — which includes the move from keyless
        // requests, whose cached results are watermarks.
        private const val DIR = "v2"

        // CARTO's grey basemap. dark_all rather than dark_nolabels: a map of a
        // neighbourhood with the street names taken off it is a pattern, not a
        // map, and the labels are the part a person actually reads.
        private const val STYLE = "dark_all"
        // The service answers on a, b, c and d alike; one is picked and kept so
        // the connection is reused across a grid
        private const val SUB = "a"

        private val AGENT = "SLand/${BuildConfig.VERSION_NAME} (github.com/${BuildConfig.REPO})"

        // Retina tiles are asked for, so one tile is 512 px of image for one
        // 256 px of map. On a phone at 3x density a 256 px tile is visibly
        // soft, and the file is only about twice the size.
        const val TILE = 256

        private const val PARALLEL = 4
        private const val TIMEOUT_MS = 8000

        // How long a tile may sit on the device. Thirty days is CARTO's own
        // limit in the Basemaps terms, so this is a licence condition and not
        // a number to tune upwards.
        private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000

        // A tile is about 15 KB, so this is a few megabytes at most
        private const val DISK_MAX = 400
        private const val LIVE_MAX = 24
        private const val SWEEP_MS = 30_000L

        // A real 512-pixel basemap tile with roads and labels on it does not
        // come out this small, and does not have this few distinct tones in
        // it. The watermark does: it is flat grey and one line of text.
        private const val PLACEHOLDER_MAX = 12_000L
        private const val PLACEHOLDER_TONES = 24
        // Pixels sampled across each axis when counting tones
        private const val SAMPLE = 32

        // Who the data and the rendering belong to. Drawn in the corner of
        // every map: it is a condition of the licence, free tier and paid
        // plan alike, and it is also simply true.
        const val CREDIT = "© OpenStreetMap · CARTO"

        // Where to go to fix a missing or refused key
        const val KEY_PAGE = "https://carto.com/basemaps/apikey/"

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
