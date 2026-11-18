package io.github.odlaver.tepi

import android.content.Intent
import android.net.Uri

// What each permission lets Tepi read, and where data goes
class Reads : Page() {
    override fun build() {
        header(getString(R.string.reads))
        note(getString(R.string.reads_intro))
        for ((title, body, file) in listOf(
            Triple(R.string.perm_listener, R.string.reads_listener, "Listener.kt"),
            Triple(R.string.perm_access, R.string.reads_access, "Access.kt"),
            Triple(R.string.perm_overlay, R.string.reads_overlay, "Island.kt"),
            Triple(R.string.lyrics_online, R.string.reads_lyrics, "Lyrics.kt"),
            Triple(R.string.shelf, R.string.reads_shelf, "Shelf.kt"),
            Triple(R.string.radio_names, R.string.reads_radios, "Radios.kt"),
            Triple(R.string.act_shot_perm, R.string.reads_shot, "Capture.kt"),
            Triple(R.string.notch_bar, R.string.reads_notch, "Notch.kt"),
            Triple(R.string.tab_maps, R.string.reads_maps, "Maps.kt"),
            Triple(R.string.act_walk, R.string.reads_steps, "Steps.kt"),
            Triple(R.string.apps_pin, R.string.reads_pins, "Shortcuts.kt"),
            Triple(R.string.glare_group, R.string.reads_glare, "Glare.kt"),
            Triple(R.string.badge_title, R.string.reads_badge, "Badge.kt"),
        )) {
            label(getString(title))
            val g = group()
            row(g, getString(body), getString(R.string.reads_code, file)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SRC/$file")))
            }
        }
        label(getString(R.string.reads_never_title))
        val g = group()
        row(g, getString(R.string.reads_never))
    }

    companion object {
        private const val SRC = "https://github.com/odlaver/tepi-android/blob/main/app/src/main/java/io/github/odlaver/tepi"
    }
}
