package io.github.odlaver.tepi

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

// Window above the status bar; hears only bank app launches
class Access : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        val p = Prefs(this)
        val wasPaused = p.bool(PAUSED, false)
        p.set(PAUSED, false)
        p.set(PAUSED_PKG, "")
        p.set(PAUSED_AT, 0L)
        // Events from bank apps only, window changes only
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = BANKS
        }
        Listener.instance?.rehost()
        if (wasPaused) {
            Toast.makeText(this, getString(R.string.bank_restored), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in BANKS) stepAside(pkg)
    }

    // Banks refuse to run beside accessibility
    fun stepAside(pkg: String?) {
        val p = Prefs(this)
        p.set(PAUSED, true)
        p.set(PAUSED_PKG, pkg ?: "")
        p.set(PAUSED_AT, android.os.SystemClock.uptimeMillis())
        val name = pkg?.let { try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { null } }
        Toast.makeText(this, if (name != null) getString(R.string.bank_stepped, name) else getString(R.string.bank_stepped_any), Toast.LENGTH_LONG).show()
        Listener.instance?.onBankModeStarted(pkg)
        disableSelf()
    }

    override fun onInterrupt() = Unit

    // Lock the screen, without the side button.
    //
    // This is the one thing on Home that genuinely cannot be done any other
    // way. An app may not lock the screen: the only two routes Android offers
    // are a device admin, which is a heavyweight enrolment that also hands the
    // app the power to wipe the phone, and this — the accessibility service's
    // own global action, which exists from Android 9 and does exactly one
    // thing. Tepi already runs an accessibility service for the window above
    // the status bar, so the second route costs nothing extra and asks for
    // nothing the user has not already granted.
    //
    // False when the service is not running, so the tile can say so rather
    // than appear to do nothing.
    fun lockScreen(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 28) return false
        return try { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) } catch (_: Throwable) { false }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        gone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone()
        super.onDestroy()
    }

    private fun gone() {
        if (instance !== this) return
        instance = null
        Listener.instance?.rehost()
    }

    companion object {
        var instance: Access? = null
            private set

        const val PAUSED = "bank.paused"
        const val PAUSED_PKG = "bank.paused_pkg"
        const val PAUSED_AT = "bank.paused_at"

        // Indonesian banking apps known to block accessibility
        val BANKS = arrayOf(
            "com.bca", "com.bcadigital.blu", "id.bni.wondr", "src.com.bni", "id.co.bri.brimo",
            "id.bmri.livin", "com.jago.digitalBanking", "id.co.cimbniaga.mobile.android", "com.btpn.dc",
        )

        fun paused(c: Context) = instance == null && Prefs(c).bool(PAUSED, false)

        // Tepi's own switch when the system offers it
        fun openSettings(c: Context) {
            val me = ComponentName(c, Access::class.java).flattenToString()
            val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me)
            for (i in listOf(direct, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
            }
        }
    }
}
