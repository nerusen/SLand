package io.github.odlaver.tepi

import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout

// Choosing which apps go on the island.
//
// The same shape as the notification filter's page, because it is the same
// question asked twice: a long list of installed apps with a switch beside
// each. The differences are that this one is capped — ten apps, and the
// eleventh switch refuses rather than silently dropping the first — and that
// the ones already chosen are listed at the top, in the order they will be
// drawn in, so the grid on the island can be read off this page.
//
// System apps are not offered, as asked. The filter lives in Shortcuts, next
// to the grid that draws them, so the rule is in one place.
class Pins : Page() {
    private var query = ""
    private var list: LinearLayout? = null
    private lateinit var apps: Shortcuts

    override fun build() {
        apps = Shortcuts(this, prefs)
        header(getString(R.string.apps_pin))
        note(getString(R.string.apps_pin_sub, Shortcuts.MAX))
        val search = field(getString(R.string.search), value = query)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s.toString(); fill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        col.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        fill()
    }

    private fun fill() {
        val box = list ?: return
        box.removeAllViews()
        val pm = packageManager
        val all = Shortcuts.installed(this)
        val chosen = apps.stored()
        // The chosen ones first and in their own order, which is the order the
        // island draws them in: this page is the only place that order can be
        // seen, so hiding it inside an alphabetical list would make the grid
        // unexplainable
        val picked = chosen.mapNotNull { p -> all.firstOrNull { it.first == p } }
        val rest = all.filter { it.first !in chosen }
            .filter { query.isBlank() || it.second.contains(query, true) }
        val groups = listOf(
            getString(R.string.apps_pin_on, picked.size, Shortcuts.MAX) to picked,
            getString(R.string.apps_all) to rest,
        )
        for ((title, group) in groups) {
            if (group.isEmpty()) continue
            box.addView(text(title, 12f, sub).apply { setPadding(dpi(4f), dpi(20f), 0, dpi(6f)) })
            val g = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(14f) }
            }
            box.addView(g)
            for ((pkg, label) in group) {
                val on = apps.has(pkg)
                val full = !on && chosen.size >= Shortcuts.MAX
                val sw = toggle(g, label, if (full) getString(R.string.apps_pin_full, Shortcuts.MAX) else null, on, !full) {
                    if (!apps.toggle(pkg)) {
                        // Refused because the list is full. The switch has
                        // already moved, so the rebuild below puts it back and
                        // the reason is said rather than left as a control
                        // that appears to do nothing.
                        toast(getString(R.string.apps_pin_full, Shortcuts.MAX))
                    }
                    changed()
                    rebuild()
                }
                try {
                    val icon = pm.getApplicationIcon(pkg)
                    icon.setBounds(0, 0, dpi(30f), dpi(30f))
                    sw.setCompoundDrawablesRelative(icon, null, null, null)
                    sw.compoundDrawablePadding = dpi(12f)
                } catch (_: Exception) { }
            }
        }
    }
}
