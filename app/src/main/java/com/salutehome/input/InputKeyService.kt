package com.salutehome.input

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.salutehome.media.SessionTokens
import com.salutehome.volume.TvVolume
import com.salutehome.volume.VolumeKeys

/**
 * The remote's Input key opens CVTE's source panel, from any app. CVTE remotes send KEYCODE_TV_INPUT
 * (scan code 79), which the firmware only ever answered with a broadcast to the stock launcher; this
 * service catches it instead. A TV without that panel keeps its own Input key.
 *
 * The remote's volume keys are the home's too (TvVolume): the volume changes here, with no flags, and the
 * home's own pill shows it instead of the TV's pop-up. Every other key passes through untouched.
 *
 * It also sees every notification posted, which is how the home screen learns what's playing on this TV:
 * a music app's notification carries its media session's token (see SessionTokens).
 */
class InputKeyService : AccessibilityService() {
    private var volume: TvVolume? = null

    /** Whether this TV has CVTE's source panel; asked once, when the service connects. */
    private var hasSourcePanel = false

    /** The app in front, from the window events; read by the volume's thread. */
    @Volatile
    private var front = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Watch for the service being switched off again while this process lives.
        ServiceGuard.start(this)
        hasSourcePanel = packageManager.resolveActivity(Intent(SHOW_INPUT), 0) != null
        volume = TvVolume(this, homeInFront = { front == packageName })
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val v = volume
        if (v != null && VolumeKeys.isVolumeKey(event.keyCode)) return v.onKey(event)
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_TV_INPUT && code != KeyEvent.KEYCODE_TV) return false
        if (!hasSourcePanel) return false
        if (event.action == KeyEvent.ACTION_UP) openSourcePanel()
        return true
    }

    private fun openSourcePanel() {
        try {
            startActivity(Intent(SHOW_INPUT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (ignored: ActivityNotFoundException) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The system's own panels (a volume slider, say) don't change who's in front.
                if (pkg != SYSTEM_UI) front = pkg
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val notification = event.parcelableData as? Notification ?: return
                SessionTokens.onNotification(this, pkg, notification)
            }
            // Only those two are asked for (input_key_service.xml).
            else -> Unit
        }
    }

    override fun onDestroy() {
        volume?.release()
        volume = null
        SessionTokens.release()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    companion object {
        /** CVTE's Source panel (HDMI 1, HDMI 2, AV...). */
        const val SHOW_INPUT = "com.cvte.intent.SHOW_INPUT"
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
