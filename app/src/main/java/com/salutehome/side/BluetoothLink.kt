package com.salutehome.side

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * What the top bar's Bluetooth pill shows: whether Bluetooth is on, and the name of the speaker or headphones
 * the TV plays to (A2DP), if any. The remote is a Bluetooth device too, but not an audio one, so it never
 * shows. Device names need BLUETOOTH_CONNECT, granted over adb at install; without it the pill shows only
 * on or off.
 */
@SuppressLint("MissingPermission") // Granted over adb; every call that needs it catches SecurityException.
class BluetoothLink(private val context: Context, private val onChange: (State) -> Unit) {
    data class State(val on: Boolean, val device: String?)

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var a2dp: BluetoothProfile? = null
    private var listening = false

    // getProfileProxy answers later: a proxy that arrives once stopped is closed at once.
    private var started = false

    /** False on a TV without Bluetooth: the pill stays hidden. */
    val present: Boolean get() = adapter != null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    private val profiles = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val old = a2dp
            if (old != null && old !== proxy) adapter?.closeProfileProxy(BluetoothProfile.A2DP, old)
            if (!started) {
                adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                a2dp = null
                return
            }
            a2dp = proxy
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            a2dp = null
            if (started) refresh()
        }
    }

    fun start() {
        val a = adapter ?: return
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction("android.bluetooth.device.action.ALIAS_CHANGED") // Android 11+: renamed in Settings
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        listening = true
        try {
            a.getProfileProxy(context, profiles, BluetoothProfile.A2DP)
        } catch (e: SecurityException) {
            // Without the permission there's no device name to show; on and off still work.
        }
        refresh()
    }

    fun stop() {
        started = false
        if (listening) context.unregisterReceiver(receiver)
        listening = false
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
    }

    fun refresh() {
        val a = adapter ?: return
        val on = a.isEnabled
        val device = if (!on) null else try {
            a2dp?.connectedDevices?.firstOrNull()?.let { d ->
                (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name
            }
        } catch (e: SecurityException) {
            null
        }
        onChange(State(on, device?.takeIf { it.isNotBlank() }))
    }
}
