package io.github.odlaver.tepi

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.telephony.TelephonyManager

// Mobile data and the lock screen, as the two tiles Home did not have.
//
// Neither is a switch an app may simply flip, and they are refused for
// different reasons:
//
//   Mobile data   setMobileDataEnabled was removed from the public API in
//                 Android 5 and the replacement needs MODIFY_PHONE_STATE,
//                 which is signature-level: only the system and the carrier
//                 can hold it. Reflection against the hidden method is tried
//                 first because a few vendor builds still allow it, and the
//                 fall-back is the system's own data page, which is one tap
//                 from the switch. Reading the state, on the other hand, is
//                 allowed and reliable, so the tile is always honest about
//                 whether data is on even where it cannot change it.
//
//   Lock screen   no permission reaches it at all, but the accessibility
//                 service has a global action for exactly this, and Tepi
//                 already runs one. So this works outright when the service
//                 is on, and says why when it is not.
//
// The point of the pair is the hardware: a side button has a rated number of
// presses in it and locking the phone is most of them. A tile that locks the
// screen is a button that does not wear out.
object Quick {
    // Whether mobile data is switched on.
    //
    // isDataEnabled is public, but from Android 13 it is guarded by
    // READ_PHONE_STATE — a permission Tepi does not ask for, because reading
    // the phone state is a far broader grant than one tile is worth. So the
    // getter is tried, the SecurityException it throws there is caught, and
    // the fall-back below answers instead. The tile is right either way; the
    // difference is only whether it knows data is on with no signal.
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("MissingPermission")
    fun dataOn(context: Context): Boolean {
        val t = context.getSystemService(TelephonyManager::class.java)
        try {
            if (t != null) return t.isDataEnabled
        } catch (_: Throwable) { }
        // Anything that refuses the getter: fall back to asking whether a
        // cellular network is actually up. Not the same question — data can be
        // on with no signal — but it is the closest supported answer, and it
        // is never wrong in the direction that matters, which is claiming data
        // is on when nothing is connected.
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork ?: return false
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } catch (_: Exception) { false }
    }

    // Whether the phone has a cellular radio at all. A tablet with no SIM
    // slot should not be offered a mobile data tile.
    fun hasData(context: Context): Boolean = try {
        val t = context.getSystemService(TelephonyManager::class.java)
        t != null && t.phoneType != TelephonyManager.PHONE_TYPE_NONE
    } catch (_: Exception) { false }

    // Turn mobile data on or off.
    //
    // The hidden setter first, which works on a minority of builds and is
    // exactly what the user asked for when it does: one tap, data on, second
    // tap, data off, no page in the way. True when it was actually flipped;
    // false means the caller should open the page instead, and the caller is
    // expected to, because a tile that reports success it did not have is
    // worse than one that admits it needs a tap more.
    fun setData(context: Context, on: Boolean): Boolean {
        val t = context.getSystemService(TelephonyManager::class.java) ?: return false
        // The public setter exists from Android 12 but is still guarded by
        // MODIFY_PHONE_STATE, so it throws for an ordinary app rather than
        // failing quietly; it is tried anyway because a carrier-privileged
        // install would be allowed it
        try {
            TelephonyManager::class.java.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
                .invoke(t, on)
            // Trust nothing: read it back rather than assume the call took
            return dataOn(context) == on
        } catch (_: Throwable) { }
        return false
    }

    // The system's own mobile network page, for the hold and for the fall-back
    fun networkSettings(): Intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)

    // Lock the screen through the accessibility service. False when there is
    // no service to do it with, which is what the tile says instead.
    fun lock(): Boolean = Access.instance?.lockScreen() == true

    // Whether locking is possible at all right now
    fun canLock() = android.os.Build.VERSION.SDK_INT >= 28 && Access.instance != null
}
