package io.github.odlaver.tepi

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.WindowManager
import io.github.odlaver.tepi.scene.Action
import io.github.odlaver.tepi.scene.Alert
import io.github.odlaver.tepi.scene.AgentRow
import io.github.odlaver.tepi.scene.AgentScene
import io.github.odlaver.tepi.scene.AlertScene
import io.github.odlaver.tepi.scene.BriefScene
import io.github.odlaver.tepi.scene.CallScene
import io.github.odlaver.tepi.scene.ClassRow
import io.github.odlaver.tepi.scene.TaskRow
import io.github.odlaver.tepi.scene.ClockScene
import io.github.odlaver.tepi.scene.IncomingScene
import io.github.odlaver.tepi.scene.NavScene
import io.github.odlaver.tepi.scene.PrivacyScene
import io.github.odlaver.tepi.scene.ProgressScene
import io.github.odlaver.tepi.scene.Glyph
import io.github.odlaver.tepi.scene.Message
import io.github.odlaver.tepi.scene.NoteRow
import io.github.odlaver.tepi.scene.PrayerRow
import io.github.odlaver.tepi.scene.ShelfRow
import io.github.odlaver.tepi.scene.TimerRow
import io.github.odlaver.tepi.scene.TimerScene

class Listener : NotificationListenerService() {
    private var island: Island? = null
    private var media: Media? = null
    private lateinit var prefs: Prefs
    private lateinit var lyrics: Lyrics
    private val recent = HashMap<String, Long>()
    private val sentPc = HashMap<String, Long>()
    private val codes = HashMap<String, Long>()
    private val forwarded = HashSet<String>()
    private val waSeen = HashMap<String, String>()
    lateinit var timers: Timers
    lateinit var reminders: Reminders
    lateinit var notes: Notes
    lateinit var shelf: Shelf
    private var alerts: AlertScene? = null
    lateinit var live: Live
    lateinit var agenda: Agenda
    lateinit var wallet: Wallet
    lateinit var assistant: Assistant
    lateinit var pc: Pc
    private lateinit var watch: Watch
    lateinit var radios: Radios
    lateinit var github: Github
    lateinit var capture: Capture
    lateinit var volume: Volume
    lateinit var flags: Flags
    lateinit var steps: Steps
    lateinit var shortcuts: Shortcuts
    private lateinit var glare: Glare
    lateinit var maps: Maps
    private var briefs: BriefScene? = null
    private var privacyScene: PrivacyScene? = null
    private var timerScene: TimerScene? = null
    private var shots: io.github.odlaver.tepi.scene.ShotScene? = null
    private var volumeScene: io.github.odlaver.tepi.scene.VolumeScene? = null
    private var flagScene: io.github.odlaver.tepi.scene.FlagScene? = null
    private var translateScene: io.github.odlaver.tepi.scene.TranslateScene? = null

    override fun onCreate() {
        super.onCreate(); prefs = Prefs(this)
        try { getSystemService(NotificationManager::class.java).cancelAll() } catch (_: Exception) { }
        lyrics = Lyrics(java.io.File(cacheDir, "lyrics")); notes = Notes(this); shelf = Shelf(this)
        timers = Timers(this, prefs, ended = { timerEnded(it) }, changed = { island?.refresh() })
        reminders = Reminders(this, prefs, prayAlert = { i, at, now -> prayAlert(i, at, now) }, nudge = { nudge(it) })
        agenda = Agenda(this, prefs, changed = { island?.refresh() }, classSoon = { classSoon(it) })
        wallet = Wallet(agenda.vault) { island?.refresh() }; assistant = Assistant(this)
        pc = Pc(this, agenda.vault, prefs) { island?.refresh() }; pc.onFile = { name, uri -> fromLaptop(name, uri) }
        radios = Radios(this) { island?.refresh() }; github = Github(this, agenda.vault) { island?.refresh() }
        capture = Capture(this, prefs) { shot -> ensureIsland(); shots?.show(shot) }
        volume = Volume(this, prefs) { now, max, muted -> ensureIsland(); volumeScene?.show(now, max, muted, volume.kind) }
        flags = Flags(this, prefs) { flag, on -> ensureIsland(); flagScene?.show(flag, on) }
        steps = Steps(this, prefs) { island?.refresh() }; shortcuts = Shortcuts(this, prefs); maps = Maps(this, cacheDir)
        glare = Glare(this, prefs) { pct -> tooBright(pct) }
        live = Live(this, prefs, { n, pkg -> n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: appIcon(pkg) }) { island?.refresh() }
        watch = Watch(this, prefs, brief = { k, t -> briefs?.show(k, t) }, lowBattery = { lowBattery(it) }, battery = { l, c -> pc.battery(l, c); island?.battery(l, c) }, privacy = { cam, mic -> privacyScene?.let { it.cam = cam; it.mic = mic }; island?.refresh() })
    }

