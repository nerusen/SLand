package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.odlaver.tepi.Lyrics
import io.github.odlaver.tepi.Media
import io.github.odlaver.tepi.Prefs
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.SongLyrics
import io.github.odlaver.tepi.Spring
import kotlin.math.min
import kotlin.math.sin

class MusicScene(host: Host, private val media: Media, private val prefs: Prefs, private val lyrics: Lyrics) : Scene(host) {
    override val id = "music"
    override val priority = 40
    override val order = 1
    private val hitOpen = RectF()

    private val main = Handler(Looper.getMainLooper())
    private var song: SongLyrics? = null
    private var songKey: String? = null
    private var lyricIdx = -1
    private var lyricPrev = ""
    private val lyricT = Spring(1f, 0.004f, 260f, 32f)
    private val playLevel = Spring(0f, 0.004f, 160f, 26f)
    private var wasPlaying = false
    private var pausedAt = 0L
    private var dismissedKey: String? = null
    private val tmp = RectF()
    private val hitPrev = RectF()
    private val hitPlay = RectF()
    private val hitNext = RectF()
    private val hitBar = RectF()
    private val hitInfo = RectF()
    private val hitLock = RectF()

    // The panel's nine-second timer, switched off.
    //
    // Every other page in the island is somewhere the user goes to do a thing
    // and then leaves, which is what the timer is for: a panel that stayed up
    // would be a panel in the way. Music is the exception, and it is the
    // exception precisely because of how the timer works — the timer is reset
    // by touching the island, and listening to a song is the one activity
    // where the user is deliberately not touching anything. Watching the lyric
    // line change or the time run down is the whole of what they are doing,
    // and the panel closing under them every nine seconds makes it impossible.
    //
    // It releases itself on a page change rather than needing to be turned
    // off. A lock is a thing people forget, and a lock that outlived the page
    // it belongs to would be an island that never closed again — which looks
    // like a bug, not a feature. So the island asks this page whether it is
    // still holding, and moving away is the answer being no.
    var lockOn = false
        private set

    fun unlock() { lockOn = false }

    val playing get() = media.playing
    val track get() = media.track

    fun changed() {
        val tr = media.track
        if (tr != null && prefs.str("music.pkg", "") != tr.pkg) prefs.set("music.pkg", tr.pkg)
        if (tr == null || tr.key != songKey) {
            song = null
            lyricIdx = -1
            songKey = tr?.key
            if (tr != null && prefs.lyrics) {
                val k = tr.key
                lyrics.load(tr.title, tr.artist, tr.durationMs, prefs.lyricsOnline) { got ->
                    if (songKey == k) { song = got; host.refresh() }
                }
            }
        }
        if (tr?.key != dismissedKey) dismissedKey = null
        if (media.playing) {
            pausedAt = 0
            wasPlaying = true
        } else if (wasPlaying) {
            pausedAt = SystemClock.uptimeMillis()
            wasPlaying = false
            main.postDelayed({ host.refresh() }, PAUSED_MS + 50)
        }
        host.refresh()
    }

    fun reload() {
        songKey = null
        changed()
    }

    override fun live(): Boolean {
        val tr = media.track ?: return false
        if (tr.key == dismissedKey || !prefs.music || !io.github.odlaver.tepi.AppFilter.allowed(prefs, tr.pkg)) return false
        return media.playing || (pausedAt > 0 && SystemClock.uptimeMillis() - pausedAt < PAUSED_MS)
    }

    // Has a track at all, for Home
    fun present() = media.track != null

    override fun tab() = prefs.music
    override fun keepOpen() = true

    override fun dismiss() { dismissedKey = media.track?.key }

    override fun ticking() = media.playing

    override fun step(dt: Float): Boolean {
        playLevel.target = if (media.playing) 1f else 0f
        var moving = playLevel.step(dt)
        moving = lyricT.step(dt) or moving
        if (updateLyric()) host.refresh()
        return moving
    }

    private fun currentLine(): String? {
        if (!prefs.lyrics) return null
        val s = song ?: return null
        if (lyricIdx < 0 || lyricIdx >= s.lines.size) return null
        return s.lines[lyricIdx].text.takeIf { it.isNotEmpty() }
    }

    // What the compact lyric bar shows. Once the first line has played the bar
    // stays up for the rest of the song, carrying a note through instrumental
    // gaps: letting it close and reopen between verses would shrink and regrow
    // the island each time, which is worse than an empty bar.
    private fun rowLine(): String? {
        if (!prefs.lyrics) return null
        val s = song ?: return null
        if (s.lines.isEmpty() || lyricIdx < 0) return null
        return currentLine() ?: "♪"
    }

