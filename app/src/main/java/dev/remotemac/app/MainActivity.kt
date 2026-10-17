package dev.remotemac.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.remotemac.app.stream.HidKeys
import dev.remotemac.app.stream.Phase
import dev.remotemac.app.ui.AppRoot
import dev.remotemac.protocol.PairLink
import dev.remotemac.protocol.RelayLink

class MainActivity : ComponentActivity() {
    private val controller get() = (application as App).controller
    val lock get() = (application as App).lock

    /** A `sill://pair` link waiting for the person's confirmation. */
    val pendingLink = mutableStateOf<PairLink?>(null)
    /** A `remotemac://relay` link from outside the app: any app or page can open one, so it waits too. */
    val pendingRelay = mutableStateOf<RelayLink?>(null)
    private var wentBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { AppRoot(controller, this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        PairLink.parse(data)?.let { pendingLink.value = it }
        RelayLink.parse(data)?.let { pendingRelay.value = it }
    }

    override fun onStart() {
        super.onStart()
        controller.discovery.start()
        if (wentBackground) {
            lock.cameForward()
            controller.cameForward()
        }
        wentBackground = false
    }

    override fun onStop() {
        super.onStop()
        wentBackground = true
        lock.wentBackground()
        controller.discovery.stop()
    }

    private var prompting = false

    /** The owner's fingerprint, face or screen lock; the lock screen asks again on failure. */
    fun promptUnlock() {
        if (prompting || !lock.locked.value) return
        prompting = true
        val b = BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.unlock_prompt_title))
            .setSubtitle(getString(R.string.unlock_prompt_subtitle))
        // Biometrics or the screen lock, whichever the owner uses.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            b.setDeviceCredentialAllowed(true)
        }
        b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                lock.unlocked()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
            }
        })
    }

    /** While streaming: the screen stays on and the system bars give the Mac's picture their room. */
    fun streaming(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) bars.hide(WindowInsetsCompat.Type.systemBars()) else bars.show(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * A hardware keyboard while streaming: every key goes to the Mac as itself (its HID usage with
     * the modifiers held), so ⌘C, arrows and Esc work as on the Mac. The software keyboard's text
     * arrives through the text field instead.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val hardware = event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && event.device?.isVirtual == false
        if (hardware && controller.phase.value == Phase.CONNECTED && !textEntryActive && !lock.locked.value) {
            val usage = HidKeys.usage(event.keyCode)
            if (usage != null && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
                val down = event.action == KeyEvent.ACTION_DOWN
                if (!(down && event.repeatCount > 0 && HidKeys.isModifier(usage))) {
                    controller.key(usage, down, HidKeys.modifiers(event.metaState))
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** True while one of the app's own text fields (search, code entry) has the keyboard. */
    @Volatile var textEntryActive = false
}