    override fun onListenerConnected() { instance = this; connected = true; media = Media(this, ComponentName(this, Listener::class.java)) { island?.mediaChanged() }.also { it.start() }; applyEnabled() }

    fun applyEnabled() { if (prefs.enabled) { reminders.start(); watch.start(); agenda.start(); wallet.start(); pc.start(); radios.start(); github.start(); capture.start(); volume.start(); flags.start(); steps.start(); glare.start(); try { activeNotifications?.forEach { live.posted(it) } } catch (_: Exception) { }; ensureIsland(); if (Access.paused(this)) startBankMonitor() } else { island?.detach(); island = null; reminders.stop(); watch.stop(); agenda.stop(); wallet.stop(); pc.stop(); radios.stop(); github.stop(); capture.stop(); volume.stop(); flags.stop(); steps.stop(); glare.stop(); live.clear() }; Tile.refresh(this) }

    override fun onListenerDisconnected() { connected = false; teardown(); if (prefs.enabled) android.os.Handler(mainLooper).postDelayed({ if (!connected) try { NotificationListenerService.requestRebind(ComponentName(this, Listener::class.java)) } catch (_: Exception) { } }, REBIND_DELAY_MS) }
    override fun onDestroy() { teardown(); super.onDestroy() }

    private fun teardown() { island?.detach(); island = null; reminders.stop(); watch.stop(); agenda.stop(); wallet.stop(); pc.stop(); radios.stop(); github.stop(); capture.stop(); volume.stop(); flags.stop(); steps.stop(); glare.stop(); maps.clear(); live.clear(); media?.stop(); media = null; if (instance === this) instance = null }

