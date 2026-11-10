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
        private const val DISCOVERY_RETRY_INTERVAL_MS = 3_000L
        private const val DISCOVERY_RETRY_INTERVAL_FAST_MS = 1_500L  // Wi-Fi 正在扫描/切换时，短暂重试
        private const val DISCOVERY_MAX_RETRIES = 5
        private const val DISCOVERY_HARD_FAIL_COUNT = 20  // 超过此次数触发 channel 重建
        private const val WFD_CONTROL_PORT = 7236
        private const val CONNECT_MAX_ATTEMPTS = 5   // 每个设备最多尝试5次 connect()
    }

    /** 老电视可能根本没有 Wi-Fi Direct，getSystemService 会返回 null。 */
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var generation = 0

    private val lastConnectAttempt = mutableMapOf<String, Long>()
    private val connectAttemptCount = mutableMapOf<String, Int>()
    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val restartDiscoveryRunnable = Runnable { startDiscovery() }
    private var discoveryRetryCount = 0
    @Volatile var autoConnectAndroid = false

    /** P2P 组丢了（发送端断开、系统回收等）。由 Controller 决定要不要重建。 */
    var onGroupDropped: (() -> Unit)? = null

    /** P2P 组刚建立成功（groupFormed 变为 true）。通知 WfdServer 立刻开始扫描。 */
    var onGroupFormed: ((android.net.wifi.p2p.WifiP2pInfo) -> Unit)? = null

    /** P2P 开关状态变化（用户关 Wi-Fi 等）。 */
    var onP2pEnabledChanged: ((Boolean) -> Unit)? = null

    /**
     * discovery 持续失败（超过 [DISCOVERY_HARD_FAIL_COUNT] 次），可能是 Wi-Fi driver 内部状态卡死。
     * Controller 收到后应执行 close()+open() 重建整个 P2P channel。
     */
    var onDiscoveryHardFail: (() -> Unit)? = null

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
    suspend fun close(): Boolean {
        cancelDiscoveryRestart()
        generation++                       // 先让所有迟到的回调失效
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null

        val ch = channel
        val p2p = manager
        var ok = true
        if (ch != null && p2p != null) {
            ok = cleanupChannel(p2p, ch)
            if (Build.VERSION.SDK_INT >= 27 && runCatching { ch.close() }.isFailure) ok = false
        } else if (isWfdConfigured || MiracastModeStore.isFrameworkIeInjected(context)) {
            ok = false
        }
        channel = null
        isWfdConfigured = false
        lastConnectAttempt.clear()
        connectAttemptCount.clear()
        discoveryRetryCount = 0
        Timber.i("Wi-Fi Direct closed")
        return ok
    }

    /** channel reinit 后重置 discovery 重试计数，使新一轮计数从 0 开始。 */
    fun resetDiscoveryRetryCount() {
        discoveryRetryCount = 0
    }

    /**
     * 深度清理：不依赖 [open] 过的状态。
     * 进程崩溃后 P2P 组 / 本地服务 / discovery 可能还留在系统里，而我们手里已经没有 channel 了，
     * 所以这里临时 initialize 一个 channel，把能撤的全撤掉。幂等，不抛异常。
     */
    suspend fun purge(): Boolean {
        if (channel != null) return close()
        val p2p = manager ?: return !MiracastModeStore.isFrameworkIeInjected(context)
        return try {
            val ch = p2p.initialize(context, Looper.getMainLooper(), null) ?: return false
            var ok = cleanupChannel(p2p, ch)
            if (Build.VERSION.SDK_INT >= 27 && runCatching { ch.close() }.isFailure) ok = false
            Timber.i("Wi-Fi Direct purged")
            ok
        } catch (e: SecurityException) {
            Timber.w("purge skipped, permission missing")
            false
        } catch (e: Exception) {
            Timber.w(e, "purge failed")
            false
        }
    }

    private suspend fun cleanupChannel(p2p: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean {
        val report = CleanupReport()
        if (isWfdConfigured || MiracastModeStore.isFrameworkIeInjected(context)) {
            report.attempt("framework WFD IE") {
                val cls = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
                val info = cls.getDeclaredConstructor().newInstance()
                invokeReflect(info, cls, false, "setEnabled", "setWfdEnabled")
                val method = WifiP2pManager::class.java.getMethod(
                    "setWFDInfo", WifiP2pManager.Channel::class.java, cls, WifiP2pManager.ActionListener::class.java
                )
                val ok = action { method.invoke(p2p, ch, info, it) } == ACTION_OK
                if (ok) MiracastModeStore.setFrameworkIeInjected(context, false)
                ok
            }
        }
        report.attempt("cancel connect") { action(3_000L) { p2p.cancelConnect(ch, it) } == ACTION_OK }
        report.attempt("discovery") { action(3_000L) { p2p.stopPeerDiscovery(ch, it) } == ACTION_OK }
        report.attempt("local services") { action(3_000L) { p2p.clearLocalServices(ch, it) } == ACTION_OK }
        report.attempt("group") {
            action(5_000L) { p2p.removeGroup(ch, it) }
            val absent = withTimeoutOrNull(3_000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    try {
                        p2p.requestGroupInfo(ch) { group -> if (cont.isActive) cont.resume(group == null) }
                    } catch (_: SecurityException) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } == true
            absent
        }
        if (!report.clean) Timber.w("P2P cleanup incomplete: ${report.failures}")
        return report.clean
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
            MiracastModeStore.setFrameworkIeInjected(context, true)
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
                    discoveryRetryCount = 0
                    Timber.i("Miracast peer discovery started")
                    scheduleDiscoveryRestart()
                }

                override fun onFailure(reason: Int) {
                    discoveryRetryCount++
                    // discovery 持续失败超过阈值：channel 可能卡死，通知 Controller 重建整个 P2P stack
                    if (discoveryRetryCount >= DISCOVERY_HARD_FAIL_COUNT) {
                        Timber.w("Peer discovery hard-failed ($discoveryRetryCount times), requesting channel reinit")
                        onDiscoveryHardFail?.invoke()
                        return
                    }
                    // reason=0 (ERROR) 通常是 Wi-Fi 正在扫描/切换信道，很快会好，用短间隔重试。
                    // 超过阈值后才用长间隔，避免持续空转。
                    val delay = when {
                        discoveryRetryCount <= DISCOVERY_MAX_RETRIES -> DISCOVERY_RETRY_INTERVAL_FAST_MS
                        discoveryRetryCount <= DISCOVERY_MAX_RETRIES * 3 -> DISCOVERY_RETRY_INTERVAL_MS
                        else -> DISCOVERY_RESTART_INTERVAL_MS
                    }
                    Timber.w("Peer discovery failed: $reason — retry $discoveryRetryCount in ${delay}ms")
                    discoveryHandler.removeCallbacks(restartDiscoveryRunnable)
                    discoveryHandler.postDelayed(restartDiscoveryRunnable, delay)
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
                            if (info.groupFormed) {
                                cancelDiscoveryRestart()
                                onGroupFormed?.invoke(info)
                            } else {
                                onGroupDropped?.invoke()
                            }
                        }
                    }
                }
            }
        }
        // 必须 RECEIVER_EXPORTED：P2P 广播由系统进程发出；走 ContextCompat 兼容 API 21+。
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        receiver = r
    }

    private fun handlePeersChanged(gen: Int) {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.requestPeers(ch) { peers: WifiP2pDeviceList ->
            if (gen != generation) return@requestPeers
            if (!autoConnectAndroid) return@requestPeers
            val now = System.currentTimeMillis()
            peers.deviceList.forEach { device ->
                Timber.d("peer: ${device.deviceName} status=${device.status}")
            }
            // 同一时刻只能协商一个 P2P 连接，选第一个满足条件的 target 设备
            val target = peers.deviceList.firstOrNull { device ->
                // 跳过已连接、已邀请（协商中）或正在接入的设备
                if (device.status == WifiP2pDevice.CONNECTED) return@firstOrNull false
                if (device.status == WifiP2pDevice.INVITED) return@firstOrNull false
                val name = device.deviceName?.lowercase() ?: return@firstOrNull false
                val isOppoVivo = name.contains("oppo") || name.contains("vivo")
                val isXiaomi = name.contains("xiaomi")
                val isSamsung = name.contains("samsung") || name.contains("galaxy") || name.contains("sm-")
                if (!isOppoVivo && !isXiaomi && !isSamsung) return@firstOrNull false
                // xiaomi 可能是小米电视（sink=1），检查 wfdInfo；type=3(Dual Role) 当作 source
                if (isXiaomi) {
                    try {
                        val wfdInfo = device.wfdInfo
                        if (wfdInfo != null) {
                            val cls = wfdInfo.javaClass
                            val enabled = try {
                                cls.getMethod("isEnabled").invoke(wfdInfo) as? Boolean ?: false
                            } catch (_: NoSuchMethodException) {
                                try {
                                    cls.getMethod("isWfdEnabled").invoke(wfdInfo) as? Boolean ?: false
                                } catch (_: NoSuchMethodException) { false }
                            }
                            val deviceType = try {
                                cls.getMethod("getDeviceType").invoke(wfdInfo) as? Int
                                    ?: cls.getField("deviceType").get(wfdInfo) as? Int
                                    ?: -1
                            } catch (_: Throwable) { -1 }
                            if (enabled && deviceType == 1) {
                                Timber.d("skip Xiaomi sink: ${device.deviceName} deviceType=1")
                                return@firstOrNull false
                            }
                        }
                    } catch (e: Throwable) {
                        Timber.d("wfdInfo check failed for ${device.deviceName}: $e")
                    }
                }
                val last = lastConnectAttempt[device.deviceAddress] ?: 0L
                if (now - last < 2_000L) return@firstOrNull false
                val attempts = connectAttemptCount[device.deviceAddress] ?: 0
                if (attempts >= CONNECT_MAX_ATTEMPTS) {
                    Timber.d("skip ${device.deviceName}: reached max connect attempts ($attempts)")
                    return@firstOrNull false
                }
                true
            } ?: return@requestPeers

            val attempts = connectAttemptCount[target.deviceAddress] ?: 0
            lastConnectAttempt[target.deviceAddress] = now
            connectAttemptCount[target.deviceAddress] = attempts + 1
            Timber.i("connect() #${attempts + 1} → ${target.deviceName} status=${target.status}")
            val config = WifiP2pConfig().apply {
                deviceAddress = target.deviceAddress
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 15  // 电视必须赢得 GO 角色，才能接收投屏
            }
            try {
                p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Timber.i("connect() accepted for ${target.deviceName}")
                    override fun onFailure(reason: Int) {
                        Timber.w("connect() failed for ${target.deviceName}: $reason")
                        // 失败立即清除冷却，允许下一次 PEERS_CHANGED 再试
                        lastConnectAttempt.remove(target.deviceAddress)
                    }
                })
            } catch (e: SecurityException) {
                Timber.w("connect() denied: $e")
                lastConnectAttempt.remove(target.deviceAddress)
            }
        }
    }
}
