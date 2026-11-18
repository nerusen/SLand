package io.github.odlaver.tepi

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

// One app the user put on the island
class Pinned(val pkg: String, val label: String, val icon: Bitmap?)

// Apps pinned to the island, for launching without going looking.
//
// A home screen is a grid of everything, sorted by nothing in particular, and
// the four or five apps anybody actually opens twenty times a day are
// somewhere in it. The island is already on screen and already one swipe from
// anywhere, so a short grid of exactly those apps is the shortest route there
// is to them — shorter than a home screen, because the island does not need
// the app currently in front to be dismissed first.
//
// Ten at most, in two rows of five. Not an arbitrary cap: five across is what
// fits a panel at icon sizes that are still recognisable, and two rows is the
// most that can go under the tab's heading without the panel becoming a page
// that has to be scrolled — and a shortcut grid that needs scrolling has
// become the drawer it was supposed to replace.
//
// Icons only, no labels. An app icon is a thing people recognise faster than
// its name, which is why launchers draw them at all, and ten labels at this
// width would each be three characters and an ellipsis.
//
// System apps are left out of the list, as asked. The filter is the
// framework's own FLAG_SYSTEM, with one deliberate exception: an app that
// shipped with the phone but has since been updated from the store is one the
// user chose to keep and probably uses — a pre-installed browser or camera —
// so FLAG_UPDATED_SYSTEM_APP is treated as ordinary. What is actually being
// filtered out is the hundred framework components with launcher entries that
// nobody opens on purpose.
class Shortcuts(private val context: Context, private val prefs: Prefs) {
    // Resolved apps, in the order the user put them. Rebuilt when the stored
    // list changes rather than per frame: loading ten icons out of the package
    // manager is not something to do inside onDraw.
    private var live: List<Pinned> = emptyList()
    private var liveKey = ""

    val list: List<Pinned> get() {
        val key = stored().joinToString(",")
        if (key != liveKey) {
            liveKey = key
            live = resolve(stored())
        }
        return live
    }

    val any get() = list.isNotEmpty()

    // The stored order, as package names. A list rather than a set, because
    // the order is the user's and a set would reorder the grid on every save.
    fun stored(): List<String> = prefs.str(KEY, "").split('\n').filter { it.isNotBlank() }

    fun has(pkg: String) = pkg in stored()

    // True when it went in or came out, false when the list was already full
    fun toggle(pkg: String): Boolean {
        val now = ArrayList(stored())
        if (now.remove(pkg)) { save(now); return true }
        if (now.size >= MAX) return false
        now.add(pkg)
        save(now)
        return true
    }

    private fun save(l: List<String>) {
        prefs.set(KEY, l.take(MAX).joinToString("\n"))
        liveKey = ""
    }

    // Package names that no longer resolve are dropped quietly: an app that
    // has been uninstalled should not leave a hole in the grid, and asking the
    // user to tidy up after their own uninstall is work the list can do itself
    private fun resolve(pkgs: List<String>): List<Pinned> {
        val pm = context.packageManager
        val out = ArrayList<Pinned>()
        val alive = ArrayList<String>()
        for (p in pkgs) {
            val info = try { pm.getApplicationInfo(p, 0) } catch (_: Exception) { null } ?: continue
            if (pm.getLaunchIntentForPackage(p) == null) continue
            val label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { p }
            out.add(Pinned(p, label, icon(pm, p)))
            alive.add(p)
        }
        if (alive.size != pkgs.size) prefs.set(KEY, alive.joinToString("\n"))
        return out
    }

    // The launcher icon as a bitmap, at the size the grid draws it. Adaptive
    // icons are Drawables with no bitmap of their own, so they are rendered
    // once into one here rather than on every frame.
    private fun icon(pm: PackageManager, pkg: String): Bitmap? = try {
        flatten(pm.getApplicationIcon(pkg))
    } catch (_: Exception) { null }

    private fun flatten(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val side = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, side, side)
        d.draw(Canvas(bmp))
        return bmp
    }

    fun launch(p: Pinned): Intent? =
        try { context.packageManager.getLaunchIntentForPackage(p.pkg) } catch (_: Exception) { null }

    companion object {
        const val KEY = "apps.pinned"

        // Two rows of five: what fits a panel at a size an icon is still an
        // icon at
        const val MAX = 10
        const val COLS = 5
        const val ROWS = 2

        private const val ICON_DP = 48f

        // Every launchable app that is not part of the system, by label.
        //
        // queryIntentActivities for MAIN/LAUNCHER is the same question a
        // launcher asks, so this is exactly the set of things that can be
        // opened. Tepi itself is left out — an island shortcut to the island
        // is nothing — and so is anything with FLAG_SYSTEM still set, which is
        // the framework's own answer to "did this ship with the phone and has
        // it been left alone since".
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            return try {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .mapNotNull { r ->
                        val pkg = r.activityInfo?.packageName ?: return@mapNotNull null
                        if (pkg == context.packageName) return@mapNotNull null
                        val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { null } ?: return@mapNotNull null
                        if (!general(info)) return@mapNotNull null
                        pkg to r.loadLabel(pm).toString()
                    }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            } catch (_: Exception) { emptyList() }
        }

        // Not a system component. An updated system app counts as general: it
        // was replaced from the store, which means somebody wanted the newer
        // one, and a pre-installed browser or camera that has been kept up to
        // date is an app people use rather than framework furniture.
        private fun general(info: ApplicationInfo): Boolean {
            if (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) return true
            return info.flags and ApplicationInfo.FLAG_SYSTEM == 0
        }
    }
}