    fun ensureIsland() { if (!prefs.enabled) return; val m = media ?: return; val access = Access.instance; if (island != null || (access == null && !Settings.canDrawOverlays(this))) return; val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return; val type = if (access != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; val ctx = access ?: if (Build.VERSION.SDK_INT >= 31) createWindowContext(display, type, null) else this; val probe = if (access != null && Settings.canDrawOverlays(this)) { if (Build.VERSION.SDK_INT >= 31) createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else this } else null; island = Island(ctx, probe, display, type, m, prefs, lyrics).also { try { wire(it); it.attach(); it.mediaChanged(); if (watch.level >= 0) it.battery(watch.level, watch.charging) } catch (_: Exception) { island = null } } }

    private fun wire(isl: Island) {
        val al = AlertScene(isl); val ts = TimerScene(isl, timers); alerts = al; timerScene = ts
        isl.register(al); isl.register(ts)
        val br = BriefScene(isl); val pv = PrivacyScene(isl); briefs = br; privacyScene = pv
        isl.register(br); isl.register(pv)
        shots = io.github.odlaver.tepi.scene.ShotScene(isl, { shot -> isl.open(Capture.share(shot)) }) { shot -> shelve(shot) }.also { isl.register(it) }
        volumeScene = io.github.odlaver.tepi.scene.VolumeScene(isl, { volume.steps }) { volume.set(it) }.also { isl.register(it) }
        flagScene = io.github.odlaver.tepi.scene.FlagScene(isl).also { isl.register(it) }
        isl.register(AgentScene(isl, pc)); translateScene = io.github.odlaver.tepi.scene.TranslateScene(isl).also { isl.register(it) }
        isl.home.rows.add(AgentRow(isl, pc)); isl.register(IncomingScene(isl, live)); isl.register(CallScene(isl, live)); isl.register(NavScene(isl, live)); isl.register(ClockScene(isl, live)); isl.register(ProgressScene(isl, live))
        isl.home.rows.add(0, TimerRow(isl, timers, ts)); isl.home.rows.add(1, ClassRow(isl, agenda)); isl.home.rows.add(2, PrayerRow(isl, reminders))
        val agendaTab = io.github.odlaver.tepi.scene.AgendaTab(isl, agenda) { section(Section.CONNECT) }
        isl.register(agendaTab); isl.register(io.github.odlaver.tepi.scene.PrayerTab(isl, reminders) { section(Section.REMINDERS) }); isl.register(io.github.odlaver.tepi.scene.NotesTab(isl, notes, { newNote() }) { open(Items::class.java, Items.NOTES) }); isl.register(io.github.odlaver.tepi.scene.ShelfTab(isl, shelf, { picked(it) }) { open(Items::class.java, Items.SHELF) }); isl.register(io.github.odlaver.tepi.scene.AgentsTab(isl, pc)); isl.register(io.github.odlaver.tepi.scene.FinanceScene(isl, wallet)); isl.register(io.github.odlaver.tepi.scene.KanbanScene(isl, wallet)); isl.register(io.github.odlaver.tepi.scene.AssistantScene(isl, wallet, assistant))
        isl.register(io.github.odlaver.tepi.scene.ShotsTab(isl, capture) { isl.open(android.content.Intent(android.content.Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) })
        val mapsTab = io.github.odlaver.tepi.scene.MapsTab(isl, maps, steps, prefs, settings = { section(Section.ACTIVITIES) }) { askWalk() }
        isl.register(mapsTab); isl.closers.add { id -> if (id != mapsTab.id) mapsTab.away() }
        isl.register(io.github.odlaver.tepi.scene.AppsTab(isl, shortcuts) { startActivity(android.content.Intent(this, Pins::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) })
        isl.wireBadges(radios); isl.home.rows.add(TaskRow(isl, agenda, agendaTab)); isl.home.rows.add(NoteRow(isl, notes) { open(Items::class.java, Items.NOTES) }); isl.home.rows.add(ShelfRow(isl, shelf) { picked(it) }); isl.home.rows.add(io.github.odlaver.tepi.scene.RadioTiles(isl, radios)); isl.register(io.github.odlaver.tepi.scene.GithubTab(isl, github) { section(Section.CONNECT) })
        isl.homeExtras.add { if (Access.paused(this)) Action(getString(R.string.perm_access), primary = true, icon = Icon.Hand) { Access.openSettings(this) } else null }
        isl.menuExtras.add { Action(getString(R.string.t_timer), icon = Icon.Timer) { isl.expand(ts) } }; isl.menuExtras.add { Access.instance?.let { a -> Action(getString(R.string.bank_mode), icon = Icon.Shield) { a.stepAside(null) } } }; isl.menuExtras.add { Action(getString(R.string.note_new), icon = Icon.Note, personal = true) { newNote() } }
    }

    private fun section(mode: String) { startActivity(android.content.Intent(this, Section::class.java).putExtra(Section.MODE, mode).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun open(cls: Class<*>, mode: String) { startActivity(android.content.Intent(this, cls).putExtra(Items.MODE, mode).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun newNote() { island?.input(getString(R.string.note_hint)) { text -> if (text.isNotBlank()) { notes.add(text); alert(Alert("note", getString(R.string.note_saved), text, Glyph.Check, ms = 2500)) } } }
    private fun picked(item: ShelfItem) { if (item.isText) { shelf.copy(item); alert(Alert("shelf", getString(R.string.copied), item.text!!.take(80), Glyph.Check, ms = 2500)) } else island?.open(shelf.openIntent(item)) }
    private fun shelve(shot: Shot) { Thread { val ok = try { shelf.addFile(shot.uri) } catch (_: Exception) { false }; android.os.Handler(mainLooper).post { alert(Alert("shelf", getString(if (ok) R.string.shot_shelved else R.string.shot_shelve_failed), shot.name, if (ok) Glyph.Check else Glyph.Bell, ms = 3000)) } }.start() }
    private fun alert(a: Alert) { ensureIsland(); alerts?.show(a) }

    private fun classSoon(s: ClassSlot) { val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.start)); val where = listOf(time, s.room.ifEmpty { if (s.online) "Online" else "" }).filter { it.isNotEmpty() }.joinToString(" · "); val acts = s.url?.let { u -> listOf(Action(getString(R.string.join), primary = true, icon = Icon.Open) { island?.open(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }) } ?: emptyList(); alert(Alert("class", getString(if (s.exam) R.string.exam_soon else R.string.class_soon, s.title), where, Glyph.Book, acts, 10_000)) }
    private fun lowBattery(level: Int) { alert(Alert("battery", getString(R.string.low_battery, level), getString(R.string.low_battery_body), Glyph.Bolt, ms = 6000)) }
    private fun tooBright(pct: Int) { val fix = Action(getString(R.string.glare_fix), primary = true, icon = Icon.Eye) { island?.open(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) }; alert(Alert("glare", getString(R.string.glare_title), getString(R.string.glare_body, pct), Glyph.Eye, listOf(fix), 8000)) }
    private fun askWalk() { section(Section.ACTIVITIES) }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { live.removed(sbn.key); waSeen.remove(sbn.key); if (forwarded.remove(sbn.key)) pc.removed(sbn.key) }
    private fun fromLaptop(name: String, uri: android.net.Uri?) { if (uri == null) { alert(Alert("pc", getString(R.string.pc_file_failed), name, Glyph.Bell, ms = 4000)); return }; val open = Action(getString(R.string.open), primary = true, icon = Icon.Open) { val type = contentResolver.getType(uri) ?: "*/*"; island?.open(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) }; alert(Alert("pc", getString(R.string.pc_file_in), name, Glyph.Check, listOf(open), 8000)) }
    fun sentToLaptop(ok: Boolean, name: String) { alert(Alert("pc", getString(if (ok) R.string.pc_sent else R.string.pc_send_failed), name, if (ok) Glyph.Check else Glyph.Bell, ms = 4000)) }

    private fun otpFrom(sbn: StatusBarNotification): String? { val x = sbn.notification.extras; val parts = listOf(x.getCharSequence(Notification.EXTRA_TITLE), x.getCharSequence(Notification.EXTRA_TEXT), x.getCharSequence(Notification.EXTRA_BIG_TEXT), lastBundle(sbn.notification)?.getCharSequence("text"), sbn.notification.tickerText).mapNotNull { it?.toString() }.distinct(); val code = Otp.find(parts.joinToString("\n")) ?: return null; val now = SystemClock.uptimeMillis(); codes.entries.removeAll { now - it.value > 120_000 }; if (codes.containsKey(code)) return code; codes[code] = now; pc.otp(appLabel(sbn.packageName), code); return code }

    private fun forward(sbn: StatusBarNotification, rankingMap: RankingMap?) { val n = sbn.notification; val isl = island ?: return; if (sbn.isOngoing || !isMessage(n) || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return; if (rankingMap != null) { val rank = Ranking(); if (rankingMap.getRanking(sbn.key, rank) && rank.importance < NotificationManager.IMPORTANCE_DEFAULT) return }; val (title, text) = chat(n); if (title.isEmpty() && text.isEmpty()) return; val sig = sbn.packageName + "|" + title + "|" + text; val nowMs = SystemClock.uptimeMillis(); recent.entries.removeAll { nowMs - it.value > 60_000 }; if (recent.containsKey(sig)) return; recent[sig] = nowMs; val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked; val reveal = prefs.notifContent && !locked; val app = appLabel(sbn.packageName); val badge = appIcon(sbn.packageName); val icon = n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: badge; val reply = if (reveal) n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } else null; val others = if (reveal) n.actions?.filter { it !== reply && it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }?.take(2).orEmpty() else emptyList(); val built = if (reveal) Message(sbn.key, title.ifEmpty { app }, text, icon, n.contentIntent, reply, others, app, badge, sbn.postTime) else Message(sbn.key, app, getString(R.string.new_message), badge, n.contentIntent, app = app, appIcon = badge, at = sbn.postTime); isl.show(built) }

    private fun timerEnded(k: Timers.Kind) { Bell.ring(this); val (title, body) = when (k) { Timers.Kind.Focus -> getString(R.string.t_focus_done) to getString(R.string.t_break_now, prefs.int("focus.break", 5)); Timers.Kind.Break -> getString(R.string.t_break_done) to getString(R.string.t_again); else -> getString(R.string.t_done) to "" }; val acts = if (k == Timers.Kind.Break) listOf(Action(getString(R.string.t_focus_n, 30), primary = true, icon = Icon.Timer) { timers.focus(30) }) else emptyList(); alert(Alert("timer", title, body, Glyph.Check, acts, 8000)) }
    private fun prayAlert(i: Int, at: Long, now: Boolean) { val name = Prayer.names[i]; val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)); val title = if (now) getString(R.string.pray_now, name) else getString(R.string.pray_soon, name, prefs.int("pray.before", 10)); alert(Alert("pray", title, "${reminders.city()?.name ?: ""} · $time", Glyph.Moon, ms = if (now) 10_000 else 7000)) }
    private fun nudge(n: Nudge) { val later = Action(getString(R.string.later)) { reminders.snooze(15) }; when (n) { Nudge.Drink -> alert(Alert("nudge", getString(R.string.n_drink), getString(R.string.n_drink_count, reminders.drinkCount(), reminders.drinkGoal()), Glyph.Drop, listOf(Action(getString(R.string.n_drank), primary = true, icon = Icon.Check) { reminders.drank() }, later), 12_000)); Nudge.Eyes -> alert(Alert("nudge", getString(R.string.n_eyes), getString(R.string.n_eyes_body), Glyph.Eye, listOf(later), 20_000)); Nudge.Stretch -> alert(Alert("nudge", getString(R.string.n_stretch), getString(R.string.n_stretch_body), Glyph.Stretch, listOf(later), 12_000)) } }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName != packageName) AppFilter.saw(prefs, sbn.packageName)
        val own = sbn.packageName == packageName
        val code = if (!own && pc.linked && pc.sendOtp) otpFrom(sbn) else null
        // User turned this app off
        if (!AppFilter.allowed(prefs, sbn.packageName)) return
        live.posted(sbn)
        if (!own && code == null && pc.linked && pc.online) forward(sbn, rankingMap)
        if (sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b") startWhatsAppTimerIfMatched(sbn)
        val isl = island ?: return
        if (!own) isl.startled(); if (!prefs.notifs) return
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing || !isMessage(n)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (rankingMap != null) { val rank = Ranking(); if (rankingMap.getRanking(sbn.key, rank) && rank.importance < NotificationManager.IMPORTANCE_DEFAULT) return }
        val (title, text) = chat(n); if (title.isEmpty() && text.isEmpty()) return
        val sig = sbn.packageName + "|" + title + "|" + text; val nowMs = SystemClock.uptimeMillis(); recent.entries.removeAll { nowMs - it.value > 60_000 }; if (recent.containsKey(sig)) return
        recent[sig] = nowMs
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked; val reveal = prefs.notifContent && !locked
        val app = appLabel(sbn.packageName); val badge = appIcon(sbn.packageName); val icon = n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: badge
        val reply = if (reveal) n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } else null
        val others = if (reveal) n.actions?.filter { it !== reply && it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }?.take(2).orEmpty() else emptyList()
        val built = if (reveal) Message(sbn.key, title.ifEmpty { app }, text, icon, n.contentIntent, reply, others, app, badge, sbn.postTime) else Message(sbn.key, app, getString(R.string.new_message), badge, n.contentIntent, app = app, appIcon = badge, at = sbn.postTime)
        isl.show(built)
    }

    private fun startWhatsAppTimerIfMatched(sbn: StatusBarNotification) {
        if (!prefs.bool(WhatsAppTimer.ENABLED, false)) return
        val n = sbn.notification
        if (sbn.isOngoing || !isMessage(n) || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val (title, text) = chat(n)
        @Suppress("DEPRECATION")
        val msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val count = msgs?.size ?: 0
        val lastTime = (msgs?.lastOrNull() as? android.os.Bundle)?.getLong("time", 0L) ?: 0L
        val stamp = if (lastTime != 0L) lastTime else n.`when`
        val sig = "$count|$stamp|$title|$text"
        if (waSeen[sbn.key] == sig) return
        if (waSeen.size > 64) waSeen.clear()
        waSeen[sbn.key] = sig
        WhatsAppTimer.matches(prefs, title, n)?.let { target ->
            WhatsAppTimer.notifyTarget(this, target, prefs)
        }
    }

    private fun isMessage(n: Notification): Boolean { if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false; if (n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL) return true; if (n.extras.containsKey(Notification.EXTRA_MESSAGES)) return true; return n.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true }
    private fun chat(n: Notification): Pair<String, String> { val x = n.extras; val conv = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }; val last = lastBundle(n); val sender = last?.let { senderOf(it) }; val group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) && conv != null; val body = last?.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty(); val title = (if (group) conv else sender ?: conv ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()).orEmpty(); return title to (if (group && sender != null) "$sender: $body" else body) }

    @Suppress("DEPRECATION") private fun lastBundle(n: Notification): android.os.Bundle? = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle
    @Suppress("DEPRECATION") private fun senderOf(b: android.os.Bundle): String? { val person = b.getParcelable<android.app.Person>("sender_person"); return (person?.name ?: b.getCharSequence("sender"))?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
    private fun appLabel(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun appIcon(pkg: String): Bitmap? = try { bitmap(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) { null }
    private fun bitmap(d: Drawable): Bitmap { if (d is BitmapDrawable && d.bitmap != null) return d.bitmap; val side = (48 * resources.displayMetrics.density).toInt(); val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888); val c = Canvas(bmp); d.setBounds(0, 0, side, side); d.draw(c); return bmp }

    private var bankMonitoring = false
    private var wasBankForeground = false
    private var bankPromptShown = false

    fun onBankModeStarted(pkg: String?) {
        wasBankForeground = true
        bankPromptShown = false
        startBankMonitor()
    }

    private fun startBankMonitor() {
        if (bankMonitoring) return
        bankMonitoring = true
        checkBankStatus()
    }

    private fun checkBankStatus() {
        if (!Access.paused(this)) {
            bankMonitoring = false
            bankPromptShown = false
            wasBankForeground = false
            return
        }
        val pkg = prefs.str(Access.PAUSED_PKG, "")
        val inForeground = isAppInForeground(pkg)
        val pausedAt = prefs.long(Access.PAUSED_AT, 0L)
        val elapsed = SystemClock.uptimeMillis() - pausedAt

        if (wasBankForeground && !inForeground && !bankPromptShown && elapsed > 1500L) {
            bankPromptShown = true
            promptBankRestore()
        } else if (inForeground) {
            wasBankForeground = true
        }

        android.os.Handler(mainLooper).postDelayed({
            if (Access.paused(this)) checkBankStatus() else { bankMonitoring = false; bankPromptShown = false }
        }, 2000L)
    }

    private fun isAppInForeground(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return false
        val procs = am.runningAppProcesses ?: return false
        return procs.any { it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && it.processName == pkg }
    }

    fun promptBankRestore() {
        val act = Action(getString(R.string.bank_restore_action), primary = true, icon = Icon.Hand) {
            Access.openSettings(this)
        }
        alert(Alert(
            key = "bank_restore",
            title = getString(R.string.bank_restore_title),
            body = getString(R.string.bank_restore_body),
            glyph = Glyph.Bolt,
            actions = listOf(act),
            ms = 12000L
        ))
    }

    fun rehost() { island?.detach(); island = null; ensureIsland() }
    fun prefsChanged() { island?.prefsChanged() }
    fun test() { ensureIsland(); island?.show(Message("tepi.test", getString(R.string.test_title), getString(R.string.test_text), appIcon(packageName), null)) }
    fun command(what: String): Boolean { ensureIsland(); val isl = island ?: return false; when (what) { Open.HOME -> isl.openHome(); "focus" -> timers.focus(30); "timer" -> timerScene?.let { isl.expand(it) }; "note" -> newNote(); else -> return false }; return true }

    companion object {
        private const val REBIND_DELAY_MS = 2000L
        var instance: Listener? = null
            private set
        var connected = false
            private set
        fun translated(src: String, out: String, pair: String): Boolean { val l = instance ?: return false; l.ensureIsland(); val t = l.translateScene ?: return false; t.show(src, out, pair); return true }
        fun shelved(msg: String): Boolean { val l = instance ?: return false; if (l.island == null) return false; l.alert(Alert("shelf", msg, "", Glyph.Check, ms = 2500)); return true }
        fun hasAccess(context: Context): Boolean = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, Listener::class.java))
    }
}
