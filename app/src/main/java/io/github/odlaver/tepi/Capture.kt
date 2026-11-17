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
            if (found != null) main.post { if (started) shot(found) }
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

    companion object {
        const val KEY = "act.shot"

        // How long after a capture the card is still worth showing
        private const val FRESH_MS = 20_000L
        private const val THUMB_DP = 96f
        // Rows examined per look; a fresh capture is always at the very top
        private const val LOOK_ROWS = 4

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
