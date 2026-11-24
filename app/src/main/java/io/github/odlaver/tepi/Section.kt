package io.github.odlaver.tepi

import android.app.AlertDialog
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.LinearLayout

// One settings category per page
class Section : Page() {
    private val agenda get() = Listener.instance?.agenda
    private var kuliaEmail = ""
    private var kuliaSent = false
    private var waEnableAfterPermission = false

    override fun build() {
        when (intent.getStringExtra(MODE)) {
            ISLAND -> island()
            ACTIVITIES -> activities()
            PRODUCTIVITY -> productivity()
            REMINDERS -> reminders()
            CONNECT -> connect()
            THEME -> theme()
            TABS -> tabs()
            else -> about()
        }
    }

    private fun a11y() = Access.instance != null

    // The island's colours.
    //
    // Monochrome palettes and combinations. The monochrome palettes are the
    // island as it was and are listed first; the combinations
    // name a ground and an accent, and the accent is what a primary button, a
    // lit tile, the current page's mark and the logo are painted with. Body
    // text stays white in all nine, which is why there is no contrast warning
    // to give here: the only coloured things are the ones chosen to be looked
    // at, and the text on top of them is picked for whichever of black or
    // white reads against the accent.
    private fun theme() {
        header(getString(R.string.th_title))
        note(getString(R.string.th_sub))
        val now = prefs.str(Theme.KEY, "amoled")
        // Two groups, each one laid out before it is filled: Page appends to a
        // single column, so the label for the second group has to be added
        // after the first group's rows are in it
        label(getString(R.string.th_mono))
        val plain = group()
        for (t in Theme.all) if (!t.coloured) themeRow(plain, t, now)
        label(getString(R.string.th_combo_group))
        val combo = group()
        for (t in Theme.all) if (t.coloured) themeRow(combo, t, now)
        note(getString(R.string.th_note))
    }

    private fun themeRow(into: LinearLayout, t: Theme, now: String) {
        row(into, getString(t.label), swatchText(t), swatch(t, t.key == now), Icon.Palette) {
            prefs.set(Theme.KEY, t.key)
            changed()
            rebuild()
        }
    }

    // What the palette is made of, said in words: a combination theme is a
    // ground plus accent tones, and which is which is the thing a swatch alone
    // cannot tell you
    private fun swatchText(t: Theme) =
        if (t.coloured) getString(R.string.th_combo) else getString(R.string.th_plain)

