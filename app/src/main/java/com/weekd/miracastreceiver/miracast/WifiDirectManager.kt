package com.weekd.miracastreceiver.miracast

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Wi-Fi Direct / Miracast 需要的运行时权限（与 MainActivity 申请的一致）。 */
object MiracastPermissions {
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    fun granted(ctx: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Wi-Fi Direct 的"原子操作"集合，**没有自己的模式判断**。
 *
 * 只有 [MiracastController] 在 APP 模式下才会 [open]；SYSTEM 模式下这个类处于关闭状态，
 * 没有 channel、没有广播接收器、没有 discovery、没有自动 connect()，对系统服务零干扰。
 *
 * 所有回调都带 generation 校验：[close] 之后迟到的系统广播一律丢弃，
 * 不会出现"切到系统模式后又被旧回调重建了 group"。
 */
class WifiDirectManager(
    private val context: Context,
    private val deviceName: String
) {
    companion object {
        private const val ACTION_OK = -1
        private const val ACTION_TIMEOUT = -2
        private const val ACTION_TIMEOUT_MS = 8_000L
        private const val GROUP_ATTEMPTS = 3
        private const val DISCOVERY_RESTART_INTERVAL_MS = 90_000L
        private const val WFD_CONTROL_PORT = 7236
    }

    /** 老电视可能根本没有 Wi-Fi Direct，getSystemService 会返回 null。 */
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var generation = 0

    private val lastConnectAttempt = mutableMapOf<String, Long>()
    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val restartDiscoveryRunnable = Runnable { startDiscovery() }

    /** P2P 组丢了（发送端断开、系统回收等）。由 Controller 决定要不要重建。 */
    var onGroupDropped: (() -> Unit)? = null

    /** P2P 开关状态变化（用户关 Wi-Fi 等）。 */
    var onP2pEnabledChanged: ((Boolean) -> Unit)? = null

    val isOpen: Boolean get() = channel != null

    var isWfdConfigured = false
        private set

    fun isSupported(): Boolean = manager != null

    // ---------------------------------------------------------------- 生命周期

    /** 创建 channel 并注册广播接收器。失败抛 [MiracastException]，并保证不留半成品。 */
    fun open() {
        if (channel != null) return
        val p2p = manager ?: throw MiracastException(MiracastError.UNSUPPORTED)
        try {
            val ch = p2p.initialize(context, Looper.getMainLooper(), null)
                ?: throw MiracastException(MiracastError.UNSUPPORTED)
            channel = ch
            generation++
            registerReceiver(generation)
            Timber.i("Wi-Fi Direct opened")
        } catch (e: SecurityException) {
            channel = null
            throw MiracastException(MiracastError.PERMISSION_MISSING, e)
        }
    }

    /** 撤销一切：discovery、本地服务、P2P 组、接收器、channel。幂等，不抛异常。 */
    suspend fun close() {
        cancelDiscoveryRestart()
        generation++                       // 先让所有迟到的回调失效
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null

        val ch = channel
        val p2p = manager
        if (ch != null && p2p != null) {
            runCatching { action(3_000L) { p2p.stopPeerDiscovery(ch, it) } }
            runCatching { action(3_000L) { p2p.clearLocalServices(ch, it) } }
            runCatching { action(5_000L) { p2p.removeGroup(ch, it) } }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
        channel = null
        isWfdConfigured = false
        lastConnectAttempt.clear()
        Timber.i("Wi-Fi Direct closed")
    }

    /**
     * 深度清理：不依赖 [open] 过的状态。
     * 进程崩溃后 P2P 组 / 本地服务 / discovery 可能还留在系统里，而我们手里已经没有 channel 了，
     * 所以这里临时 initialize 一个 channel，把能撤的全撤掉。幂等，不抛异常。
     */
    suspend fun purge() {
        if (channel != null) { close(); return }
        val p2p = manager ?: return
        try {
            val ch = p2p.initialize(context, Looper.getMainLooper(), null) ?: return
            runCatching { action(3_000L) { p2p.stopPeerDiscovery(ch, it) } }
            runCatching { action(3_000L) { p2p.clearLocalServices(ch, it) } }
            runCatching { action(5_000L) { p2p.removeGroup(ch, it) } }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
            Timber.i("Wi-Fi Direct purged")
        } catch (e: SecurityException) {
            Timber.w("purge skipped, permission missing")
        } catch (e: Exception) {
            Timber.w(e, "purge failed")
        }
    }

    // ---------------------------------------------------------------- 原子操作

    /** 注册 _miracast._tcp 本地服务。尽力而为，失败不致命。 */
    suspend fun registerLocalService(): Boolean {
        val p2p = manager ?: return false
        val ch = channel ?: return false
        return try {
            val record = mapOf("version" to "1.0", "type" to "miracast-sink", "rtsp_port" to "$WFD_CONTROL_PORT")
            val info = WifiP2pDnsSdServiceInfo.newInstance("_miracast", "_tcp", record)
            action { p2p.addLocalService(ch, info, it) } == ACTION_OK
        } catch (e: SecurityException) {
            throw MiracastException(MiracastError.PERMISSION_MISSING, e)
        }
    }

    /**
     * 建立 P2P 组（本机为 GO）。
     * @return true = 新建了组；false = 已经有本机为 GO 的组，直接复用。
     * 注意：不要和 discoverPeers 同时调用，会 BUSY（所以 discovery 在建组之后再启动）。
     */
    suspend fun createGroup(): Boolean {
        val p2p = manager ?: throw MiracastException(MiracastError.UNSUPPORTED)
        val ch = channel ?: throw MiracastException(MiracastError.UNKNOWN)
        try {
            val existing = requestGroup()
            if (existing != null) {
                if (existing.isGroupOwner) return false
                action(5_000L) { p2p.removeGroup(ch, it) }
                delay(500)
            }

            var last = ACTION_OK
            for (attempt in 1..GROUP_ATTEMPTS) {
                last = action { p2p.createGroup(ch, it) }
                if (last == ACTION_OK) {
                    Timber.i("Wi-Fi Direct group created")
                    setDeviceName(p2p, ch)
                    return true
                }
                if (last == WifiP2pManager.P2P_UNSUPPORTED) throw MiracastException(MiracastError.UNSUPPORTED)
                Timber.w("createGroup attempt $attempt failed: $last")
                delay(1_000L * attempt)
            }
            throw MiracastException(
                if (last == ACTION_TIMEOUT) MiracastError.TIMEOUT else MiracastError.GROUP_FAILED
            )
        } catch (e: SecurityException) {
            throw MiracastException(MiracastError.PERMISSION_MISSING, e)
        }
    }

    /**
     * 通过 setWFDInfo 声明自己是 Sink。需要 signature 级 CONFIGURE_WIFI_DISPLAY，
     * 普通应用基本必败——如果失败，WFD IE 不会广播，Windows 等依赖 WFD IE 的发送端找不到本机。
     * 手机发送端（OPPO/Samsung/小米）通常不依赖 WFD IE，靠 P2P 设备发现即可连接。
     */
    suspend fun setWfdInfo(): Boolean {
        val p2p = manager ?: return false
        val ch = channel ?: return false
        return try {
            val cls = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val info = cls.getDeclaredConstructor().newInstance()
            invokeReflect(info, cls, true, "setEnabled", "setWfdEnabled")
            cls.getMethod("setDeviceType", Int::class.java).invoke(info, 1) // PRIMARY_SINK
            invokeReflect(info, cls, true, "setSessionAvailable")
            cls.getMethod("setControlPort", Int::class.java).invoke(info, WFD_CONTROL_PORT)
            cls.getMethod("setMaxThroughput", Int::class.java).invoke(info, 50)

            val method = WifiP2pManager::class.java.getMethod(
                "setWFDInfo", WifiP2pManager.Channel::class.java, cls, WifiP2pManager.ActionListener::class.java
            )
            val ok = action { method.invoke(p2p, ch, info, it) } == ACTION_OK
            isWfdConfigured = ok
            ok
        } catch (e: Exception) {
            Timber.w("setWFDInfo unavailable: ${e.cause ?: e}")
            false
        }
    }

    /** 本机是不是还持有一个 GO 组（健康检查用）。 */
    suspend fun hasGroup(): Boolean = requestGroup()?.isGroupOwner == true

    /** 启动 peer discovery（建组之后调用），并定时重启保活。 */
    fun startDiscovery() {
        val p2p = manager ?: return
        val ch = channel ?: return
        try {
            p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Miracast peer discovery started")
                    scheduleDiscoveryRestart()
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Peer discovery failed: $reason")
                    scheduleDiscoveryRestart()
                }
            })
        } catch (e: SecurityException) {
            Timber.w("discoverPeers denied: $e")
        }
    }

