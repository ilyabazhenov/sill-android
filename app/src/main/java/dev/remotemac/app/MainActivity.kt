package dev.remotemac.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.remotemac.app.stream.HidKeys
import dev.remotemac.app.stream.Phase
import dev.remotemac.app.ui.AppRoot
import dev.remotemac.protocol.PairLink

class MainActivity : ComponentActivity() {
    private val controller get() = (application as App).controller

    /** A `sill://pair` link waiting for the person's confirmation. */
    val pendingLink = mutableStateOf<PairLink?>(null)
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
    }

    override fun onStart() {
        super.onStart()
        controller.discovery.start()
        if (wentBackground) controller.cameForward()
        wentBackground = false
    }

    override fun onStop() {
        super.onStop()
        wentBackground = true
        controller.discovery.stop()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * A hardware keyboard while streaming: every key goes to the Mac as itself (its HID usage with
     * the modifiers held), so ⌘C, arrows and Esc work as on the Mac. The software keyboard's text
     * arrives through the text field instead.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val hardware = event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && event.device?.isVirtual == false
        if (hardware && controller.phase.value == Phase.CONNECTED && !textEntryActive) {
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
