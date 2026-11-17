package io.github.odlaver.tepi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size

// One screenshot, ready to show
class Shot(
    val uri: Uri,
    val name: String,
    val size: Long,
    val takenAt: Long,
    val thumb: Bitmap?,
)

// Watches the gallery for a screenshot the user just took.
//
// Android has no "a screenshot was taken" broadcast for other apps:
// ScreenCaptureCallback, new in Android 14, only reports captures of the
// caller's own windows. What every screenshot does do is land a new image in
// MediaStore under a Screenshots folder, so that is what is watched here. The
// cost is a media read permission, and without it the feature simply stays
// off rather than guessing.
class Capture(private val context: Context, private val prefs: Prefs, private val shot: (Shot) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    // Ignore whatever is already in the gallery: only images that appear
    // after this point are the user pressing the buttons
    private var since = 0L
    private var lastId = -1L
    private var readyAt = 0L

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!started || !prefs.bool(KEY, true)) return
            // A single capture writes the row, then updates it as the file is
            // flushed; one look shortly after the last change is enough
            main.removeCallbacks(look)
            main.postDelayed(look, 450)
        }
    }

    private val look = Runnable { scan() }

    fun start() {
        if (started) return
        if (!allowed(context)) return
        started = true
        since = System.currentTimeMillis()
        readyAt = SystemClock.uptimeMillis() + 1500
        try {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (_: Exception) { started = false }
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(look)
        try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
    }

    // Newest image in the store, taken as a screenshot, newer than the last
    // one reported. The query and the thumbnail decode are both disk work, so
    // they happen off the main thread: the island draws there, and a stall
    // would land in the middle of the animation that shows the result.
    private fun scan() {
        if (!started || SystemClock.uptimeMillis() < readyAt) return
        Thread {
            val found = read()
            if (found != null) main.post {
                // The grid is a capture behind now; let the tab rebuild it the
                // next time it is drawn
                loadedAt = 0L
                if (started) shot(found)
            }
        }.start()
    }

    private fun read(): Shot? {
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        // Newest first. The row count is capped while reading instead of with
        // a LIMIT clause: MediaStore validates the sort order strictly from
        // Android 11 and rejects anything but column names there.
        val found = try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            )?.use { c -> pick(c) }
        } catch (_: Exception) { null } ?: return null
        lastId = found.first
        // A screenshot is only news while it is fresh; an app writing an old
        // picture into the folder is not the user pressing the buttons
        if (System.currentTimeMillis() - found.second.takenAt > FRESH_MS) return null
        return found.second
    }

    private fun pick(c: android.database.Cursor): Pair<Long, Shot>? {
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        // Only the few newest rows are worth looking at; a screenshot is
        // always at the top of a date-descending list
        var seen = 0
        while (c.moveToNext() && seen < LOOK_ROWS) {
            seen++
            val id = c.getLong(idCol)
            if (id <= lastId) return null
            val name = c.getString(nameCol) ?: continue
            val path = c.getString(pathCol) ?: ""
            if (!isShot(path, name)) continue
            // DATE_ADDED is in seconds
            val added = c.getLong(dateCol) * 1000L
            if (added < since - 2000L) return null
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            return id to Shot(uri, name, c.getLong(sizeCol), added, thumb(uri))
        }
        return null
    }

    // Screenshots land in a Screenshots folder on every build that matters,
    // and the file is named for them too; either is taken as proof
    private fun isShot(path: String, name: String): Boolean {
        val p = path.lowercase()
        val n = name.lowercase()
        return p.contains("screenshot") || p.contains("screencapture") ||
            n.startsWith("screenshot") || n.startsWith("screencapture") || n.startsWith("scrn")
    }

    private fun thumb(uri: Uri): Bitmap? = try {
        val side = (THUMB_DP * context.resources.displayMetrics.density).toInt()
        context.contentResolver.loadThumbnail(uri, Size(side, side), null)
    } catch (_: Exception) { null }

    // The last few screenshots, for the grid tab.
    //
    // Separate from the watcher: that one reports a single fresh capture and
    // deliberately ignores everything already in the gallery, while the tab
    // wants the recent ones whenever it is opened. Both the query and the
    // thumbnail decodes are disk work, so the list is built on a background
    // thread and handed back on the main one; the tab keeps whatever it was
    // last given until the new list arrives, so opening it never blocks a
    // frame.
    private var recent: List<Shot> = emptyList()
    private var loading = false
    private var loadedAt = 0L

    val shots: List<Shot> get() = recent

    // Whether the grid tab is worth listing: the read has to be granted, and
    // the tab has its own switch apart from the card's.
    //
    // Asked several times per frame, by the page list as well as by the tab
    // bar, so the permission answer is kept for a few seconds rather than
    // checked each time. A grant arrives through the settings page, which
    // restarts the watcher anyway, so a stale yes or no is never more than
    // this far behind.
    private var grantSeen = false
    private var grantAt = 0L

    fun tabOn(): Boolean {
        if (!prefs.bool(TAB_KEY, true)) return false
        val now = SystemClock.uptimeMillis()
        if (now - grantAt > GRANT_MS) {
            grantSeen = allowed(context)
            grantAt = now
        }
        return grantSeen
    }

    // Called when the tab is drawn. A reload is only started when the list is
    // stale, so a panel redrawing at 60 fps does not queue sixty queries.
    fun refresh(changed: () -> Unit) {
        if (!tabOn()) { recent = emptyList(); return }
        if (loading || SystemClock.uptimeMillis() - loadedAt < LIST_MS) return
        loading = true
        Thread {
            val list = list()
            main.post {
                loading = false
                loadedAt = SystemClock.uptimeMillis()
                recent = list
                changed()
            }
        }.start()
    }

    // Newest screenshots first, thumbnails included
    private fun list(): List<Shot> {
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val out = ArrayList<Shot>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                // Rows are scanned, not limited in SQL: MediaStore validates
                // the sort order strictly from Android 11. The scan is capped
                // as well, so a gallery of thousands of pictures without a
                // single screenshot cannot turn this into a full table read.
                var seen = 0
                while (c.moveToNext() && out.size < LIST_MAX && seen < LIST_SCAN) {
                    seen++
                    val name = c.getString(nameCol) ?: continue
                    val path = c.getString(pathCol) ?: ""
                    if (!isShot(path, name)) continue
                    val id = c.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    out.add(Shot(uri, name, c.getLong(sizeCol), c.getLong(dateCol) * 1000L, thumb(uri)))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    companion object {
        const val KEY = "act.shot"
        // The grid tab of recent captures, on its own switch: the card is a
        // notice, the tab is a place to go looking
        const val TAB_KEY = "act.shots.tab"

        // How long after a capture the card is still worth showing
        private const val FRESH_MS = 20_000L
        private const val THUMB_DP = 96f
        // Rows examined per look; a fresh capture is always at the very top
        private const val LOOK_ROWS = 4
        // The grid: how many to show, how far to look for them, and how long
        // a built list stays good. The cap is the widest grid the tab can draw
        // at its own cell size, so it never asks for a thumbnail it cannot put
        // anywhere.
        private const val LIST_MAX = 12
        private const val LIST_SCAN = 400
        private const val LIST_MS = 20_000L
        // How long the permission answer is trusted between checks
        private const val GRANT_MS = 5000L

        // Reading the gallery needs the images permission from Android 13,
        // and the old storage one before that
        fun permission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE

        fun allowed(context: Context) =
            context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

        // Gallery, opened on that one picture
        fun open(shot: Shot): android.content.Intent =
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(shot.uri, "image/*")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // Same chooser the shelf uses for a file
        fun share(shot: Shot): android.content.Intent {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("image/*")
                .putExtra(android.content.Intent.EXTRA_STREAM, shot.uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return android.content.Intent.createChooser(send, null)
        }
    }
}