    private fun updateLyric(): Boolean {
        val s = song ?: return false
        if (s.lines.isEmpty()) return false
        val idx = s.indexAt(media.positionMs() + 150)
        if (idx == lyricIdx) return false
        lyricPrev = rowLine() ?: ""
        lyricIdx = idx
        lyricT.snap(0f)
        lyricT.target = 1f
        return true
    }

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val tr = media.track ?: return
        val s = min(left.height(), ui.dp(22f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        ui.art(c, tr.art, tmp, a)
        bars(c, right.right - s / 2, right.centerY(), s * 0.8f, a)
    }

    // One width for the whole song, not one per line. The island sizes the
    // lyric bar from this, so measuring the current line would resize the
    // shape on every line change: exactly where the eye is resting. Measured
    // once when the song's lines arrive, then held.
    private var rowW = 0f
    private var rowWKey: String? = null

    override fun rowWidth(): Float {
        val s = song
        if (s == null || !prefs.lyrics || s.lines.isEmpty()) return 0f
        if (rowLine() == null) return 0f
        if (rowWKey != s.key) {
            var widest = 0f
            for (ln in s.lines) if (ln.text.isNotEmpty()) widest = maxOf(widest, ui.lyric.measureText(ln.text))
            rowW = widest + ui.dp(40f)
            rowWKey = s.key
        }
        return rowW
    }

    override fun drawRow(c: Canvas, r: RectF, a: Float) {
        val line = rowLine() ?: return
        val k = lyricT.value.coerceIn(0f, 1f)
        val shift = ui.dp(10f)
        val w = r.width() - ui.s24
        if (k < 1f && lyricPrev.isNotEmpty()) ui.centered(c, lyricPrev, ui.lyric, r.centerX(), r.centerY() - shift * k, w, a * (1 - k))
        ui.centered(c, line, ui.lyric, r.centerX(), r.centerY() + shift * (1 - k), w, a * k)
    }

    override fun drawBubble(c: Canvas, r: RectF, a: Float) {
        val tr = media.track ?: return
        tmp.set(r)
        tmp.inset(r.width() * 0.18f, r.height() * 0.18f)
        ui.art(c, tr.art, tmp, a, 0.5f)
    }

    private fun hasLyrics() = prefs.lyrics && song?.lines?.isNotEmpty() == true

    override fun panelHeight(w: Float): Float {
        if (media.track == null) return ui.dp(18f) + ui.s12 + ui.dp(36f) + ui.s12 + ui.btnH
        var h = ui.dp(48f) + ui.dp(16f) + ui.dp(4f) + ui.dp(18f) + ui.dp(4f) + ui.dp(44f)
        if (hasLyrics()) h += ui.dp(8f) + ui.dp(38f)
        return h
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val tr = media.track
        if (tr == null) {
            // Nothing playing: one way to start
            ui.icon(c, io.github.odlaver.tepi.Icon.Music, r.left + ui.dp(9f), r.top + ui.dp(9f), ui.dp(16f), ui.sub, a)
            ui.text(c, host.ctx.getString(R.string.music), ui.label, r.left + ui.dp(26f), ui.baseline(ui.label, r.top + ui.dp(9f)), r.width(), a)
            val y = r.top + ui.dp(18f) + ui.s12
            ui.text(c, host.ctx.getString(R.string.music_idle), ui.body, r.left, y + ui.dp(16f), r.width(), a)
            hitOpen.set(r.left, y + ui.dp(36f) + ui.s12, r.right, y + ui.dp(36f) + ui.s12 + ui.btnH)
            ui.button(c, hitOpen, host.ctx.getString(R.string.open_player), a, true, io.github.odlaver.tepi.Icon.Play)
            return
        }
        hitOpen.setEmpty()
        val art = ui.dp(48f)
        tmp.set(r.left, r.top, r.left + art, r.top + art)
        ui.art(c, tr.art, tmp, a)
        val tx = r.left + art + ui.s12
        // The lock sits at the top right, where nothing else is: the title row
        // runs out before it and the transport controls are centred far below.
        // Its own button rather than a line in a menu, because what it is for
        // is being reached while a song is already playing.
        val lb = ui.dp(32f)
        hitLock.set(r.right - lb, r.top + (art - lb) / 2, r.right, r.top + (art + lb) / 2)
        ui.fill(c, hitLock, lb / 2, if (lockOn) ui.lead else ui.fill, a)
        ui.icon(c, io.github.odlaver.tepi.Icon.Lock, hitLock.centerX(), hitLock.centerY(), ui.dp(16f),
            if (lockOn) ui.onLead else ui.sub, a)
        hitInfo.set(r.left, r.top, hitLock.left - ui.s8, r.top + art)
        val tw = hitLock.left - ui.s8 - tx
        ui.text(c, tr.title, ui.title, tx, r.top + ui.dp(20f), tw, a)
        if (tr.artist.isNotEmpty()) ui.text(c, tr.artist, ui.body, tx, r.top + ui.dp(38f), tw, a)

        val barY = r.top + art + ui.dp(16f)
        val dur = tr.durationMs
        val pos = media.positionMs()
        tmp.set(r.left, barY, r.right, barY + ui.dp(4f))
        ui.fill(c, tmp, ui.dp(2f), ui.fillStrong, a)
        if (dur > 0) {
            tmp.right = r.left + r.width() * (pos.toFloat() / dur).coerceIn(0f, 1f)
            ui.fill(c, tmp, ui.dp(2f), ui.sub, a)
            ui.text(c, ui.clock(pos), ui.small, r.left, barY + ui.dp(18f), ui.dp(60f), a)
            ui.textEnd(c, ui.clock(dur), ui.small, r.right, barY + ui.dp(18f), a)
        }
        hitBar.set(r.left, barY - ui.s16, r.right, barY + ui.s16)

        val cy = barY + ui.dp(4f) + ui.dp(18f) + ui.dp(4f) + ui.dp(22f)
        val cx = r.centerX()
        val gap = ui.dp(64f)
        val hit = ui.dp(24f)
        hitPrev.set(cx - gap - hit, cy - hit, cx - gap + hit, cy + hit)
        hitPlay.set(cx - hit, cy - hit, cx + hit, cy + hit)
        hitNext.set(cx + gap - hit, cy - hit, cx + gap + hit, cy + hit)
        ui.icon(c, io.github.odlaver.tepi.Icon.Prev, cx - gap, cy, ui.dp(22f), ui.ink, a)
        ui.icon(c, if (media.playing) io.github.odlaver.tepi.Icon.Pause else io.github.odlaver.tepi.Icon.Play, cx, cy, ui.dp(28f), ui.ink, a)
        ui.icon(c, io.github.odlaver.tepi.Icon.Next, cx + gap, cy, ui.dp(22f), ui.ink, a)

        if (!hasLyrics()) return
        val lines = song?.lines ?: return
        val ly = cy + ui.dp(22f) + ui.dp(8f) + ui.dp(9f)
        ui.centered(c, currentLine() ?: "♪", ui.lyric, cx, ly, r.width(), a)
        val next = lines.getOrNull(lyricIdx + 1)?.text
        if (!next.isNullOrEmpty()) ui.centered(c, next, ui.body, cx, ly + ui.dp(19f), r.width(), a)
    }

    override fun tap(x: Float, y: Float): Boolean {
        if (media.track == null) {
            if (!hitOpen.contains(x, y)) return false
            val last = prefs.str("music.pkg", "")
            val i = (if (last.isNotEmpty()) host.ctx.packageManager.getLaunchIntentForPackage(last) else null)
                ?: android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_MUSIC)
            host.open(i)
            host.collapse()
            return true
        }
        when {
            hitLock.contains(x, y) -> {
                lockOn = !lockOn
                // The island re-arms its timer on every tap, so turning the
                // lock off leaves nine seconds on the clock rather than
                // closing the panel out from under the finger that did it
                host.refresh()
            }
            hitPlay.contains(x, y) -> media.toggle()
            hitPrev.contains(x, y) -> media.prev()
            hitNext.contains(x, y) -> media.next()
            hitBar.contains(x, y) -> media.track?.durationMs?.takeIf { it > 0 }?.let { d ->
                media.seek((d * ((x - hitBar.left) / hitBar.width()).coerceIn(0f, 1f)).toLong())
            }
            hitInfo.contains(x, y) -> media.track?.pkg?.let { pkg ->
                host.ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { host.open(it); host.collapse() }
            }
            else -> return false
        }
        host.haptic()
        return true
    }

    // Decorative bars: follow play state, not audio
    private fun bars(c: Canvas, cx: Float, cy: Float, maxH: Float, a: Float) {
        val w = ui.dp(2.6f)
        val gap = ui.dp(2.4f)
        val total = w * 4 + gap * 3
        val secs = SystemClock.uptimeMillis() / 1000f
        val level = playLevel.value.coerceIn(0f, 1f)
        for (i in 0 until 4) {
            val wave = 0.5f + 0.25f * sin(secs * (5.1f + i * 1.7f) + i * 1.3f) + 0.25f * sin(secs * (8.3f - i) + i * 2.1f)
            val h = maxH * (0.2f + 0.8f * wave * level).coerceIn(0.2f, 1f)
            val x = cx - total / 2 + i * (w + gap)
            tmp.set(x, cy - h / 2, x + w, cy + h / 2)
            ui.fill(c, tmp, w / 2, ui.ink, a)
        }
    }

    override fun describe(): String {
        val tr = media.track ?: return ""
        return host.ctx.getString(R.string.cd_music, tr.title, tr.artist)
    }

    companion object {
        private const val PAUSED_MS = 20_000L
    }
}