    // The palette itself, as the colours it actually paints with. Two dots for
    // a grey theme — the ground and the white on it — and three for a
    // combination, the third being the accent as it will really be drawn,
    // lightened if the theme's own tone would not have read.
    private fun swatch(t: Theme, current: Boolean): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val tones = ArrayList<Int>()
        tones.add(t.back)
        if (t.tier != 0) tones.add(t.tier)
        if (t.lift != 0) tones.add(t.lift)
        if (t.coloured) tones.add(t.mark)
        for (c in tones) {
            row.addView(android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    // A hairline, so a black swatch is still visible on a dark
                    // page and a white one on a light page
                    setStroke(maxOf(1, dpi(1f)), withAlpha(ink, 60))
                }
            }, LinearLayout.LayoutParams(dpi(16f), dpi(16f)).apply { marginEnd = dpi(4f) })
        }
        if (current) row.addView(android.view.View(this).apply {
            background = IconDrawable(Icon.Check, ink, dpi(18f))
        }, LinearLayout.LayoutParams(dpi(18f), dpi(18f)).apply { marginStart = dpi(6f) })
        return row
    }

    // Which pages the island offers, and which notices may put themselves up.
    //
    // Two lists rather than one, because they are two different wants. A tab
    // is a page reached by swiping, and turning one off is "I never go there";
    // a live notice is a card that arrives on its own, and turning one off is
    // "do not interrupt me with this". Music is in both, and deliberately:
    // somebody may well want the lyric bar without the music page, or the
    // other way round.
    //
    // Home is in neither list. Every other page is reached by swiping from it
    // and the gear in the tab bar lives on it, so an island whose navigation
    // had been emptied would have nothing behind the swipe at all — the switch
    // would read as "turn the island off", which is what the master switch at
    // the top of the app is for.
    private fun tabs() {
        header(getString(R.string.tabs_title))
        note(getString(R.string.tabs_sub))
        label(getString(R.string.tabs_pages))
        val g = group()
        row(g, getString(R.string.home), getString(R.string.tabs_home_always), icon = Icon.Home)
        for ((id, title, icon) in PAGES) {
            toggle(g, getString(title), null, prefs.tabOn(id), icon = icon) {
                if (!prefs.setTab(id, it)) {
                    toast(getString(R.string.tabs_limit))
                    rebuild()
                } else changed()
            }
        }
        label(getString(R.string.tabs_notices))
        val n = group()
        for ((id, title, icon) in NOTICES) {
            toggle(n, getString(title), null, prefs.liveOn(id), icon = icon) { prefs.setLive(id, it); changed() }
        }
        note(getString(R.string.tabs_note))
    }

    private fun island() {
        header(getString(R.string.section_island))
        val g = group()
        row(g, getString(R.string.th_title), getString(R.string.th_row_sub, getString(Theme.of(prefs).label)), icon = Icon.Palette) {
            startActivity(Intent(this, Section::class.java).putExtra(MODE, THEME))
        }
        row(g, getString(R.string.tabs_title), getString(R.string.tabs_row_sub), icon = Icon.Layers) {
            startActivity(Intent(this, Section::class.java).putExtra(MODE, TABS))
        }
        row(g, getString(R.string.apps_pin), getString(R.string.apps_pin_row_sub, Shortcuts(this, prefs).stored().size, Shortcuts.MAX), icon = Icon.Grid) {
            startActivity(Intent(this, Pins::class.java))
        }
        label(getString(R.string.idle_bar))
        val c = group()
        toggle(c, getString(R.string.idle_bar), getString(R.string.idle_bar_sub), prefs.idleBar, icon = Icon.Layers) {
            prefs.idleBar = it; changed(); rebuild()
        }
        if (prefs.idleBar) {
            slider(c, getString(R.string.idle_width), Prefs.IDLE_WIDTH_MIN, Prefs.IDLE_WIDTH_MAX, prefs.idleWidthDp, { "$it dp" }, Icon.Chevron) {
                prefs.idleWidthDp = it
                changed()
            }
            toggle(c, getString(R.string.island_shadow), getString(R.string.island_shadow_sub), prefs.islandShadow, icon = Icon.Layers) {
                prefs.islandShadow = it
                changed()
            }
            if (prefs.islandShadow) {
                choice(c, getString(R.string.shadow_intensity),
                    listOf(
                        "soft" to getString(R.string.shadow_soft),
                        "balanced" to getString(R.string.shadow_balanced),
                        "strong" to getString(R.string.shadow_strong),
                        "custom" to getString(R.string.shadow_custom),
                    ),
                    prefs.shadowPreset, Icon.Layers) { preset ->
                        prefs.shadowPreset = preset
                        when (preset) {
                            "soft" -> prefs.shadowOpacity = Prefs.SHADOW_OPACITY_SOFT
                            "balanced" -> prefs.shadowOpacity = Prefs.SHADOW_OPACITY_BALANCED
                            "strong" -> prefs.shadowOpacity = Prefs.SHADOW_OPACITY_STRONG
                        }
                        changed()
                        rebuild()
                    }
                if (prefs.shadowPreset == "custom") {
                    slider(c, getString(R.string.shadow_opacity), Prefs.SHADOW_OPACITY_MIN, Prefs.SHADOW_OPACITY_MAX,
                        prefs.shadowOpacity, { "$it%" }, Icon.Layers) {
                        prefs.shadowOpacity = it
                        prefs.shadowPreset = "custom"
                        changed()
                    }
                }
            }
            choice(c, getString(R.string.logo_title),
                listOf("author" to getString(R.string.logo_author), "none" to getString(R.string.logo_none)),
                prefs.logoMode, Icon.Palette) {
                prefs.logoMode = it
                changed()
                rebuild()
            }
            toggle(c, getString(R.string.greeting_title), getString(R.string.greeting_sub), prefs.greetingEnabled, icon = Icon.Chat) {
                prefs.greetingEnabled = it
                changed()
                rebuild()
            }
            if (prefs.greetingEnabled) {
                val name = field(getString(R.string.greeting_name_hint), value = prefs.greetingName)
                name.inputType = android.text.InputType.TYPE_CLASS_TEXT
                name.maxLines = 1
                form(c, name, button(getString(R.string.save)) {
                    prefs.greetingName = name.text.toString().trim()
                    changed()
                    toast(getString(R.string.saved))
                })
                note(getString(R.string.greeting_name_note, Prefs.GREETING_NAME_MAX))
            }
            // What the capsule carries where the battery used to be. The list
            // is in Badge, in the order it is offered, so the page and the
            // drawing cannot drift apart.
            choice(c, getString(R.string.badge_title), Badge.entries.map { it to getString(Badge.label(it)) },
                Badge.of(prefs), Icon.Battery) { Badge.set(prefs, it); changed(); rebuild() }
            val badge = Badge.of(prefs)
            if (badge == Badge.Text) {
                // The user's own line. Saved on the button rather than as it
                // is typed: a capsule that changed under every keystroke would
                // be redrawing the island while somebody was still deciding
                // what to put in it.
                val f = field(getString(R.string.badge_text_hint), value = prefs.str(Badge.TEXT_KEY, ""))
                f.inputType = android.text.InputType.TYPE_CLASS_TEXT
                form(c, f, button(getString(R.string.save)) {
                    prefs.set(Badge.TEXT_KEY, f.text.toString().trim().take(Badge.TEXT_MAX))
                    changed()
                    toast(getString(R.string.saved))
                })
                note(getString(R.string.badge_text_note, Badge.TEXT_MAX))
            }
            if (badge == Badge.Cpu) note(getString(R.string.badge_cpu_note))
            if (badge == Badge.Provider || badge == Badge.Wifi || badge == Badge.Bluetooth) note(getString(R.string.badge_name_note))
        }
        label(getString(R.string.gestures_group))
        val b = group()
        toggle(b, getString(R.string.nav_always), getString(if (prefs.navAlways) R.string.nav_always_on else R.string.nav_always_off), prefs.navAlways, icon = Icon.Tasks) {
            prefs.navAlways = it; changed(); rebuild()
        }
        toggle(b, getString(R.string.camera_tap), getString(if (a11y()) R.string.camera_tap_sub else R.string.camera_tap_off), prefs.cameraTap, a11y(), Icon.Camera) {
            prefs.cameraTap = it; changed()
        }
        toggle(b, getString(R.string.lockscreen), getString(R.string.lockscreen_sub), prefs.bool("lockscreen", true), icon = Icon.Shield) {
            prefs.set("lockscreen", it); changed()
        }
        slider(b, getString(R.string.offset), Prefs.OFFSET_MIN, Prefs.OFFSET_MAX, prefs.offsetDp, { (if (it > 0) "+$it" else "$it") + " dp" }, Icon.Pin) {
            prefs.offsetDp = it
            changed()
            Listener.instance?.test()
        }
        // Names in Home's Wi-Fi and Bluetooth tiles, and in the capsule's own
        // badges. Optional: the tiles work without them, they just say Wi-Fi
        // and Bluetooth.
        val need = Radios.missing(this)
        row(b, getString(R.string.radio_names), getString(if (need.isEmpty()) R.string.radio_names_on else R.string.radio_names_sub),
            if (need.isEmpty()) null else button(getString(R.string.grant)) { requestPermissions(need, REQ_RADIOS) }, Icon.Wifi)
        // Sideways the island has nowhere to be, so it becomes a notch with
        // a pair of eyes in it instead of disappearing
        label(getString(R.string.notch))
        val n = group()
        toggle(n, getString(R.string.notch_bar), getString(R.string.notch_bar_sub), prefs.notchBar, icon = Icon.Eye) {
            prefs.notchBar = it; changed(); rebuild()
        }
        if (prefs.notchBar) {
            toggle(n, getString(R.string.notch_clock), getString(R.string.notch_clock_sub), prefs.notchClock, icon = Icon.Timer) {
                prefs.notchClock = it; changed(); rebuild()
            }
            toggle(n, getString(R.string.notch_tap), getString(if (prefs.notchOpens) R.string.notch_tap_on else R.string.notch_tap_off), prefs.notchOpens, icon = Icon.Hand) {
                prefs.notchOpens = it; changed(); rebuild()
            }
            // Two sliders rather than one: how much screen the block takes and
            // how much of the block is face are different wants, and a single
            // control would have tied a bigger notch to a bigger eye
            slider(n, getString(R.string.notch_size), Prefs.NOTCH_SIZE_MIN, Prefs.NOTCH_SIZE_MAX, prefs.notchSize, { "$it%" }, Icon.Stretch) {
                prefs.notchSize = it
                changed()
            }
            slider(n, getString(R.string.notch_eye), Prefs.NOTCH_EYE_MIN, Prefs.NOTCH_EYE_MAX, prefs.notchEye, { "$it%" }, Icon.Eye) {
                prefs.notchEye = it
                changed()
            }
            toggle(n, getString(R.string.eye_sway), getString(R.string.eye_sway_sub), prefs.eyeSway, icon = Icon.Music) {
                prefs.eyeSway = it; changed()
            }
            note(getString(R.string.notch_note))
        }
        label(getString(R.string.gestures))
        val h = group()
        for ((t, d, i) in listOf(
            Triple(R.string.g_tap, R.string.g_tap_sub, Icon.Hand),
            Triple(R.string.g_down, R.string.g_down_sub, Icon.Download),
            Triple(R.string.g_up, R.string.g_up_sub, Icon.Close),
            Triple(R.string.g_hold, R.string.g_hold_sub, Icon.Spark),
            Triple(R.string.g_side, R.string.g_side_sub, Icon.Chevron),
            Triple(R.string.triggers, R.string.triggers_sub, Icon.Info),
        )) row(h, getString(t), getString(d), icon = i)
        addButton(button(getString(R.string.test), icon = Icon.Play) { Listener.instance?.test() }, 12f)
    }

    private fun activities() {
        header(getString(R.string.section_activities))
        val g = group()
        toggle(g, getString(R.string.music), getString(R.string.music_sub), prefs.music, icon = Icon.Music) { prefs.music = it; changed() }
        toggle(g, getString(R.string.lyrics), getString(R.string.lyrics_sub), prefs.lyrics, icon = Icon.Lyrics) { prefs.lyrics = it; changed() }
        toggle(g, getString(R.string.lyrics_online), getString(R.string.lyrics_online_sub), prefs.lyricsOnline, icon = Icon.Globe) { prefs.lyricsOnline = it; changed() }
        label(getString(R.string.messages))
        val m = group()
        row(m, getString(R.string.apps), getString(R.string.apps_row_sub), icon = Icon.Layers) { startActivity(Intent(this, Apps::class.java)) }
        toggle(m, getString(R.string.notifs), getString(R.string.notifs_sub), prefs.notifs, icon = Icon.Chat) { prefs.notifs = it }
        toggle(m, getString(R.string.notif_content), getString(R.string.notif_content_sub), prefs.notifContent, icon = Icon.Eye) { prefs.notifContent = it }
        label(getString(R.string.wa_timer_title))
        val wa = group()
        toggle(wa, getString(R.string.wa_timer_enabled), getString(R.string.wa_timer_sub), prefs.bool(WhatsAppTimer.ENABLED, false), icon = Icon.Timer) { setWhatsAppTimerEnabled(it) }
        val soundUri = prefs.str(WhatsAppTimer.SOUND_URI, "")
        row(wa, getString(R.string.wa_timer_sound), if (soundUri.isEmpty()) getString(R.string.wa_timer_sound_default) else soundName(Uri.parse(soundUri)),
            button(getString(R.string.choose), false, Icon.Music) { pickWhatsAppSound() }, Icon.Music)
        if (soundUri.isNotEmpty()) row(wa, getString(R.string.wa_timer_sound_custom), null,
            button(getString(R.string.reset), false, Icon.Close) {
                try { contentResolver.releasePersistableUriPermission(Uri.parse(soundUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                prefs.set(WhatsAppTimer.SOUND_URI, "")
                rebuild()
            }, Icon.Music)
        val targets = WhatsAppTimer.load(prefs)
        targets.forEachIndexed { index, target ->
            row(wa, target.name, target.number, button(getString(R.string.remove), false, Icon.Trash) {
                WhatsAppTimer.save(prefs, targets.filterIndexed { i, _ -> i != index })
                rebuild()
            }, Icon.User)
        }
        if (targets.size < 5) row(wa, getString(R.string.wa_timer_add), getString(R.string.wa_timer_limit, targets.size),
            button(getString(R.string.choose), false, Icon.User) { pickWhatsAppContact() }, Icon.Plus)
        else row(wa, getString(R.string.wa_timer_add), getString(R.string.wa_timer_limit, targets.size), icon = Icon.Plus)
        label(getString(R.string.system))
        val s = group()
        for ((key, title, detail, icon) in listOf(
            Quad("act.charge", R.string.act_charge, R.string.act_charge_sub, Icon.Bolt),
            Quad("act.call", R.string.act_call, R.string.act_call_sub, Icon.Phone),
            Quad("act.progress", R.string.act_progress, R.string.act_progress_sub, Icon.Download),
            Quad("act.nav", R.string.act_nav, R.string.act_nav_sub, Icon.Nav),
            Quad(Volume.KEY, R.string.act_volume, R.string.act_volume_sub, Icon.Volume),
            Quad("act.audio", R.string.act_audio, R.string.act_audio_sub, Icon.Headphones),
            Quad("act.privacy", R.string.act_privacy, R.string.act_privacy_sub, Icon.Shield),
        )) toggle(s, getString(title), getString(detail), prefs.bool(key, true), icon = icon) { prefs.set(key, it); changed() }

        // The screenshot card, and the gallery read it needs
        label(getString(R.string.act_shot))
        val sh = group()
        val shotOk = Capture.allowed(this)
        toggle(sh, getString(R.string.act_shot), getString(if (shotOk) R.string.act_shot_sub else R.string.act_shot_need),
            prefs.bool(Capture.KEY, true) && shotOk, shotOk, Icon.Image) { prefs.set(Capture.KEY, it); restartCapture() }
        // The grid is a separate want from the card: one arrives, the other is
        // gone looking for
        toggle(sh, getString(R.string.act_shots_tab), getString(if (shotOk) R.string.act_shots_tab_sub else R.string.act_shot_need),
            prefs.bool(Capture.TAB_KEY, true) && shotOk, shotOk, Icon.Layers) { prefs.set(Capture.TAB_KEY, it); changed() }
        if (!shotOk) row(sh, getString(R.string.act_shot_perm), getString(R.string.act_shot_perm_sub),
            button(getString(R.string.grant)) { requestPermissions(arrayOf(Capture.permission()), REQ_SHOT) }, Icon.Image)

        // Switches from the shade, each said as one line
        label(getString(R.string.act_modes))
        val m2 = group()
        for ((key, title, detail, icon) in listOf(
            Quad("act.dnd", R.string.act_dnd, R.string.act_dnd_sub, Icon.Moon),
            Quad("act.ringer", R.string.act_ringer, R.string.act_ringer_sub, Icon.Bell),
            Quad("act.torch", R.string.act_torch, R.string.act_torch_sub, Icon.Flash),
            Quad("act.hotspot", R.string.act_hotspot, R.string.act_hotspot_sub, Icon.Hotspot),
            Quad("act.plane", R.string.act_plane, R.string.act_plane_sub, Icon.Plane),
            Quad("act.saver", R.string.act_saver, R.string.act_saver_sub, Icon.Leaf),
        )) toggle(m2, getString(title), getString(detail), prefs.bool(key, true), icon = icon) { prefs.set(key, it); changed() }
        note(getString(R.string.act_hotspot_note))

        // Where the phone is, and how far it has walked today
        label(getString(R.string.tab_maps))
        val w = group()
        // The basemap's own key. Not a setting — it is compiled in from a
        // build property, so there is nothing to type here — but it is worth a
        // row, because "the map is blank" and "the key is missing" look
        // identical from the outside and only this row tells them apart.
        val mapKey = BuildConfig.CARTO_KEY.isNotBlank()
        row(w, getString(R.string.maps_key), getString(if (mapKey) R.string.maps_key_on else R.string.maps_key_off),
            if (mapKey) null else button(getString(R.string.maps_key_page), false) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Maps.KEY_PAGE)))
            }, Icon.Pin)
        val walkOk = Listener.instance?.steps?.canCount() ?: false
        val hasSensor = Listener.instance?.steps?.hasCounter() ?: false
        toggle(w, getString(R.string.act_walk), getString(R.string.act_walk_sub), prefs.steps, icon = Icon.Stretch) {
            prefs.steps = it; changed(); rebuild()
        }
        if (!hasSensor) note(getString(R.string.act_walk_nosensor))
        else if (!walkOk) row(w, getString(R.string.act_walk_perm), getString(R.string.act_walk_perm_sub),
            button(getString(R.string.grant)) { requestPermissions(Steps.permission(), REQ_WALK) }, Icon.Stretch)
        if (prefs.steps) {
            // The stride the step count is turned into metres with, and the
            // weight the calorie figure needs. Both are the user's own, and
            // both are asked for rather than guessed because a stride from an
            // assumed height is where a step-based distance goes wrong.
            slider(w, getString(R.string.body_height), Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX, prefs.heightCm, { "$it cm" }, Icon.User) {
                prefs.heightCm = it
                changed()
            }
            slider(w, getString(R.string.body_weight), Prefs.WEIGHT_MIN, Prefs.WEIGHT_MAX, prefs.weightKg, { "$it kg" }, Icon.User) {
                prefs.weightKg = it
                changed()
            }
            note(getString(R.string.act_walk_note))
        }

        // The screen turned most of the way up
        label(getString(R.string.glare_group))
        val gl = group()
        toggle(gl, getString(R.string.glare_warn), getString(R.string.glare_warn_sub), prefs.glareWarn, icon = Icon.Eye) {
            prefs.glareWarn = it; changed(); rebuild()
        }
        if (prefs.glareWarn) {
            slider(gl, getString(R.string.glare_at), Prefs.GLARE_MIN, Prefs.GLARE_MAX, prefs.glareAt, { "$it%" }, Icon.Flash) {
                prefs.glareAt = it
                changed()
            }
            note(getString(R.string.glare_note))
        }
    }

    // The watcher holds a permission state and a switch; both are settled at
    // start, so it is restarted rather than poked
    private fun restartCapture() {
        changed()
        Listener.instance?.let { it.capture.stop(); it.capture.start() }
    }

    private fun pickWhatsAppContact() {
        if (WhatsAppTimer.load(prefs).size >= 5) return
        startActivityForResult(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_WA_CONTACT)
    }

    private fun setWhatsAppTimerEnabled(enabled: Boolean) {
        if (!enabled) {
            prefs.set(WhatsAppTimer.ENABLED, false)
            rebuild()
            return
        }
        if (WhatsAppTimer.load(prefs).isEmpty()) {
            toast(getString(R.string.wa_timer_need_contact))
            rebuild()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            waEnableAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_WA_NOTIF)
            prefs.set(WhatsAppTimer.ENABLED, false)
            rebuild()
            return
        }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            prefs.set(WhatsAppTimer.ENABLED, false)
        } else prefs.set(WhatsAppTimer.ENABLED, true)
        rebuild()
    }

    private fun pickWhatsAppSound() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/quicktime", "application/octet-stream"))
        }
        startActivityForResult(intent, REQ_WA_SOUND)
    }

    private fun soundName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: getString(R.string.wa_timer_sound_custom)
    } catch (_: Exception) { getString(R.string.wa_timer_sound_custom) }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == REQ_WA_SOUND) {
            val name = soundName(uri)
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension !in setOf("mp3", "ogg", "bnk", "wav", "mov")) {
                toast(getString(R.string.wa_timer_sound_unsupported))
                return
            }
            val oldUri = prefs.str(WhatsAppTimer.SOUND_URI, "")
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            if (oldUri.isNotEmpty() && oldUri != uri.toString()) {
                try { contentResolver.releasePersistableUriPermission(Uri.parse(oldUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            }
            prefs.set(WhatsAppTimer.SOUND_URI, uri.toString())
            rebuild()
            return
        }
        if (requestCode != REQ_WA_CONTACT) return
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val name = cursor.getString(0)?.trim().orEmpty()
            val number = WhatsAppTimer.normalize(cursor.getString(1).orEmpty())
            if (name.isEmpty() || number.isEmpty()) return
            val targets = WhatsAppTimer.load(prefs)
            if (targets.none { it.number == number } && targets.size < 5) {
                WhatsAppTimer.save(prefs, targets + WhatsAppTimerTarget(name, number))
                rebuild()
            }
        }
    }

    private fun productivity() {
        header(getString(R.string.section_productivity))
        val g = group()
        choice(g, getString(R.string.focus_break), listOf(5, 10, 15, 20).map { it to getString(R.string.t_min_n, it) }, prefs.int("focus.break", 5), Icon.Timer) { prefs.set("focus.break", it) }
        row(g, getString(R.string.notes), getString(R.string.notes_sub), icon = Icon.Note) { startActivity(Intent(this, Items::class.java).putExtra(Items.MODE, Items.NOTES)) }
        row(g, getString(R.string.shelf), getString(R.string.shelf_sub), icon = Icon.Shelf) { startActivity(Intent(this, Items::class.java).putExtra(Items.MODE, Items.SHELF)) }
    }

    private fun reminders() {
        header(getString(R.string.section_reminders))
        val r = group()
        toggle(r, getString(R.string.pray), getString(R.string.pray_sub), prefs.bool("pray", false), icon = Icon.Moon) { prefs.set("pray", it); rebuild() }
        if (prefs.bool("pray", false)) {
            choice(r, getString(R.string.pray_city), Prayer.cities.map { it.name to it.name }, prefs.str("pray.city", ""), Icon.Pin) { prefs.set("pray.city", it) }
            choice(r, getString(R.string.pray_before), listOf(0, 5, 10, 15).map { it to if (it == 0) getString(R.string.off) else getString(R.string.t_min_n, it) },
                prefs.int("pray.before", 10), Icon.Bell) { prefs.set("pray.before", it) }
        }
        val w = group().also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dpi(12f) }
        toggle(w, getString(R.string.drink), getString(R.string.drink_sub), prefs.bool("drink", false), icon = Icon.Drop) { prefs.set("drink", it); rebuild() }
        if (prefs.bool("drink", false)) {
            choice(w, getString(R.string.every), listOf(30, 45, 60, 90).map { it to getString(R.string.t_min_n, it) }, prefs.int("drink.every", 60), Icon.Timer) { prefs.set("drink.every", it) }
            choice(w, getString(R.string.drink_goal), listOf(6, 8, 10, 12).map { it to getString(R.string.glasses, it) }, prefs.int("drink.goal", 8), Icon.Check) { prefs.set("drink.goal", it) }
        }
        toggle(w, getString(R.string.eyes), getString(R.string.eyes_sub), prefs.bool("eyes", false), icon = Icon.Eye) { prefs.set("eyes", it) }
        toggle(w, getString(R.string.stretch), getString(R.string.stretch_sub), prefs.bool("stretch", false), icon = Icon.Stretch) { prefs.set("stretch", it); rebuild() }
        if (prefs.bool("stretch", false)) {
            choice(w, getString(R.string.every), listOf(30, 60, 90).map { it to getString(R.string.t_min_n, it) }, prefs.int("stretch.every", 60), Icon.Timer) { prefs.set("stretch.every", it) }
        }
        val c = group().also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dpi(12f) }
        toggle(c, getString(R.string.class_remind), getString(R.string.class_remind_sub), prefs.bool("class.remind", true), icon = Icon.Book) { prefs.set("class.remind", it) }
    }

    private fun connect() {
        header(getString(R.string.section_connect))
        val ag = agenda
        if (ag == null) { note(getString(R.string.need_running)); return }
        note(getString(R.string.connect_intro))

        // Kulia: email code, like kulia.live/masuk
        label("Kulia")
        val k = group()
        if (ag.kulia.signedIn) {
            row(k, ag.kulia.email ?: "Kulia", getString(R.string.kulia_in), button(getString(R.string.sign_out), false) {
                work({ ag.kulia.signOut() }) { ag.refresh(); rebuild() }
            }, Icon.Book)
        } else if (!kuliaSent) {
            row(k, getString(R.string.kulia_title), getString(R.string.kulia_sub), icon = Icon.Book)
            val email = field(getString(R.string.email), value = kuliaEmail)
            email.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            form(k, email, button(getString(R.string.send_code)) {
                val addr = email.text.toString().trim()
                if (!addr.contains('@')) return@button
                kuliaEmail = addr
                work({ ag.kulia.sendCode(addr) }) { err -> if (err != null) toast(getString(err)) else { kuliaSent = true; rebuild() } }
            })
        } else {
            row(k, getString(R.string.code_sent, kuliaEmail), null, icon = Icon.Book)
            val code = field(getString(R.string.code))
            code.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            form(k, code, button(getString(R.string.sign_in)) {
                val c = code.text.toString().trim()
                if (c.length < 6) return@button
                work({ ag.kulia.verify(kuliaEmail, c) }) { err -> if (err != null) toast(getString(err)) else { kuliaSent = false; ag.refresh(); rebuild() } }
            })
        }

        // Todoist token
        label("Todoist")
        val t = group()
        if (ag.todoistToken != null) row(t, getString(R.string.connected), getString(R.string.todoist_sub), button(getString(R.string.disconnect), false) {
            ag.vault.put("todoist", null); ag.refresh(); rebuild()
        }, Icon.Tasks)
        else {
            row(t, getString(R.string.todoist_title), getString(R.string.todoist_how), icon = Icon.Tasks)
            val f = field(getString(R.string.token), secret = true)
            form(t, f, button(getString(R.string.save)) {
                val tok = f.text.toString().trim()
                if (tok.isEmpty()) return@button
                work({ Todoist(tok).tasks() != null }) { ok -> if (ok) { ag.vault.put("todoist", tok); ag.refresh(); rebuild() } else toast(getString(R.string.token_bad)) }
            })
        }

        // Notion token and data source
        label("Notion")
        val n = group()
        val ntok = ag.notionToken
        if (ntok != null) {
            val src = prefs.str("notion.source.name", "")
            row(n, if (src.isNotEmpty()) src else getString(R.string.notion_pick), getString(R.string.notion_sub), button(getString(R.string.choose), false) {
                work({ Notion(ntok).sources() }) { list ->
                    if (list.isNullOrEmpty()) { toast(getString(R.string.notion_none)); return@work }
                    AlertDialog.Builder(this).setTitle(R.string.notion_pick).setItems(list.map { it.second }.toTypedArray()) { _, i ->
                        prefs.set("notion.source", list[i].first); prefs.set("notion.source.name", list[i].second); ag.refresh(); rebuild()
                    }.show()
                }
            }, Icon.Note)
            row(n, getString(R.string.disconnect), null, icon = Icon.Close) {
                ag.vault.put("notion", null); prefs.set("notion.source", ""); prefs.set("notion.source.name", ""); ag.refresh(); rebuild()
            }
        } else {
            row(n, getString(R.string.notion_title), getString(R.string.notion_how), icon = Icon.Note)
            val f = field(getString(R.string.token), secret = true)
            form(n, f, button(getString(R.string.save)) {
                val tok = f.text.toString().trim()
                if (tok.isEmpty()) return@button
                work({ Notion(tok).sources() }) { list -> if (list != null) { ag.vault.put("notion", tok); rebuild() } else toast(getString(R.string.token_bad)) }
            })
        }

        // GitHub token: the contribution calendar tab
        github()

        // iCal feeds
        label(getString(R.string.calendar))
        val c = group()
        for (u in ag.icsUrls) row(c, u.substringAfter("://").take(40), null, button(getString(R.string.delete), false) {
            ag.vault.put("ics", ag.icsUrls.filter { it != u }.joinToString("\n")); ag.refresh(); rebuild()
        }, Icon.Globe)
        if (ag.icsUrls.size < 12) {
            val f = field(getString(R.string.ics_hint))
            form(c, f, button(getString(R.string.add)) {
                val u = f.text.toString().trim()
                if (!u.startsWith("https://") && !u.startsWith("webcal://")) { toast(getString(R.string.ics_bad)); return@button }
                work({ Ical.fetch(u) != null }) { ok -> if (ok) { ag.vault.put("ics", (ag.icsUrls + u).joinToString("\n")); ag.refresh(); rebuild() } else toast(getString(R.string.ics_bad)) }
            })
        }
        laptop()
        money()
        note(getString(R.string.connect_privacy))
    }

    // Supabase mirror plus the Gemini key. Both are read-only on the phone:
    // every mutation stays in the web app that owns the balance triggers.
    private fun money() {
        val w = Listener.instance?.wallet ?: return
        val ai = Listener.instance?.assistant ?: return

        label(getString(R.string.wallet_section))
        val g = group()
        if (w.configured) {
            row(g, getString(R.string.connected), getString(R.string.wallet_url), button(getString(R.string.wallet_test), false) {
                w.testConnection { _, msg -> toast(msg) }
            }, Icon.Purse)
            row(g, getString(R.string.disconnect), null, icon = Icon.Close) {
                w.configure(null, null); rebuild()
            }
        } else {
            val url = field(getString(R.string.wallet_url))
            val anon = field(getString(R.string.wallet_anon), secret = true)
            form(g, url, anon, button(getString(R.string.save)) {
                val u = url.text.toString().trim()
                val a = anon.text.toString().trim()
                if (!u.startsWith("https://") || a.isEmpty()) { toast(getString(R.string.token_bad)); return@button }
                w.configure(u, a)
                w.testConnection { ok, msg -> toast(msg); if (ok) w.refresh(); rebuild() }
            })
        }
        note(getString(R.string.wallet_note))
        val privacy = group()
        toggle(privacy, getString(R.string.finance_hide_balance),
            getString(R.string.finance_hide_balance_sub), prefs.financeBalanceHidden, icon = Icon.Eye) {
            prefs.financeBalanceHidden = it
            rebuild()
        }

        label(getString(R.string.assistant))
        val a = group()
        if (ai.configured) {
            row(a, Assistant.MODEL_LABELS[ai.model()] ?: ai.model(), getString(R.string.gemini_model),
                button(getString(R.string.choose), false) {
                    val ids = Assistant.MODEL_IDS
                    val names = ids.map { Assistant.MODEL_LABELS[it] ?: it }.toTypedArray()
                    AlertDialog.Builder(this).setTitle(R.string.gemini_model).setItems(names) { _, i ->
                        ai.setModel(ids[i]); rebuild()
                    }.show()
                }, Icon.Spark)
            row(a, getString(R.string.disconnect), null, icon = Icon.Close) {
                ai.configure(null); ai.clear(); rebuild()
            }
        } else {
            val key = field(getString(R.string.gemini_key), secret = true)
            form(a, key, button(getString(R.string.save)) {
                val k = key.text.toString().trim()
                if (k.isEmpty()) return@button
                ai.configure(k); rebuild()
            })
        }
        note(getString(R.string.gemini_note))
    }

    // Classic token with read:user, or a fine-grained one; the calendar is
    // the token owner's own, so every user wires up their own profile
    private fun github() {
        val gh = Listener.instance?.github ?: return
        label("GitHub")
        val g = group()
        if (gh.linked) {
            row(g, gh.login.ifEmpty { getString(R.string.connected) }, getString(R.string.github_sub), button(getString(R.string.disconnect), false) {
                gh.save(null); rebuild()
            }, Icon.Github)
            return
        }
        row(g, getString(R.string.github_title), getString(R.string.github_how), icon = Icon.Github)
        val f = field(getString(R.string.token), secret = true)
        form(g, f, button(getString(R.string.save)) {
            val tok = f.text.toString().trim()
            if (tok.isEmpty()) return@button
            work({ Github.fetch(tok) }) { cal -> if (cal != null) { gh.save(tok); rebuild() } else toast(getString(R.string.token_bad)) }
        })
    }

    // Tepi on Windows: approve agents from here
    private fun laptop() {
        val pc = Listener.instance?.pc ?: return
        label(getString(R.string.laptop))
        val g = group()
        if (pc.paired) {
            row(g, getString(R.string.pc_connected, pc.name.ifEmpty { pc.host }), getString(if (pc.online) R.string.pc_online else R.string.pc_offline, pc.host),
                button(getString(R.string.disconnect), false) { pc.forget(); rebuild() }, Icon.Laptop)
            row(g, getString(R.string.pc_send_files), getString(R.string.pc_send_files_sub),
                button(getString(R.string.pick_files), false) { startActivity(Intent(this, PickSendActivity::class.java)) }, Icon.Share)
            toggle(g, getString(R.string.pc_notifs), getString(R.string.pc_notifs_sub), pc.sendNotifs, icon = Icon.Chat) { pc.sendNotifs = it }
            toggle(g, getString(R.string.pc_otp), getString(R.string.pc_otp_sub), pc.sendOtp, icon = Icon.Shield) { pc.sendOtp = it }
            return
        }
        row(g, getString(R.string.pc_title), getString(R.string.pc_how), icon = Icon.Laptop)
        val host = field(getString(R.string.pc_address))
        val code = field(getString(R.string.pc_code))
        code.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        // Opened from a tepi://pair link: fill, never connect alone
        val fill = { raw: String? ->
            val link = pc.parseLink(raw)
            if (link == null) toast(getString(R.string.qr_bad))
            else { host.setText(link.first[0]); code.setText(link.second); qrHosts = link.first }
        }
        intent.getStringExtra(LINK)?.let { intent.removeExtra(LINK); fill(it) }
        val pairBtn = button(getString(R.string.pc_pair)) {
            val h = host.text.toString().trim()
            val c = code.text.toString().trim()
            if (!h.contains(':') || c.replace("-", "").length != 12) { toast(getString(R.string.pc_bad)); return@button }
            val hosts = if (qrHosts.firstOrNull() == h) qrHosts else listOf(h)
            work({ pc.pairAny(hosts, c) }) { ok -> if (ok) rebuild() else toast(getString(R.string.pc_failed)) }
        }
        if (Scan.available(this)) form(g, button(getString(R.string.scan_qr), false, Icon.Camera) { Scan.qr(this, fill) }, host, code, pairBtn)
        else form(g, host, code, pairBtn)
    }

    // Laptop addresses from the last QR
    private var qrHosts: List<String> = emptyList()

    private fun about() {
        header(getString(R.string.section_about))
        val g = group()
        row(g, getString(R.string.reads), getString(R.string.reads_sub), icon = Icon.Shield) { startActivity(Intent(this, Reads::class.java)) }
        row(g, getString(R.string.publisher), getString(R.string.publisher_name), icon = Icon.User)
        row(g, getString(R.string.version), BuildConfig.VERSION_NAME, button(getString(R.string.check_update), false) {
            work({ Update.latest() }) { rel ->
                when {
                    rel == null -> toast(getString(R.string.update_none))
                    Update.newer(rel.version, BuildConfig.VERSION_NAME) -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_found, rel.version))
                        .setMessage(R.string.update_sub)
                        .setPositiveButton(R.string.download) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk))) }
                        .setNegativeButton(R.string.cancel, null).show()
                    else -> toast(getString(R.string.update_latest))
                }
            }
        }, Icon.Info)
        row(g, getString(R.string.source), "github.com/${BuildConfig.REPO}", icon = Icon.Code) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.REPO}")))
        }
        row(g, getString(R.string.coffee), getString(R.string.coffee_sub), icon = Icon.Coffee) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.COFFEE)))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        when (code) {
            REQ_WA_NOTIF -> {
                val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
                if (waEnableAfterPermission && granted) prefs.set(WhatsAppTimer.ENABLED, true)
                waEnableAfterPermission = false
                if (!granted) toast(getString(R.string.wa_timer_permission))
                rebuild()
            }
            REQ_RADIOS -> {
                // Fresh names for the tiles, and the row's own state
                Listener.instance?.let { it.radios.stop(); it.radios.start() }
                rebuild()
            }
            REQ_SHOT -> {
                // The watcher only registers when the read is allowed, and
                // the grid tab only lists itself once it is
                Listener.instance?.let { it.capture.stop(); it.capture.start() }
                changed()
                rebuild()
            }
            REQ_WALK -> {
                // The counter can only be registered once the recognition
                // permission is in; a refusal leaves the figure at nothing and
                // the page says so
                Listener.instance?.let { it.steps.stop(); it.steps.start() }
                changed()
                rebuild()
            }
        }
    }

    companion object {
        private const val REQ_RADIOS = 7
        private const val REQ_SHOT = 8
        private const val REQ_WALK = 9
        private const val REQ_WA_NOTIF = 10
        private const val REQ_WA_CONTACT = 7101
        private const val REQ_WA_SOUND = 7102
        const val MODE = "mode"
        const val LINK = "link"
        const val ISLAND = "island"
        const val ACTIVITIES = "activities"
        const val PRODUCTIVITY = "productivity"
        const val REMINDERS = "reminders"
        const val CONNECT = "connect"
        const val ABOUT = "about"
        const val THEME = "theme"
        const val TABS = "tabs"

        // The pages the island offers behind a swipe, with their switches.
        //
        // Scene ids, so the switch and the page it hides cannot drift: the
        // island asks prefs.tabOn with exactly these strings. Home is not in
        // the list on purpose — see tabs().
        private val PAGES = listOf(
            Triple("music", R.string.tab_music, Icon.Music),
            Triple("timer", R.string.t_timer, Icon.Timer),
            Triple("agenda", R.string.tab_agenda, Icon.Book),
            Triple("prayer", R.string.tab_prayer, Icon.Moon),
            Triple("agents", R.string.laptop, Icon.Laptop),
            Triple("notes", R.string.notes, Icon.Note),
            Triple("shelf", R.string.shelf, Icon.Shelf),
            Triple("github", R.string.tab_github, Icon.Github),
            Triple("shots", R.string.tab_shots, Icon.Image),
            Triple("maps", R.string.tab_maps, Icon.Pin),
            Triple("apps", R.string.tab_apps, Icon.Grid),
            Triple("finance", R.string.finance, Icon.Purse),
            Triple("kanban", R.string.kanban, Icon.Kanban),
            Triple("assistant", R.string.assistant, Icon.Spark),
        )

        // And the notices that may put themselves up without being asked.
        // Separate list, separate switches: a page is somewhere to go and a
        // notice is something that arrives.
        private val NOTICES = listOf(
            Triple("music", R.string.tab_music, Icon.Music),
            Triple("message", R.string.messages, Icon.Chat),
            Triple("incoming", R.string.act_call, Icon.Phone),
            Triple("nav", R.string.act_nav, Icon.Nav),
            Triple("progress", R.string.act_progress, Icon.Download),
            Triple("shot", R.string.act_shot, Icon.Image),
            Triple("volume", R.string.act_volume, Icon.Volume),
            Triple("flag", R.string.act_modes, Icon.Spark),
            Triple("privacy", R.string.act_privacy, Icon.Shield),
            Triple("translate", R.string.translate, Icon.Globe),
            Triple("brief", R.string.act_charge, Icon.Bolt),
            Triple("alert", R.string.section_reminders, Icon.Bell),
        )
    }
}
