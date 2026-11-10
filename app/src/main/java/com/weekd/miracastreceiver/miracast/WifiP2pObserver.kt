package com.weekd.miracastreceiver.miracast

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import timber.log.Timber

object MiracastPermissions {
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun granted(ctx: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}

/** Read-only channel. Closing never stops discovery, disconnects, or removes a group. */
class WifiP2pObserver(context: Context) {
    private val ctx = context.applicationContext
    private val manager by lazy { ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var generation = 0
    var onGroupFormed: ((WifiP2pInfo) -> Unit)? = null
    var onGroupDropped: (() -> Unit)? = null
    var onUnavailable: (() -> Unit)? = null
    val isOpen: Boolean get() = channel != null
    fun isSupported(): Boolean = manager != null

    fun start() {
        if (isOpen) return
        val p2p = manager ?: throw MiracastException(MiracastError.UNSUPPORTED)
        val gen = ++generation
        try {
            channel = p2p.initialize(ctx, Looper.getMainLooper()) {
                if (gen == generation) { stop(); onUnavailable?.invoke() }
            } ?: throw MiracastException(MiracastError.UNSUPPORTED)
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (gen != generation) return
                    when (intent.action) {
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refresh()
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                                WifiP2pManager.WIFI_P2P_STATE_ENABLED) refresh()
                            else onGroupDropped?.invoke()
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(ctx, r, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_EXPORTED)
            receiver = r
            refresh()
            Timber.i("Miracast: observing system P2P; no active P2P operations")
        } catch (e: Exception) { stop(); throw e }
    }

    fun refresh() {
        val p2p = manager ?: return
        val ch = channel ?: return
        val gen = generation
        try {
            p2p.requestConnectionInfo(ch) { info ->
                if (gen != generation || channel !== ch) return@requestConnectionInfo
                if (info.groupFormed) onGroupFormed?.invoke(info) else onGroupDropped?.invoke()
            }
        } catch (e: SecurityException) {
            Timber.w(e, "Miracast: P2P observation permission unavailable")
            onUnavailable?.invoke()
        }
    }

    fun stop(): Boolean {
        generation++
        val r = receiver
        receiver = null
        val unregisterOk = r == null || runCatching { ctx.unregisterReceiver(r) }.isSuccess
        val ch = channel
        channel = null
        val closeOk = ch == null || Build.VERSION.SDK_INT < 27 || runCatching { ch.close() }.isSuccess
        return unregisterOk && closeOk
    }
}
