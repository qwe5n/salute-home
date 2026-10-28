package com.salutehome.media

import android.content.ComponentName
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService

/**
 * Notification access, where the TV allows it, lets the home screen read every app's media session; this
 * service holds it and hands the sessions to a SessionFollower. Low-RAM Android (this TV: ro.config.low_ram
 * is true) has no notification access at all, so here it never starts and SessionTokens does the job.
 */
class NowPlayingListener : NotificationListenerService() {

    private var follower: SessionFollower? = null
    private var sessions: MediaSessionManager? = null
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> follower?.follow(list) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        active = this
        tellVpn()
        if (follower != null) return
        val f = SessionFollower(this)
        follower = f
        f.start()
        val component = ComponentName(this, NowPlayingListener::class.java)
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component, f.handler)
            sessions = msm
            f.follow(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Notification access was taken away while we started: nothing to follow.
        }
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null && sbn.packageName in vpnPackages(this)) tellVpn()
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null && sbn.packageName in vpnPackages(this)) tellVpn()
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
        stop()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: RuntimeException) {
            // Already gone with the binding.
        }
        sessions = null
        follower?.stop()
        follower = null
    }

    companion object {
        @Volatile
        private var active: NowPlayingListener? = null
        private var vpnApps: Set<String>? = null
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        /** Told (main thread) when a VPN app's notification comes or goes. */
        val vpnListeners = mutableListOf<() -> Unit>()

        private fun tellVpn() {
            main.post { for (l in vpnListeners.toList()) l() }
        }

        /** Apps that can run a VPN (they declare a VpnService). */
        fun vpnPackages(context: android.content.Context): Set<String> = vpnApps ?: try {
            context.packageManager.queryIntentServices(android.content.Intent("android.net.VpnService"), 0)
                .map { it.serviceInfo.packageName }.toSet()
        } catch (e: RuntimeException) {
            emptySet()
        }.also { vpnApps = it }

        /** The VPN apps running now, by their notification (a VPN app keeps one while it's on). */
        fun runningVpnApps(context: android.content.Context): List<String> {
            val l = active ?: return emptyList()
            val vpn = vpnPackages(context)
            return try {
                l.activeNotifications.orEmpty().map { it.packageName }.filter { it in vpn }.distinct()
            } catch (e: RuntimeException) {
                emptyList()
            }
        }
    }
}
