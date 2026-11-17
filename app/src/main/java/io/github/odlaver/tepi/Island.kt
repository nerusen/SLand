package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.odlaver.tepi.scene.Action
import io.github.odlaver.tepi.scene.HomeRow
import io.github.odlaver.tepi.scene.HomeScene
import io.github.odlaver.tepi.scene.Host
import io.github.odlaver.tepi.scene.MenuScene
import io.github.odlaver.tepi.scene.Message
import io.github.odlaver.tepi.scene.MessageScene
import io.github.odlaver.tepi.scene.MusicRow
import io.github.odlaver.tepi.scene.MusicScene
import io.github.odlaver.tepi.scene.Scene
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Overlay around the front camera; hosts scenes
@SuppressLint("ViewConstructor")
class Island(
    context: Context,
    private val probeCtx: Context?,
    private val screen: Display,
    private val type: Int,
    media: Media,
    private val prefs: Prefs,
    lyrics: Lyrics,
) : View(context), Host {
    // Idle: a resting capsule when nothing is happening
    private enum class Kind { Hidden, Idle, Compact, Panel }

    override val ui = Ui(context)
    override val ctx: Context get() = context
    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
    override val locked get() = keyguard.isKeyguardLocked

    // Lock and unlock change what may be shown
    private val lockWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (locked && opened?.personal == true) opened = null
            refresh()
        }
    }
    private var listening = false
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val touchable get() = type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    // Scenes
    val music = MusicScene(this, media, prefs, lyrics)
    val messages = MessageScene(this)
    val home = HomeScene(this) { homeActions() }
    private val menu = MenuScene(this) { menuItems() }
    private val scenes = arrayListOf<Scene>(messages, music)
    val menuExtras = ArrayList<() -> Action?>()
    val homeExtras = ArrayList<() -> Action?>()

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    private val lp = WindowManager.LayoutParams(
        1, 1, type, baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Tepi"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        // The window is resized and moved under the shape: grown for a
        // transition, shrunk back once it settles. WindowManager animates
        // every change of a window's position by default, sliding the surface
        // from the old spot to the new one while the content inside has
        // already been drawn for the new spot. Closing a panel back to the
        // resting capsule shrinks the window from the left, so the capsule
        // was shown displaced to the left for that slide and then crept back
        // to the lens. The island does all of its own motion; the window has
        // to land where it is put, instantly.
        noMoveAnimation(this)
    }
    private var attached = false
    private var input: Input? = null

    // Geometry, screen px
    private var screenW = 0f
    private val hole = RectF()
    private val pill = RectF()
    private var statusBottom = 0f
    private var wingBase = 0f
    // Centre and width, not two edges. Two edge springs only hold the centre
    // still while their targets are symmetric and their velocities match; a
    // retarget mid-flight, or a target nudged sideways to clear a screen edge,
    // leaves the pair out of step and the shape visibly slides across as it
    // opens or closes. One centre spring cannot drift.
    private val midX = Spring(0f, 0.3f)
    private val wid = Spring(0f, 0.3f)
    private val t = Spring(0f, 0.3f)
    private val b = Spring(0f, 0.3f)
    private val rad = Spring(0f, 0.3f)
    private val neck = Spring(0f, 0.3f)
    private val split = Spring(0f, 0.3f)
    private val radB = Spring(0f, 0.3f)
    private val bubbleK = Spring(0f, 0.004f, 420f, 30f)
    private val contentA = Spring(0f, 0.004f, 520f, 46f)
    // Logo and battery fade with the resting capsule, apart from scene content
    private val idleA = Spring(0f, 0.004f, 520f, 46f)
    // Tab bar: drives both its own fade and the panel's extra height. It is
    // given the same constants as the bottom edge so the two move in lockstep;
    // a stiffer or softer spring here would let the bar drift away from the
    // edge it is supposed to be riding.
    private val navA = Spring(0f, 0.004f, 380f, 32f)
    private val now = RectF()
    private val target = RectF()
    private val bubble = RectF()

    // State
    private var kind = Kind.Hidden
    private var primary: Scene? = null
    private var second: Scene? = null
    private var opened: Scene? = null
    private var shownKey = ""
    private var wantedKey = ""
    private var shownScene: Scene? = null
    private var shownKind = Kind.Hidden
    private var fullscreen = false
    private var hiddenUntil = 0L
    private var lastTouch = 0L
    private var batteryLevel = -1
    private var batteryCharging = false
    // Set by navOn and navOff in the automatic mode
    private var navAuto = false
    // The user may pin the bar up instead; then there is nothing to toggle
    // and nothing to time out
    private val navShown get() = prefs.navAlways || navAuto
    private var navHideAt = 0L
    private var navCell = 0f
    private val dotsHit = RectF()
    private val navBar = RectF()
    // The page list is wanted several times per frame, by the panel's height
    // as well as its dots and tab bar; rebuilding it each time would allocate
    // on every frame, so refresh settles it once
    private var pageList: List<Scene> = emptyList()

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val shape = Path()
    private val tierA = Path()
    private val tierB = Path()
    private val box = RectF()
    private val loc = IntArray(2)
    private val zoneL = RectF()
    private val zoneR = RectF()
    private val content = RectF()

    init {
        home.rows.add(MusicRow(this, music, media))
    }

    fun register(scene: Scene, homeRow: HomeRow? = null) {
        scenes.add(scene)
        if (homeRow != null) home.rows.add(homeRow)
    }

    fun attach() {
        if (attached) return
        measureScreen()
        midX.snap(hole.centerX()); wid.snap(hole.width()); t.snap(hole.top); b.snap(hole.bottom); rad.snap(hole.height() / 2)
        neck.snap(hole.width()); split.snap(hole.top); radB.snap(hole.height() / 2)
        place(hole, false)
        wm.addView(this, lp)
        attached = true
        try {
            context.applicationContext.registerReceiver(lockWatch, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }, Context.RECEIVER_NOT_EXPORTED)
            listening = true
        } catch (_: Exception) { }
        if (probeCtx != null && Settings.canDrawOverlays(context)) {
            val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            p.gravity = Gravity.TOP or Gravity.START
            try { probeCtx.getSystemService(WindowManager::class.java).addView(probe, p); probing = true } catch (_: Exception) { }
        }
        refresh()
    }

    fun detach() {
        if (!attached) return
        input?.close()
        main.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
        wm.removeView(this)
        if (listening) { try { context.applicationContext.unregisterReceiver(lockWatch) } catch (_: Exception) { }; listening = false }
        if (probing) { try { probeCtx?.getSystemService(WindowManager::class.java)?.removeView(probe) } catch (_: Exception) { }; probing = false }
        attached = false
    }

    fun show(m: Message) {
        if (opened != null && opened !== messages) opened = null
        messages.show(m)
    }

    fun mediaChanged() = music.changed()

    // Straight from ACTION_BATTERY_CHANGED, so the resting capsule shows the
    // same level Android does
    fun battery(level: Int, charging: Boolean) {
        if (level == batteryLevel && charging == batteryCharging) return
        batteryLevel = level
        batteryCharging = charging
        // onDraw reads the level directly; no geometry moved, so a repaint is
        // enough and the frame loop can stay asleep
        if (kind == Kind.Idle) invalidate()
    }

    fun prefsChanged() {
        measureScreen()
        music.reload()
        refresh()
    }

    fun openHome() = expand(home)

    // Host
    override fun expand(scene: Scene) {
        opened = scene
        lastTouch = SystemClock.uptimeMillis()
        armClose()
        refresh()
    }

    override fun collapse() {
        if (opened == null) return
        opened = null
        refresh()
    }

    override fun open(i: Intent) {
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun send(p: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                p.send(context, 0, null, null, null, null, o.toBundle())
            } else p.send()
        } catch (_: Exception) { }
    }

    override fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun input(hint: String, initial: String, done: (String) -> Unit) {
        val card = input ?: Input(context, type, ui).also { input = it }
        val w = min(screenW - ui.edge * 2, ui.panelMax)
        main.removeCallbacks(closePanel)
        card.show(hint, initial, (hole.centerX() - w / 2).toInt(), (statusBottom + ui.s8).toInt(), w.toInt()) { text ->
            armClose()
            done(text)
        }
    }

    private val closePanel = Runnable {
        if (opened != null && SystemClock.uptimeMillis() - lastTouch >= CLOSE_MS - 100) { opened = null; refresh() }
    }

    private fun armClose() {
        main.removeCallbacks(closePanel)
        main.postDelayed(closePanel, CLOSE_MS)
    }

    private fun homeActions(): List<Action> {
        val out = ArrayList<Action>()
        for (f in homeExtras) f()?.let { out.add(it) }
        return out.filter { !(locked && it.personal) }.take(3)
    }

    private fun menuItems(): List<Action> {
        val out = ArrayList<Action>()
        for (f in menuExtras) f()?.let { out.add(it) }
        out.add(Action(context.getString(R.string.home), icon = Icon.Home) { main.post { openHome() } })
        if (hiddenUntil > SystemClock.uptimeMillis()) out.add(Action(context.getString(R.string.unhide), icon = Icon.Eye) { hiddenUntil = 0; refresh() })
        else out.add(Action(context.getString(R.string.hide_hour), icon = Icon.Hide) { hiddenUntil = SystemClock.uptimeMillis() + 3_600_000L; refresh() })
        out.add(Action(context.getString(R.string.settings), icon = Icon.Gear, personal = true) { open(Intent(context, MainActivity::class.java)) })
        return out.filter { !(locked && it.personal) }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        measureScreen()
        refresh()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) sense(insets)
        return super.onApplyWindowInsets(insets)
    }

    // Status bar gone: a fullscreen app is up
    private fun sense(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < 30) return
        val full = !insets.isVisible(WindowInsets.Type.statusBars())
        if (full != fullscreen) { fullscreen = full; refresh() }
    }

    // Accessibility windows never see the status bar
    private val probe = object : View(probeCtx ?: context) {
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            sense(insets)
            return super.onApplyWindowInsets(insets)
        }
    }
    private var probing = false

    @Suppress("DEPRECATION")
    private fun measureScreen() {
        val m = DisplayMetrics()
        screen.getRealMetrics(m)
        screenW = m.widthPixels.toFloat()
        val cut = screen.cutout
        val top = cut?.boundingRectTop?.takeIf { !it.isEmpty }
        if (top != null) {
            hole.set(top)
            // Path is tighter than the bounding box
            if (Build.VERSION.SDK_INT >= 31) cut?.cutoutPath?.let { p ->
                val bounds = RectF()
                p.computeBounds(bounds, true)
                if (bounds.intersect(hole)) hole.set(bounds)
            }
            // Bar down from the edge: a round hole inside
            if (hole.top <= 1f && hole.height() > hole.width() * 1.4f) {
                val rr = hole.width() / 2
                val cy = hole.height() / 2
                hole.set(hole.centerX() - rr, cy - rr, hole.centerX() + rr, cy + rr)
            }
        } else {
            hole.set(screenW / 2 - ui.dp(10f), ui.dp(10f), screenW / 2 + ui.dp(10f), ui.dp(30f))
        }
        hole.offset(0f, ui.dp(prefs.offsetDp.toFloat()))
        val pad = ui.dp(5f)
        pill.set(hole.left - pad, max(0f, hole.top - pad), hole.right + pad, hole.bottom + pad)
        if (pill.height() < ui.dp(30f)) pill.bottom = pill.top + ui.dp(30f)
        val bar = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it != 0) resources.getDimensionPixelSize(it) else 0 }
        statusBottom = max(pill.bottom, (cut?.safeInsetTop?.takeIf { it > 0 } ?: bar).toFloat())
        wingBase = max(pill.height() * 1.3f, ui.dp(36f))
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Widest shape that still keeps the lens in the middle. Clamping width
    // rather than nudging the shape sideways is what keeps every kind centred
    // on the same point, so nothing slides when one replaces another.
    private val span get() = max(pill.width(), 2 * min(hole.centerX() - ui.edge, screenW - ui.edge - hole.centerX()))
    private val wide get() = min(min(screenW - ui.edge * 2, ui.panelMax), span)

    private fun choose() {
        val o = opened
        if (o != null && o !== home && o !== menu && !o.live() && !o.keepOpen()) opened = null
        val lock = locked
        val live = scenes.filter { it.live() && !(lock && it.personal) }.sortedByDescending { it.priority }
        // The fullscreen probe goes dark under the lock screen; ignore it there
        val quiet = landscape || (fullscreen && !lock) || hiddenUntil > SystemClock.uptimeMillis() || (lock && !prefs.bool("lockscreen", true))
        val shown = if (quiet) live.filter { it.priority >= URGENT } else live
        // The resting capsule outlives fullscreen apps; only the island's own
        // off switches put it away
        val resting = prefs.idleBar && !landscape &&
            hiddenUntil <= SystemClock.uptimeMillis() &&
            !(lock && !prefs.bool("lockscreen", true))
        val op = opened
        when {
            op != null -> { kind = Kind.Panel; primary = op; second = null }
            shown.firstOrNull()?.card() == true -> { kind = Kind.Panel; primary = shown.first(); second = null }
            shown.isNotEmpty() -> {
                kind = Kind.Compact
                primary = shown[0]
                second = shown.getOrNull(1)?.takeIf { !it.card() }
            }
            resting -> { kind = Kind.Idle; primary = null; second = null }
            else -> { kind = Kind.Hidden; primary = null; second = null }
        }
    }

    // Resting capsule: never thinner than the camera cradle, never wider
    // than a panel
    private fun idleWidth() = ui.dp(prefs.idleWidthDp.toFloat()).coerceIn(pill.width(), max(pill.width(), wide))

    private fun compactWidth(s: Scene?) = pill.width() + max(s?.wing() ?: 0f, wingBase) * 2

    // Page dots, shown whenever the open panel has somewhere to swipe to
    private fun dotsH() = if (opened != null && pageList.size > 1) ui.dp(18f) else 0f

    // Room the tab bar asks for. The panel's bottom edge is a spring, so
    // turning this on or off is the whole height animation: the bar is drawn
    // at a fixed place against the resting edge, and the moving edge uncovers
    // it on the way down and covers it again on the way back up.
    private fun navExtra() = if (navShown && kind == Kind.Panel && opened != null && pageList.size > 1) ui.dp(NAV_H) + ui.s8 else 0f

    // Shown on a page change or a tap on the dots, then it steps back out
    private fun navOn() {
        if (prefs.navAlways) return
        navAuto = true
        navHideAt = SystemClock.uptimeMillis() + NAV_MS
        main.removeCallbacks(hideNav)
        main.postDelayed(hideNav, NAV_MS)
    }

    private fun navOff() {
        if (!navAuto) return
        navAuto = false
        navBar.setEmpty()
        main.removeCallbacks(hideNav)
    }

    private val hideNav = Runnable {
        if (navAuto && SystemClock.uptimeMillis() >= navHideAt - 100) { navOff(); refresh() }
    }

    // Recompute target, start motion
    override fun refresh() {
        if (!attached) return
        choose()
        pageList = if (opened != null) pages() else emptyList()
        if (kind != Kind.Panel || opened == null) navOff()
        // Both strips are positioned by drawTabs, which only runs for an open
        // panel with pages. Clearing them here stops a stale rectangle from a
        // previous panel taking taps meant for the scene.
        if (pageList.size < 2) { dotsHit.setEmpty(); navBar.setEmpty() }
        val cx = hole.centerX()
        var nk: Float
        var sy: Float
        var rt = pill.height() / 2
        var rb = rt
        // Every kind is laid out as a width around the lens, so the centre
        // never moves between them
        var w: Float
        var top = pill.top
        var bottom: Float
        when (kind) {
            Kind.Hidden -> {
                w = hole.width(); top = hole.top; bottom = hole.bottom
                nk = hole.width(); sy = hole.top; rt = hole.height() / 2; rb = rt
            }
            Kind.Idle -> {
                w = idleWidth(); bottom = pill.bottom
                nk = w; sy = pill.top
            }
            Kind.Compact -> {
                val pillW = compactWidth(primary)
                val row = primary?.rowWidth() ?: 0f
                if (row > 0f) {
                    w = row.coerceIn(min(pillW, wide), wide)
                    bottom = statusBottom + ui.dp(32f)
                    nk = pillW; sy = statusBottom; rb = ui.dp(18f)
                } else {
                    w = min(pillW, wide); bottom = pill.bottom
                    nk = w; sy = pill.top
                }
            }
            Kind.Panel -> {
                w = wide
                val h = primary?.panelHeight(w - ui.pad * 2) ?: 0f
                bottom = statusBottom + ui.s16 + h + ui.pad + dotsH() + navExtra()
                nk = min(compactWidth(if (primary?.card() == true) null else primary), w)
                sy = statusBottom; rb = ui.radiusPanel
            }
        }
        // Clamping the width keeps the shape centred; offsetting it would not
        w = w.coerceIn(0f, span)
        target.set(cx - w / 2, top, cx + w / 2, bottom)

        midX.target = cx; wid.target = w; t.target = target.top; b.target = target.bottom
        neck.target = nk; split.target = sy; rad.target = rt; radB.target = rb
        bubbleK.target = if (second != null) 1f else 0f
        val d = pill.height()
        bubble.set(target.right + ui.s8, pill.top, target.right + ui.s8 + d, pill.bottom)

        wantedKey = "$kind:${primary?.id}"
        if (wantedKey != shownKey) contentA.target = 0f
        idleA.target = if (kind == Kind.Idle) 1f else 0f
        navA.target = if (navShown && kind == Kind.Panel && pageList.size > 1) 1f else 0f
        contentDescription = primary?.describe()

        // Grow first; shrink once settled
        val union = RectF(target)
        union.union(midX.value - wid.value / 2, t.value, midX.value + wid.value / 2, b.value)
        if (second != null || bubbleK.value > 0.01f) union.union(bubble)
        place(union, wantsTouch())
        kick()
    }

    private fun hotspot() = touchable && prefs.cameraTap

    // The resting capsule sits in the status bar. Only the accessibility
    // window may take touches up there; an app overlay would swallow part of
    // the pull-down gesture for as long as the capsule is up, so it stays
    // untouchable and purely visual instead.
    private fun wantsTouch() = when (kind) {
        Kind.Hidden, Kind.Idle -> hotspot()
        else -> true
    }

    // Window covers the moving shape only
    private fun place(rect: RectF, touch: Boolean) {
        val grow = if (touch && (kind == Kind.Hidden || kind == Kind.Idle)) ui.s8 else 0f
        val x = (rect.left - grow).toInt() - 2
        val y = max(0, (rect.top - grow).toInt() - 2)
        lp.x = x
        lp.y = y
        lp.width = max(1, (rect.right + grow).toInt() + 2 - x)
        lp.height = max(1, (rect.bottom + grow).toInt() + 2 - y)
        lp.flags = baseFlags or if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (attached) wm.updateViewLayout(this, lp)
    }

    // Frame loop
    private var ticking = false
    private var lastNs = 0L
    private var frameNo = 0
    private val frame = Choreographer.FrameCallback { ns -> tick(ns) }

    private fun kick() {
        if (ticking) return
        ticking = true
        lastNs = 0
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick(ns: Long) {
        val dt = if (lastNs == 0L) 1f / 60f else ((ns - lastNs) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNs = ns
        var moving = false
        for (s in arrayOf(midX, wid, t, b, rad, neck, split, radB, bubbleK)) moving = s.step(dt) or moving
        moving = contentA.step(dt) or moving
        moving = idleA.step(dt) or moving
        moving = navA.step(dt) or moving
        if (contentA.value <= 0.05f && contentA.target == 0f) {
            shownKey = wantedKey
            shownScene = primary
            shownKind = kind
            val bare = kind == Kind.Hidden || kind == Kind.Idle
            contentA.target = if (bare) 0f else 1f
            if (bare) contentA.snap(0f)
        }
        for (s in scenes) moving = s.step(dt) or moving
        val op = opened
        if (op != null && op !in scenes) moving = op.step(dt) or moving

        val live = kind != Kind.Hidden && (primary?.ticking() == true || second?.ticking() == true)
        // Live content at half rate
        if (moving || !live || frameNo++ % 2 == 0) invalidate()
        if (moving || live) Choreographer.getInstance().postFrameCallback(frame)
        else {
            ticking = false
            settle()
        }
    }

    private fun settle() {
        when (kind) {
            Kind.Hidden -> place(if (hotspot()) pill else hole, hotspot())
            Kind.Idle -> place(target, wantsTouch())
            else -> {
                val rect = RectF(target)
                if (second != null) rect.union(bubble)
                place(rect, true)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val half = max(0f, wid.value) / 2
        now.set(midX.value - half, t.value, midX.value + half, b.value)
        if (kind == Kind.Hidden && shownKind == Kind.Hidden && !ticking) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        buildShape(shape)
        val k = bubbleK.value.coerceIn(0f, 1f)
        if (k > 0.01f) shape.addCircle(bubble.centerX(), bubble.centerY(), pill.height() / 2 * k, Path.Direction.CW)
        canvas.drawPath(shape, bg)
        canvas.clipPath(shape)
        val a = contentA.value.coerceIn(0f, 1f)
        val s = shownScene
        if (a > 0.01f && s != null) when (shownKind) {
            Kind.Compact -> {
                zones(now)
                s.drawCompact(canvas, zoneL, zoneR, a)
                if (now.bottom > pill.bottom + 1f) {
                    val rowA = ((now.bottom - statusBottom) / ui.dp(32f)).coerceIn(0f, 1f) * a
                    box.set(now.left, statusBottom, now.right, now.bottom)
                    if (rowA > 0.02f) s.drawRow(canvas, box, rowA)
                }
            }
            Kind.Panel -> {
                content.set(target.left + ui.pad, statusBottom + ui.s16, target.right - ui.pad, target.bottom - ui.pad - dotsH() - navExtra())
                s.drawPanel(canvas, content, a)
            }
            Kind.Hidden, Kind.Idle -> Unit
        }
        // Panel chrome rides the shape, not the content crossfade. A page
        // change dips contentA to zero and back; blinking the dots and the tab
        // bar in time with it would be the very flicker the bar exists to
        // replace. The clip keeps them inside the shape, so a half-grown panel
        // still cannot leak them.
        if (kind == Kind.Panel && opened != null && pageList.size > 1) drawTabs(canvas, pageList)
        else { dotsHit.setEmpty(); navBar.setEmpty() }
        val sec = second
        if (k > 0.05f && sec != null && shownKind == Kind.Compact) {
            box.set(bubble)
            sec.drawBubble(canvas, box, k * a)
        }
        // Resting chrome last, on its own fade, so it never shows over a
        // scene. Clipped to the capsule, so a half-grown shape cannot leak it.
        val ia = idleA.value.coerceIn(0f, 1f)
        if (ia > 0.01f) drawIdleChrome(canvas, ia)
        canvas.restore()
    }

    // Page dots, and under them the tab bar. Both are placed against the
    // panel's resting edge, not the animated one, so turning the bar on leaves
    // the dots exactly where they were: the only things that move are the
    // island's bottom edge and the bar it uncovers. The canvas is clipped to
    // the shape, so the bar is revealed as the edge sweeps past it and covered
    // again as it comes back, which is the slide the height change is for.
    // Marks follow the page that is actually open, not the one fading out, so
    // the highlight lands with the swipe.
    private fun drawTabs(c: Canvas, list: List<Scene>) {
        val dots = ui.dp(18f)
        val navH = ui.dp(NAV_H)
        val cy = target.bottom - navExtra() - ui.pad / 2 - dots / 2
        val gap = ui.dp(10f)
        var x = target.centerX() - (list.size - 1) * gap / 2
        for (p in list) {
            ui.dot(c, x, cy, ui.dp(3f), if (p === primary) ui.ink else ui.faint, 1f)
            x += gap
        }
        // Tapping the dots is the other way in, so the strip takes touches
        // whether the bar is up or not
        dotsHit.set(target.left, cy - dots / 2 - ui.s4, target.right, cy + dots / 2 + ui.s4)

        val k = navA.value.coerceIn(0f, 1f)
        if (k <= 0.01f) { navBar.setEmpty(); return }
        // Settings is personal, so the lock screen gets the pages only
        val gear = !locked
        val n = list.size + if (gear) 1 else 0
        navCell = min(ui.dp(40f), (target.width() - ui.pad * 2) / n)
        val barW = navCell * n
        val top = cy + dots / 2 + ui.s8
        navBar.set(target.centerX() - barW / 2, top, target.centerX() + barW / 2, top + navH)
        ui.fill(c, navBar, navH / 2, ui.tray, k)
        var cx = navBar.left + navCell / 2
        for (p in list) {
            ui.icon(c, tabIcon(p), cx, navBar.centerY(), ui.dp(18f), if (p === primary) ui.ink else ui.faint, k)
            cx += navCell
        }
        if (gear) ui.icon(c, Icon.Gear, cx, navBar.centerY(), ui.dp(18f), ui.faint, k)
        // Half-uncovered icons are not worth aiming at; the strip takes taps
        // once it is mostly out
        if (k < 0.4f) navBar.setEmpty()
    }

    // Which icon a point in the tab bar lands on. The cells are even, so this
    // is arithmetic rather than a list of rectangles rebuilt every frame.
    private fun navTap(x: Float, y: Float): Boolean {
        if (!navShown || navBar.isEmpty || !navBar.contains(x, y) || navCell <= 0f) return false
        val list = pageList
        val last = list.size + if (locked) -1 else 0
        val i = ((x - navBar.left) / navCell).toInt().coerceIn(0, max(0, last))
        haptic()
        navOn()
        if (i < list.size) {
            if (list[i] !== primary) expand(list[i])
        } else {
            open(Intent(context, MainActivity::class.java))
            collapse()
        }
        return true
    }

    // One mark per page, matching the icon the page itself heads with. Live
    // scenes join the pages while they last, so they are listed here too
    // rather than falling back to a generic mark.
    private fun tabIcon(s: Scene) = when (s.id) {
        "home" -> Icon.Home
        "music" -> Icon.Music
        "timer" -> Icon.Timer
        "agenda" -> Icon.Book
        "prayer" -> Icon.Moon
        "agents" -> Icon.Laptop
        "notes" -> Icon.Note
        "shelf" -> Icon.Shelf
        "tasks" -> Icon.Tasks
        "incoming", "call" -> Icon.Phone
        "nav" -> Icon.Nav
        "clock" -> Icon.Timer
        "progress" -> Icon.Download
        "message" -> Icon.Chat
        "translate" -> Icon.Globe
        "brief" -> Icon.Spark
        "privacy" -> Icon.Shield
        "agent" -> Icon.Laptop
        "alert" -> Icon.Bell
        "menu" -> Icon.Layers
        "github" -> Icon.Github
        "shot" -> Icon.Image
        "volume" -> Icon.Volume
        "flag" -> Icon.Spark
        else -> Icon.Layers
    }

    // Logo hard left, battery hard right, both inside the capsule with the
    // same gap on the left, top and bottom. The camera cradle in the middle is
    // left alone: the marks shrink to fit beside it, and are dropped entirely
    // rather than drawn too small to read.
    private fun drawIdleChrome(c: Canvas, a: Float) {
        val capH = now.height()
        if (capH <= 0f) return
        // Flat room between a capsule end and the lens
        val room = (now.width() - hole.width()) / 2 - ui.s8
        if (room <= 0f) return
        // One height for both marks. Padding is (capH - h) / 2 on every side,
        // so h has to satisfy capH / 2 + h * (aspect - 0.5) <= room. The
        // battery is the wider of the two, so it sets the limit; the extra dp
        // covers the battery's minimum nub width at small heights, so the two
        // marks always appear and disappear together.
        val widest = max(Ui.LOGO_ASPECT, Ui.BATTERY_SLOT_ASPECT)
        val h = min(ui.dp(13f), (room - capH / 2 - ui.dp(1f)) / (widest - 0.5f))
        if (h < ui.dp(7f)) return

        // The capsule ends are round, so the flat inset also has to clear the
        // curve at the mark's own height
        val rr = capH / 2
        val bulge = rr - sqrt(max(0f, rr * rr - (h / 2) * (h / 2)))
        val inset = max((capH - h) / 2, bulge + ui.s4)
        val top = now.centerY() - h / 2

        val logoL = now.left + inset
        if (logoL + h * Ui.LOGO_ASPECT <= hole.left - ui.s8) {
            box.set(logoL, top, logoL + h * Ui.LOGO_ASPECT, top + h)
            ui.logo(c, box, ui.ink, a)
        }

        val battR = now.right - inset
        if (batteryLevel >= 0 && battR - ui.batteryWidth(h) >= hole.right + ui.s8) {
            box.set(battR - ui.batteryWidth(h), top, battR, top + h)
            ui.battery(c, box, batteryLevel, batteryCharging, a)
        }
    }

    // Left and right of the camera inside the neck
    private fun zones(at: RectF) {
        val nk = min(neck.value, at.width())
        val left = at.centerX() - nk / 2
        // Keep content off the rounded ends
        val inset = max(ui.dp(7f), (pill.height() - ui.dp(22f)) / 2)
        val side = max(inset, pill.height() * 0.42f)
        zoneL.set(left + side, pill.top + inset, hole.left - ui.s12, pill.bottom - inset)
        zoneR.set(hole.right + ui.s12, pill.top + inset, left + nk - side, pill.bottom - inset)
    }

    // Neck and body joined by concave fillets
    private fun buildShape(out: Path) {
        out.reset()
        val w = now.width()
        val cx = now.centerX()
        val nk = neck.value.coerceIn(0f, w)
        val sy = split.value.coerceIn(now.top, now.bottom)
        if (w - nk < 1f || sy - now.top < 1f || now.bottom - sy < 1f) {
            val r0 = min(rad.value, min(w, now.height()) / 2)
            out.addRoundRect(now, r0, r0, Path.Direction.CW)
            return
        }
        val rt = min(rad.value, nk / 2)
        val rb = min(radB.value, min(w, now.bottom - sy) / 2)
        tierA.reset()
        box.set(cx - nk / 2, now.top, cx + nk / 2, min(now.bottom, sy + rt))
        tierA.addRoundRect(box, rt, rt, Path.Direction.CW)
        tierB.reset()
        box.set(now.left, sy, now.right, now.bottom)
        tierB.addRoundRect(box, rb, rb, Path.Direction.CW)
        out.op(tierA, tierB, Path.Op.UNION)
        val f = min(ui.dp(12f), min((w - nk) / 2 - rb, sy - now.top))
        if (f < 1f) return
        for (side in intArrayOf(-1, 1)) {
            val x = cx + side * nk / 2
            tierA.reset()
            tierA.addRect(min(x, x + side * f), sy - f, max(x, x + side * f), sy, Path.Direction.CW)
            tierB.reset()
            tierB.addCircle(x + side * f, sy - f, f, Path.Direction.CW)
            tierA.op(tierB, Path.Op.DIFFERENCE)
            out.op(tierA, Path.Op.UNION)
        }
    }

    // Touch
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var acted = false
    // A hold inside an open panel goes to what is under the finger first.
    // Home's Wi-Fi and Bluetooth tiles use it for their settings pages, and
    // the quick menu opening on top of them would be the clash, so the menu
    // only comes up when the scene does not take the hold. The neck around
    // the camera is never scene content, so a hold there is always the menu.
    private val longPress = Runnable {
        if (pressed && !acted) {
            acted = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val s = primary
            val inScene = kind == Kind.Panel && s != null && downY >= statusBottom &&
                !navBar.contains(downX, downY) && !dotsHit.contains(downX, downY)
            if (inScene && s?.hold(downX, downY) == true) { armClose(); refresh() }
            else expand(menu)
        }
    }

    private fun inside(x: Float, y: Float): Boolean {
        if (kind == Kind.Hidden) {
            box.set(pill); box.inset(-ui.s8, -ui.s8)
            return hotspot() && box.contains(x, y)
        }
        if (kind == Kind.Idle) {
            if (!hotspot()) return false
            box.set(now); box.inset(-ui.s4, -ui.s8)
            return box.contains(x, y)
        }
        if (now.contains(x, y)) return true
        return second != null && bubble.contains(x, y)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (opened != null) collapse()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                if (!inside(e.rawX, e.rawY)) return false
                downX = e.rawX
                downY = e.rawY
                pressed = true
                acted = false
                main.postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed || acted) return true
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val slop = ui.dp(24f)
                when {
                    dy > slop && abs(dy) > abs(dx) -> { acted = true; main.removeCallbacks(longPress); swipeDown() }
                    dy < -slop && abs(dy) > abs(dx) -> { acted = true; main.removeCallbacks(longPress); swipeUp() }
                    abs(dx) > ui.dp(48f) && kind == Kind.Panel -> { acted = true; main.removeCallbacks(longPress); page(if (dx < 0) 1 else -1) }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (pressed && !acted && hypot(e.rawX - downX, e.rawY - downY) < ui.dp(12f)) tap(e.rawX, e.rawY)
                pressed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                pressed = false
            }
        }
        return true
    }

    private fun swipeDown() {
        when (kind) {
            Kind.Hidden, Kind.Idle -> openHome()
            Kind.Compact -> primary?.let { expand(it) }
            Kind.Panel -> Unit
        }
    }

    private fun swipeUp() {
        when (kind) {
            Kind.Panel -> if (opened != null) collapse() else primary?.dismiss()
            Kind.Compact -> primary?.dismiss()
            Kind.Hidden, Kind.Idle -> Unit
        }
        refresh()
    }

    // Next or previous live scene, Home last
    // Home, then features that have something to show
    private fun pages(): List<Scene> = listOf(home) + scenes.filter { it.tab() && !(locked && it.personal) }.sortedBy { it.order }

    private fun page(dir: Int) {
        val list = pages()
        if (list.size < 2) return
        val cur = list.indexOf(primary).coerceAtLeast(0)
        val next = cur + dir
        if (next < 0 || next >= list.size) return
        haptic()
        // Changing page is the cue to show the tab bar, so the next hop can be
        // a tap rather than another swipe
        navOn()
        expand(list[next])
    }

    private fun tap(x: Float, y: Float) {
        when (kind) {
            Kind.Hidden, Kind.Idle -> openHome()
            Kind.Compact -> {
                val sec = second
                if (sec != null && bubble.contains(x, y)) expand(sec)
                else primary?.let { if (!it.tapCompact()) expand(it) }
            }
            Kind.Panel -> {
                // Neck around the camera toggles closed
                if (y < statusBottom) { if (opened != null) collapse() else primary?.dismiss() }
                else {
                    armClose()
                    when {
                        navTap(x, y) -> Unit
                        // The dots are the handle for the bar: tap to call it
                        // up, tap again to put it away
                        // Pinned up, the dots have nothing to do
                        dotsHit.contains(x, y) && pageList.size > 1 && !prefs.navAlways -> {
                            haptic()
                            if (navAuto) navOff() else navOn()
                        }
                        else -> primary?.tap(x, y)
                    }
                }
            }
        }
        refresh()
    }

    companion object {
        private const val CLOSE_MS = 9000L
        // Tab bar: how tall, and how long it stays up after the last nudge
        private const val NAV_H = 36f
        private const val NAV_MS = 4500L
        // What still shows through a fullscreen app or a game. The system
        // notices sit just on this side of the line on purpose: a volume
        // press, a screenshot and a mode switch are all answers to something
        // the user just did, and a game or a video player is exactly where
        // they do it. Calls stay above them, so a ringing phone is never
        // covered by a volume bar.
        const val URGENT = 93

        // Hidden flag value, used only when the field itself cannot be read
        private const val NO_MOVE_ANIMATION_FALLBACK = 0x00000040

        // Public from Android 14. Before that the same switch is a private
        // flag; it is set reflectively, and if that is ever refused the
        // window simply keeps the system's move animation as it did before.
        @SuppressLint("SoonBlockedPrivateApi", "DiscouragedPrivateApi")
        private fun noMoveAnimation(p: WindowManager.LayoutParams) {
            if (Build.VERSION.SDK_INT >= 34) {
                p.setCanPlayMoveAnimation(false)
                return
            }
            try {
                val cls = WindowManager.LayoutParams::class.java
                val flag = try { cls.getField("PRIVATE_FLAG_NO_MOVE_ANIMATION").getInt(null) }
                    catch (_: Throwable) { NO_MOVE_ANIMATION_FALLBACK }
                val field = cls.getField("privateFlags")
                field.setInt(p, field.getInt(p) or flag)
            } catch (_: Throwable) { }
        }
    }
}
