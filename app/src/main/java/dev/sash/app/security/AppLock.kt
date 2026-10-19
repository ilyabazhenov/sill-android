package dev.sash.app.security

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A paired device controls the Mac completely (keyboard and mouse, password fields included), so
 * the app asks for the owner's fingerprint, face or screen lock when it opens, and again after a
 * minute in the background. Only on a device that has a screen lock: without one there is nothing
 * to ask for.
 */
class AppLock(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** The device has a PIN, pattern, password or biometrics to unlock with. */
    val available: Boolean get() = keyguard?.isDeviceSecure == true

    private val _enabled = MutableStateFlow(prefs.getBoolean("appLock", true))
    val enabled: StateFlow<Boolean> = _enabled

    private val _locked = MutableStateFlow(_enabled.value && available)
    val locked: StateFlow<Boolean> = _locked

    private var backgroundAt = 0L

    fun wentBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun cameForward() {
        if (_enabled.value && available && backgroundAt != 0L && SystemClock.elapsedRealtime() - backgroundAt > GRACE_MS) {
            _locked.value = true
        }
    }

    fun unlocked() {
        _locked.value = false
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs.edit().putBoolean("appLock", on).apply()
        if (!on) _locked.value = false
    }

    companion object {
        const val GRACE_MS = 60_000L
    }
}
