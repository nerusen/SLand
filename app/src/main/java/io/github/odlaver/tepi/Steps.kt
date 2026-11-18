package io.github.odlaver.tepi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.roundToInt

// Walking, as the phone can actually tell it.
//
// Two sources, and they answer different questions.
//
// The step counter is a hardware sensor. It counts every step since the phone
// booted, in a low-power core that keeps counting while the screen is off and
// while Tepi is not running, and it costs effectively no battery to read. That
// is what makes a day's total possible at all: nothing an app can do with GPS
// will tell it how far someone walked before the app was opened. The distance
// is the count times a stride, and the stride comes from the user's height,
// because a stride is about 0.415 of it for women and 0.413 for men — close
// enough that one factor serves both, and far closer than a fixed 75 cm is to
// anybody in particular.
//
// GPS answers where the phone is, for the dot on the map, and it corrects the
// distance while the tab is open: a run of fixes with good accuracy, added up
// great-circle, is the true path rather than an estimate from a stride. It is
// only asked for while the map is actually being looked at. A location
// listener left running is the single most expensive thing an app can do to a
// battery, and a map nobody is looking at is not worth it.
//
// Nothing here is a fitness tracker and nothing here claims to measure body
// fat. A phone cannot: body composition needs impedance through the body,
// which is a bathroom scale, not a pocket. What it can do honestly is count
// steps, turn them into a distance, add up the minutes that had steps in them,
// and multiply by a published constant for the energy walking costs. Those
// four are what is shown.
//
// Every location call below is guarded by canLocate() at the one place that
// turns them on, so the permission is checked rather than assumed; the
// annotation is there because the check is a function call away from the use
// and the tool cannot follow it.
@android.annotation.SuppressLint("MissingPermission")
class Steps(private val context: Context, private val prefs: Prefs, private val changed: () -> Unit) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    // Today's count, and the day it belongs to
    var today = 0
        private set

    // Minutes that had any steps in them, which is the closest a step counter
    // can get to time spent moving. Counting from the first step to the last
    // would call a walk to the shop and back a four-hour activity.
    private var activeMin = 0
    private var lastStepMin = -1L

    // Where the phone is, when it is being asked
    var here: Location? = null
        private set

    // Distance measured from the fixes themselves, in metres, while the map is
    // open. Added to nothing: it is reported beside the step estimate rather
    // than instead of it, because it only covers the part of the day the tab
    // was up.
    private var tracked = 0.0
    private var lastFix: Location? = null
    private var locating = false

    private val counter = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val raw = e.values.firstOrNull()?.toLong() ?: return
            note(raw)
        }
        override fun onAccuracyChanged(s: Sensor?, acc: Int) = Unit
    }

    private val fixes = LocationListener { loc -> fix(loc) }

    // A raw cumulative reading. Everything that can go wrong with a counter
    // goes wrong here rather than in the arithmetic above it: the phone
    // rebooted and the count restarted from zero, the day rolled over, or this
    // is the very first reading Tepi has ever seen.
    private fun note(raw: Long) {
        val day = dayKey()
        val storedDay = prefs.int(DAY, 0)
        val base = prefs.sp.getLong(BASE, -1L)
        if (storedDay != day || base < 0L || raw < base) {
            // New day, first ever reading, or a reboot took the counter back
            // to zero. In all three the count starts again from here, which
            // loses the steps taken between midnight and the first reading of
            // the morning — there is no way to recover those, because the only
            // record of them is a counter that has already been reset.
            prefs.set(DAY, day)
            prefs.sp.edit().putLong(BASE, raw).apply()
            today = 0
            activeMin = 0
            changed()
            return
        }
        val now = (raw - base).toInt().coerceAtLeast(0)
        if (now == today) return
        today = now
        // One active minute per minute that saw a step
        val minute = System.currentTimeMillis() / 60_000L
        if (minute != lastStepMin) {
            lastStepMin = minute
            activeMin++
            prefs.set(ACTIVE, activeMin)
        }
        changed()
    }

    private fun fix(loc: Location) {
        // A fix nobody can trust is worse than no fix: a 500-metre jump from
        // a cell-tower estimate would add half a kilometre to the day
        if (loc.hasAccuracy() && loc.accuracy > ACCURACY_M) return
        val prev = lastFix
        here = loc
        lastFix = loc
        if (prev != null) {
            val d = prev.distanceTo(loc)
            // Under the noise floor it is the phone sitting still and the fix
            // wandering; over the ceiling it is a car, a train, or a fix that
            // teleported, and none of those is walking
            if (d > NOISE_M && d < JUMP_M) tracked += d
        }
        changed()
    }

    fun start() {
        if (started) return
        started = true
        activeMin = prefs.int(ACTIVE, 0)
        if (!canCount()) return
        val sm = sensors ?: return
        val s = try { sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } catch (_: Exception) { null } ?: return
        // The slowest rate there is. The counter is a running total, so a
        // reading every few seconds is as good as a reading per step and the
        // sensor hub batches them either way.
        try { sm.registerListener(counter, s, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US) } catch (_: Exception) { }
    }

    fun stop() {
        if (!started) return
        started = false
        try { sensors?.unregisterListener(counter) } catch (_: Exception) { }
        locate(false)
    }

    // The map is open, or it is not. Fixes are only asked for in the first
    // case: this is the one switch in Tepi that genuinely costs battery, so it
    // is tied to the panel being on screen rather than to the feature being
    // enabled.
    fun locate(on: Boolean) {
        if (on == locating) return
        locating = on
        if (!on) {
            try { locations?.removeUpdates(fixes) } catch (_: Exception) { }
            lastFix = null
            return
        }
        if (!canLocate()) { locating = false; return }
        val lm = locations
        if (lm == null) { locating = false; return }
        // Last known position first, so the map has somewhere to be while the
        // first real fix is still being worked out
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val last = lm.getLastKnownLocation(p) ?: continue
                if (here == null || last.time > (here?.time ?: 0L)) here = last
            }
        } catch (_: Exception) { }
        // The fused provider where there is one: it is the system's own blend
        // of GPS, Wi-Fi and the sensors, and it reaches a usable fix indoors
        // where bare GPS does not. Both of the older providers otherwise,
        // because either on its own leaves a gap — GPS has nothing indoors and
        // the network provider is never accurate enough to trust a distance to.
        try {
            if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, FIX_MS, FIX_M, fixes, Looper.getMainLooper())
            } else {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_MS, FIX_M, fixes, Looper.getMainLooper())
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, FIX_MS, FIX_M, fixes, Looper.getMainLooper())
            }
        } catch (_: Exception) { locating = false }
        changed()
    }

    val tracking get() = locating

    fun canCount() = Build.VERSION.SDK_INT < 29 ||
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun hasCounter() = try {
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    } catch (_: Exception) { false }

    fun canLocate() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // One pace, in metres, from the user's own height. The published factor
    // for walking is a touch over four tenths of standing height; it is the
    // same figure pedometers have used for decades and it is a good deal
    // closer than any single constant.
    fun stride(): Float {
        val cm = prefs.heightCm.coerceIn(Prefs.HEIGHT_MIN, Prefs.HEIGHT_MAX)
        return cm / 100f * STRIDE
    }

    // Today's distance in metres. The tracked path wins where there is one and
    // it is longer than the estimate: inside a single walk the GPS trace is
    // the truth, and it can only ever be short of the day's total because it
    // starts when the tab was opened.
    fun metres(): Float {
        val est = today * stride()
        return max(est, tracked.toFloat())
    }

    fun km() = metres() / 1000f

    // Minutes spent moving, from the minutes that had steps in them
    fun movingMin() = activeMin

    // Energy, from the standard walking figure: about 0.75 kcal per kilogram
    // per kilometre. It scales with weight and distance and not with speed,
    // which is very nearly true for walking and is why this one number is
    // usable where a heart-rate model would not be.
    fun kcal(): Int {
        val kg = prefs.weightKg.coerceIn(Prefs.WEIGHT_MIN, Prefs.WEIGHT_MAX)
        return (kg * km() * KCAL_PER_KG_KM).roundToInt()
    }

    // Day number since the epoch, in local time, so a day rolls at the user's
    // own midnight rather than at UTC's
    private fun dayKey(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    // The counter only reports when it moves, so a phone that has been still
    // since the service started has nothing to say. This pokes it with the
    // reading it already has, which is what makes the figure appear when the
    // tab is first opened rather than after the next step.
    fun poke() {
        if (!started || !canCount()) return
        val sm = sensors ?: return
        val sensor = try { sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) } catch (_: Exception) { null } ?: return
        // A one-shot registration at the fastest rate delivers the current
        // total almost immediately, and unregisters itself as soon as it
        // arrives. Cheaper than keeping a fast listener open, and the only way
        // to ask a cumulative sensor for its value on demand.
        //
        // The timeout is the other half of it: a sensor that never fires would
        // otherwise leave a fast listener registered for the life of the
        // service, which is exactly the battery cost this is avoiding.
        val once = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                e.values.firstOrNull()?.let { note(it.toLong()) }
                try { sm.unregisterListener(this) } catch (_: Exception) { }
            }
            override fun onAccuracyChanged(sensor: Sensor?, acc: Int) = Unit
        }
        try { sm.registerListener(once, sensor, SensorManager.SENSOR_DELAY_FASTEST) } catch (_: Exception) { }
        main.postDelayed({ try { sm.unregisterListener(once) } catch (_: Exception) { } }, POKE_MS)
    }

    companion object {
        private const val DAY = "steps.day"
        private const val BASE = "steps.base"
        private const val ACTIVE = "steps.active"

        // Stride as a share of standing height, for walking
        private const val STRIDE = 0.414f
        // Walking costs about this per kilogram of body weight per kilometre
        private const val KCAL_PER_KG_KM = 0.75f

        // Batched up to a minute: the hub holds the readings and delivers them
        // together, which is the difference between a sensor that costs
        // nothing and one that wakes the main core every few seconds
        private const val BATCH_US = 60_000_000

        // How long the one-shot reading in poke() is given before its
        // listener is taken down regardless
        private const val POKE_MS = 2000L

        // Fixes: no faster than this, and no closer together than this
        private const val FIX_MS = 3000L
        private const val FIX_M = 5f

        // A fix worse than this is a cell-tower guess, not a position
        private const val ACCURACY_M = 35f
        // Movement under this is the fix wandering while the phone sits still
        private const val NOISE_M = 3f
        // And over this it is a vehicle or a teleport, not a walk
        private const val JUMP_M = 120f

        fun permission(): Array<String> {
            val out = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 29) out.add(Manifest.permission.ACTIVITY_RECOGNITION)
            return out.toTypedArray()
        }
    }
}