    /** 断开当前发送端：移除组。CONNECTION_CHANGED 会触发 [onGroupDropped]，由 Controller 重建。 */
    fun disconnectSender() {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Timber.i("Miracast sender disconnected")
            override fun onFailure(reason: Int) = Timber.w("removeGroup failed: $reason")
        })
    }

    // ---------------------------------------------------------------- 内部

    private fun scheduleDiscoveryRestart() {
        discoveryHandler.removeCallbacks(restartDiscoveryRunnable)
        discoveryHandler.postDelayed(restartDiscoveryRunnable, DISCOVERY_RESTART_INTERVAL_MS)
    }

    private fun cancelDiscoveryRestart() = discoveryHandler.removeCallbacks(restartDiscoveryRunnable)

    private suspend fun requestGroup(): WifiP2pGroup? {
        val p2p = manager ?: return null
        val ch = channel ?: return null
        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
                p2p.requestGroupInfo(ch) { group -> if (cont.isActive) cont.resume(group) }
            }
        }
    }

    /** 把 ActionListener 包成挂起函数：返回 ACTION_OK / 失败 reason / ACTION_TIMEOUT。 */
    private suspend fun action(
        timeoutMs: Long = ACTION_TIMEOUT_MS,
        call: (WifiP2pManager.ActionListener) -> Unit
    ): Int = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Int> { cont ->
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(ACTION_OK) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(reason) }
            }
            try {
                call(listener)
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    } ?: ACTION_TIMEOUT

    private fun invokeReflect(obj: Any, cls: Class<*>, arg: Boolean, vararg names: String) {
        for (name in names) {
            try {
                cls.getMethod(name, Boolean::class.java).invoke(obj, arg)
                return
            } catch (_: NoSuchMethodException) {
            }
        }
        throw NoSuchMethodException("None of ${names.toList()} found in ${cls.simpleName}")
    }

    /** Android 11+ 普通应用必败，保留给旧系统 / 系统签名构建。 */
    private fun setDeviceName(p2p: WifiP2pManager, ch: WifiP2pManager.Channel) {
        try {
            val m = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java
            )
            m.invoke(p2p, ch, deviceName, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Timber.i("Device name set to: $deviceName")
                override fun onFailure(reason: Int) = Timber.w("Failed to set device name: $reason")
            })
        } catch (e: Exception) {
            Timber.d("Could not set device name (requires system permissions)")
        }
    }

    private fun registerReceiver(gen: Int) {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (gen != generation) return          // close() 之后迟到的广播
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) cancelDiscoveryRestart()
                        onP2pEnabledChanged?.invoke(enabled)
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> handlePeersChanged(gen)

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val p2p = manager ?: return
                        val ch = channel ?: return
                        p2p.requestConnectionInfo(ch) { info ->
                            if (gen != generation) return@requestConnectionInfo
                            if (info.groupFormed) cancelDiscoveryRestart() else onGroupDropped?.invoke()
                        }
                    }
                }
            }
        }
        // 必须 RECEIVER_EXPORTED：P2P 广播由系统进程发出；走 ContextCompat 兼容 API 21+。
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    /**
     * 本机是 GO 时，OPPO / Samsung 的系统投屏会发 PBC provision-discovery 并等 GO 回应 connect()，
     * 不回应对方会超时。所以对 WFD source 设备主动 connect()（2 秒冷却）。
     *
     * 只对 WFD source 发起连接（deviceType PRIMARY_SOURCE=0 或 SECONDARY_SOURCE=2）。
     * 没有 WFD info 或者 deviceType 是 sink（1/3）的设备一律跳过，避免连到邻居电视等非发送端设备。
     */
    private fun handlePeersChanged(gen: Int) {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.requestPeers(ch) { peers: WifiP2pDeviceList ->
            if (gen != generation) return@requestPeers
            val now = System.currentTimeMillis()
            peers.deviceList.forEach { device ->
                if (device.status == WifiP2pDevice.CONNECTED) return@forEach
                if (!isWfdSource(device)) return@forEach
                val last = lastConnectAttempt[device.deviceAddress] ?: 0L
                if (now - last < 2_000L) return@forEach
                lastConnectAttempt[device.deviceAddress] = now
                Timber.i("connect() to WFD source ${device.deviceName} (${device.deviceAddress})")
                val config = WifiP2pConfig().apply {
                    deviceAddress = device.deviceAddress
                    wps.setup = WpsInfo.PBC
                }
                try {
                    p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = Timber.i("connect() sent for ${device.deviceName}")
                        override fun onFailure(reason: Int) {
                            Timber.w("connect() failed for ${device.deviceName}: $reason")
                            lastConnectAttempt.remove(device.deviceAddress)
                        }
                    })
                } catch (e: SecurityException) {
                    Timber.w("connect() denied: $e")
                }
            }
        }
    }

    /**
     * 判断设备是否为 WFD source（发送端）。
     * WFD deviceType: 0=PRIMARY_SOURCE, 1=PRIMARY_SINK, 2=SECONDARY_SOURCE, 3=SECONDARY_SINK
     * 没有 WFD info 或者 isEnabled=false → 不是 Miracast 设备，跳过。
     * sink（1/3）→ 是电视/显示器，不是投屏发送端，跳过。
     */
    private fun isWfdSource(device: WifiP2pDevice): Boolean {
        return try {
            val wfdInfo = device.wfdInfo ?: return false
            if (!wfdInfo.isWfdEnabled) return false
            // deviceType 0/2 = source；1/3 = sink
            val deviceType = wfdInfo.deviceType
            deviceType == 0 || deviceType == 2
        } catch (e: Exception) {
            false
        }
    }
}
