package io.github.odlaver.tepi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

// What the two Home tiles show
class RadioState(
    val on: Boolean,
    // Connection name, or null when on but connected to nothing
    val name: String?,
    // Earbud battery percent, or null when the device does not report one
    val battery: Int? = null,
)

// Wi-Fi and Bluetooth, watched for the Home tiles.
//
// Both names need a runtime permission the app does not need otherwise, and
// both are optional: without ACCESS_FINE_LOCATION Android hides the Wi-Fi
// SSID, and without BLUETOOTH_CONNECT the bonded device list is empty. In
// either case the tile falls back to the plain label, which is the same thing
// it shows when nothing is connected.
@android.annotation.SuppressLint("MissingPermission")
class Radios(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val conn = context.getSystemService(ConnectivityManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val bt = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bt?.adapter
    private var started = false

    // Levels straight from the system's own battery broadcast, by address
    private val levels = HashMap<String, Int>()

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    val canName get() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val canBt get() = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    // Read once per change, not per frame: Home redraws every frame while it
    // is open, and the Bluetooth side walks the bonded list through
    // reflection. A stale copy is re-read after a few seconds regardless, for
    // the levels and names that change without a broadcast.
    private var wifiNow = RadioState(false, null)
    private var btNow = RadioState(false, null)
    private var readAt = 0L

    private fun read() {
        wifiNow = readWifi()
        btNow = readBluetooth()
        readAt = android.os.SystemClock.uptimeMillis()
    }

    fun wifi(): RadioState { fresh(); return wifiNow }
    fun bluetooth(): RadioState { fresh(); return btNow }

    private fun fresh() {
        if (android.os.SystemClock.uptimeMillis() - readAt > STALE_MS) read()
    }

    // Debounced: a single toggle can fan out into several broadcasts
    private val announce = Runnable { read(); changed() }

    private fun poke() {
        main.removeCallbacks(announce)
        main.postDelayed(announce, 150)
    }

    private val watch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_BATTERY_LEVEL_CHANGED) {
                val dev = device(i)
                val lv = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                val addr = dev?.address
                if (addr != null) {
                    if (lv in 0..100) levels[addr] = lv else levels.remove(addr)
                }
            }
            poke()
        }
    }

    @Suppress("DEPRECATION")
    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    // Last Wi-Fi details the system handed over. From Android 12 the SSID
    // only reaches a callback registered with FLAG_INCLUDE_LOCATION_INFO;
    // asking getNetworkCapabilities afterwards returns it redacted.
    @Volatile private var wifiInfo: WifiInfo? = null

    private val nets: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = seen(caps)
            override fun onLost(network: Network) { wifiInfo = null; poke() }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = seen(caps)
            override fun onLost(network: Network) { wifiInfo = null; poke() }
        }

    private fun seen(caps: NetworkCapabilities) {
        wifiInfo = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) caps.transportInfo as? WifiInfo else null
        poke()
    }

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        // Wi-Fi, Bluetooth and the headset battery are all system broadcasts
        try { context.registerReceiver(watch, f, null, main, Context.RECEIVER_EXPORTED) } catch (_: Exception) { }
        try { conn?.registerDefaultNetworkCallback(nets, main) } catch (_: Exception) { }
        read()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(announce)
        try { context.unregisterReceiver(watch) } catch (_: Exception) { }
        try { conn?.unregisterNetworkCallback(nets) } catch (_: Exception) { }
    }

    private fun readWifi(): RadioState {
        val on = try { wifi?.isWifiEnabled == true } catch (_: Exception) { false }
        if (!on) return RadioState(false, null)
        return RadioState(true, ssid())
    }

    // The SSID of the current default network, when it is Wi-Fi. Android
    // replaces it with a placeholder unless the app holds a location
    // permission, so anything that is not a real name is reported as "not
    // connected" rather than shown.
    @Suppress("DEPRECATION")
    private fun ssid(): String? {
        val cm = conn ?: return null
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        // The callback's copy carries the name; the older direct read is the
        // fallback, and still answers on most builds
        val names = listOf(wifiInfo?.ssid, try { wifi?.connectionInfo?.ssid } catch (_: Exception) { null })
        for (raw in names) {
            val name = raw?.trim()?.removeSurrounding("\"") ?: continue
            if (name.isEmpty() || name == "<unknown ssid>" || name == "0x") continue
            return name
        }
        return null
    }

    private fun readBluetooth(): RadioState {
        val a = adapter
        val on = try { a?.isEnabled == true } catch (_: Exception) { false }
        if (!on) return RadioState(false, null)
        val dev = connected(a)
        if (dev == null) return RadioState(true, audioName())
        return RadioState(true, name(dev) ?: audioName(), battery(dev))
    }

    // First bonded device with an open connection. isConnected is not in the
    // SDK, so a refusal is treated as "cannot tell" and the audio device name
    // is used instead.
    private fun connected(a: BluetoothAdapter?): BluetoothDevice? {
        if (a == null || !canBt) return null
        val bonded = try { a.bondedDevices } catch (_: Exception) { null } ?: return null
        for (d in bonded) if (isConnected(d)) return d
        return null
    }

    private fun isConnected(d: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean == true
    } catch (_: Throwable) { false }

    @Suppress("DEPRECATION")
    private fun name(d: BluetoothDevice): String? = try {
        (if (Build.VERSION.SDK_INT >= 30) d.alias ?: d.name else d.name)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    // Name of a connected Bluetooth output, which needs no permission. Used
    // when the bonded list is unavailable, so a paired speaker or a pair of
    // earbuds still names itself in the tile.
    private fun audioName(): String? {
        val devs = try { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Exception) { null } ?: return null
        for (d in devs) {
            if (d.type !in BT_OUTPUTS) continue
            val n = d.productName?.toString()?.trim()
            if (!n.isNullOrEmpty()) return n
        }
        return null
    }

    // Earbud battery, in the order the system itself prefers: the level it
    // broadcast, then the device's own reading, then the per-bud metadata a
    // true wireless set publishes. For a pair, the lower bud is the one worth
    // showing, which is what Android's own Bluetooth screen does.
    private fun battery(d: BluetoothDevice): Int? {
        if (!canBt) return null
        levels[d.address]?.let { if (it in 0..100) return it }
        reflectLevel(d)?.let { return it }
        val buds = listOf(META_LEFT_BATTERY, META_RIGHT_BATTERY).mapNotNull { metaLevel(d, it) }
        buds.minOrNull()?.let { return it }
        return metaLevel(d, META_MAIN_BATTERY)
    }

    // getBatteryLevel is not in the SDK either; absent it, there is simply no
    // percentage to show
    private fun reflectLevel(d: BluetoothDevice): Int? = try {
        val v = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(d) as? Int
        if (v != null && v in 0..100) v else null
    } catch (_: Throwable) { null }

    private fun metaLevel(d: BluetoothDevice, key: Int): Int? = try {
        val m = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
        val raw = m.invoke(d, key) as? ByteArray ?: return null
        String(raw, Charsets.UTF_8).trim().toIntOrNull()?.takeIf { it in 0..100 }
    } catch (_: Throwable) { null }

    companion object {
        // Battery of a connected Bluetooth device. Both the action and the
        // extra are system API, so they are named by string here; a system
        // that does not send them simply leaves the percentage out.
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        // BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY and friends
        private const val META_LEFT_BATTERY = 10
        private const val META_RIGHT_BATTERY = 11
        private const val META_MAIN_BATTERY = 18

        private const val STALE_MS = 5000L

        private val BT_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )

        // Tap: ask the system to turn the radio on. Neither switch can be
        // flipped by an app any more, so this opens the panel Android offers
        // for exactly that instead of failing silently.
        fun wifiToggle(): Intent = Intent(android.provider.Settings.Panel.ACTION_WIFI)

        // Turning Bluetooth on goes through the system's own yes/no prompt,
        // which needs BLUETOOTH_CONNECT; without it, or once it is already
        // on, the Bluetooth page is the place to act from
        fun btToggle(on: Boolean, allowed: Boolean): Intent =
            if (!on && allowed) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            else Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)

        // Hold: the full system page for that radio
        fun wifiSettings(): Intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
        fun btSettings(): Intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)

        // Permissions the two names need, whichever are still missing
        fun missing(context: Context): Array<String> {
            val out = ArrayList<String>()
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Android 12 ignores a fine request that comes without coarse
                out.add(Manifest.permission.ACCESS_FINE_LOCATION)
                out.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                out.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            return out.toTypedArray()
        }
    }
}
