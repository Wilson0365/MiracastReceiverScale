package com.weekd.miracastreceiver.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber

/**
 * Wi-Fi Direct (P2P) 管理器
 * 用于 Miracast/WFD 设备发现
 */
/**
 * @param deviceName 对外显示的 P2P 设备名。Windows 的「无线显示器」列表里显示的就是它，
 *   所以必须和 AirPlay / DLNA 用同一个名字（[com.weekd.miracastreceiver.discovery.DeviceInfoProvider]），
 *   否则同一台设备在不同投屏方式下会显示成不同名称。
 */
class WifiDirectManager(
    private val context: Context,
    private val deviceName: String
) {
    /**
     * 不少老电视（尤其非 Android TV 认证的整机）根本没有 Wi-Fi Direct，
     * `getSystemService` 会返回 null。原先用非空强转，一启动就抛异常闪退 ——
     * 而 Miracast 只是三种投屏方式之一，不该拖垮整个应用。
     */
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false
    // true when this device is P2P Group Owner; false when no group or we are client.
    // Set in CONNECTION_CHANGED. Guarded by main thread (all P2P callbacks run there).
    private var isGroupOwner = false
    // 记录上次对某设备发起 connect() 的时间，防止 PEERS_CHANGED 风暴时重复调用
    private val lastConnectAttempt = mutableMapOf<String, Long>()

    private val discoveryHandler = Handler(Looper.getMainLooper())
    private val restartDiscoveryRunnable = Runnable { restartDiscovery() }
    private val DISCOVERY_RESTART_INTERVAL_MS = 90_000L
    enum class StartupState { STARTING, UNSUPPORTED, PERMISSION_REQUIRED, WIFI_DISABLED, FAILED }
    var onStartupState: ((StartupState) -> Unit)? = null
    var onWfdConfigured: (() -> Unit)? = null
    var isWfdConfigured = false
        private set

    var onDeviceConnected: ((WifiP2pDevice) -> Unit)? = null
    var onDeviceDisconnected: (() -> Unit)? = null
    var onGroupCreated: ((WifiP2pGroup) -> Unit)? = null

    fun start() {
        if (isStarted) {
            Timber.w("Wi-Fi Direct already started")
            return
        }

        val p2p = manager
        if (p2p == null) {
            onStartupState?.invoke(StartupState.UNSUPPORTED)
            Timber.w("本机不支持 Wi-Fi Direct，Miracast 不可用（AirPlay / DLNA 不受影响）")
            return
        }

        try {
            onStartupState?.invoke(StartupState.STARTING)
            channel = p2p.initialize(context, Looper.getMainLooper(), null)

            if (channel == null) {
                onStartupState?.invoke(StartupState.FAILED)
                Timber.e("Failed to initialize Wi-Fi P2P channel")
                return
            }

            // 注册 Wi-Fi Direct 广播接收器
            registerReceiver()

            // 尝试设置系统级 Wi-Fi Display Sink 信息；Windows 发现无线显示器依赖这个 WFD IE
            setWfdInfo()

            // 注册本地服务以支持服务发现
            registerLocalService()

            isStarted = true
            Timber.i("Wi-Fi Direct started for Miracast")

            // 如果接管开关已启用，立即建组并写 wifi_display_on=0，让手机能找到 GO
            if (AppSettings.isMiracastTakeover(context)) {
                try {
                    Settings.Global.putInt(context.contentResolver, "wifi_display_on", 0)
                    Timber.i("wifi_display_on set to 0 on start (takeover enabled)")
                } catch (se: SecurityException) {
                    Timber.w("Cannot write wifi_display_on on start: $se")
                }
                createGroup()
            }
        } catch (e: SecurityException) {
            onStartupState?.invoke(StartupState.PERMISSION_REQUIRED)
            Timber.w(e, "Wi-Fi Direct permission required")
        } catch (e: Exception) {
            onStartupState?.invoke(StartupState.FAILED)
            Timber.e(e, "Failed to start Wi-Fi Direct")
        }
    }

    private fun setWfdInfo() {
        val ch = channel ?: return

        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdInfoClass.newInstance()

            // API 28+ 改名：setWfdEnabled → setEnabled；老 API 保留旧名
            invokeReflect(wfdInfo, wfdInfoClass, true,
                "setEnabled", "setWfdEnabled")

            // PRIMARY_SINK = 1
            wfdInfoClass.getMethod("setDeviceType", Int::class.java).invoke(wfdInfo, 1)

            // API 28+ 改名：setSessionAvailable → setSessionAvailable（未改，但有些厂商 ROM 用旧名）
            invokeReflect(wfdInfo, wfdInfoClass, true,
                "setSessionAvailable")

            // RTSP 控制端口
            wfdInfoClass.getMethod("setControlPort", Int::class.java).invoke(wfdInfo, 7236)

            // 最大吞吐量 50 Mbps
            wfdInfoClass.getMethod("setMaxThroughput", Int::class.java).invoke(wfdInfo, 50)

            val setWFDInfoMethod = WifiP2pManager::class.java.getMethod(
                "setWFDInfo",
                WifiP2pManager.Channel::class.java,
                wfdInfoClass,
                WifiP2pManager.ActionListener::class.java
            )

            setWFDInfoMethod.invoke(manager, ch, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isWfdConfigured = true
                    onWfdConfigured?.invoke()
                    Timber.i("✅ WFD Info set successfully!")
                }

                override fun onFailure(reason: Int) {
                    val reasonText = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "UNKNOWN($reason)"
                    }
                    Timber.e("❌ setWFDInfo failed: $reasonText")
                }
            })

        } catch (e: SecurityException) {
            Timber.e(e, "❌ setWFDInfo: SecurityException (CONFIGURE_WIFI_DISPLAY required)")
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "❌ WifiP2pWfdInfo class not found")
        } catch (e: NoSuchMethodException) {
            Timber.e(e, "❌ setWFDInfo method not found: ${e.message}")
        } catch (e: Exception) {
            Timber.e(e, "❌ setWFDInfo error")
        }
    }

    /**
     * 按给定名称顺序依次尝试反射调用，找到第一个存在的方法就调用并返回。
     * 全部不存在时抛 NoSuchMethodException（带所有尝试的名字）。
     */
    private fun invokeReflect(obj: Any, cls: Class<*>, arg: Boolean, vararg methodNames: String) {
        for (name in methodNames) {
            try {
                cls.getMethod(name, Boolean::class.java).invoke(obj, arg)
                return
            } catch (_: NoSuchMethodException) {
                // 继续尝试下一个名字
            }
        }
        throw NoSuchMethodException("None of ${methodNames.toList()} found in ${cls.simpleName}")
    }

    private fun createGroup() {
        val ch = channel ?: return

        try {
            manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct group created successfully")

                    // 查询组信息
                    manager?.requestGroupInfo(ch) { group ->
                        if (group != null) {
                            isGroupOwner = true
                            Timber.i("Group created - SSID: ${group.networkName}, Owner: ${group.isGroupOwner}")
                            onGroupCreated?.invoke(group)

                            // 设置设备名称
                            setDeviceName(deviceName)
                        } else {
                            onStartupState?.invoke(StartupState.FAILED)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    val reasonText = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "UNKNOWN($reason)"
                    }
                    Timber.e("Failed to create Wi-Fi Direct group: $reasonText")

                    // 如果失败，尝试作为客户端模式
                    if (reason == WifiP2pManager.BUSY) {
                        // 可能已经有组存在，尝试获取当前组信息
                        manager?.requestGroupInfo(ch) { group ->
                            if (group != null) {
                                isGroupOwner = true
                                Timber.i("Existing group found: ${group.networkName}")
                                onGroupCreated?.invoke(group)
                            } else {
                                onStartupState?.invoke(StartupState.FAILED)
                            }
                        }
                    } else {
                        onStartupState?.invoke(if (reason == WifiP2pManager.P2P_UNSUPPORTED)
                            StartupState.UNSUPPORTED else StartupState.FAILED)
                    }
                }
            })
        } catch (e: SecurityException) {
            onStartupState?.invoke(StartupState.PERMISSION_REQUIRED)
            Timber.e(e, "Security exception when creating group - missing permissions?")
        } catch (e: Exception) {
            onStartupState?.invoke(StartupState.FAILED)
            Timber.e(e, "Exception when creating group")
        }
    }

    /**
     * 尝试把 P2P 设备名改成 [deviceName]（安卓手机投屏列表里显示的就是它）。
     *
     * Android 11 起系统要求调用方持有 NETWORK_SETTINGS / NETWORK_STACK / OVERRIDE_WIFI_CONFIG
     * 之一，普通应用拿不到，这里必然失败，名称保持系统默认的 `Android_xxxx`。
     * 只有旧系统或系统签名的构建才会成功，保留调用是为了这些设备。
     */
    private fun setDeviceName(name: String) {
        try {
            // 使用反射设置设备名称（API 限制）
            val setDeviceNameMethod = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )

            setDeviceNameMethod.invoke(manager, channel, name, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Device name set to: $name")
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Failed to set device name: $reason")
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "Could not set device name (requires system permissions)")
        }
    }

    private fun registerLocalService() {
        val ch = channel ?: return

        try {
            // 创建 WFD 服务信息
            val record = mapOf(
                "version" to "1.0",
                "type" to "miracast-sink",
                "rtsp_port" to "7236"
            )

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                "_miracast", "_tcp", record
            )

            manager?.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Local Miracast service registered")
                }

                override fun onFailure(reason: Int) {
                    Timber.e("Failed to register local service: $reason")
                }
            })

            startDiscovery()
        } catch (e: SecurityException) {
            // 用户拒绝定位权限时走这里 —— Miracast 用不了，但不该影响 AirPlay / DLNA
            Timber.w("注册 P2P 本地服务被拒绝（缺少定位权限），Miracast 不可用")
        } catch (e: Exception) {
            Timber.e(e, "Error registering local service")
        }
    }

    private fun startDiscovery() {
        val ch = channel ?: return
        // Miracast 依赖 P2P 设备发现与 WFD IE；discoverServices 没有
        // addServiceRequest 时会以 NO_SERVICE_REQUESTS 失败，无法启动发现。
        manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("Miracast peer discovery started")
                scheduleDiscoveryRestart()
            }
            override fun onFailure(reason: Int) {
                Timber.e("Failed to start Miracast peer discovery: $reason")
                // 失败后稍后重试（可能是 BUSY）
                scheduleDiscoveryRestart()
            }
        })
    }

    private fun restartDiscovery() {
        if (!isStarted) return
        Timber.d("Restarting peer discovery to keep P2P stack active")
        startDiscovery()
    }

    private fun scheduleDiscoveryRestart() {
        discoveryHandler.removeCallbacks(restartDiscoveryRunnable)
        discoveryHandler.postDelayed(restartDiscoveryRunnable, DISCOVERY_RESTART_INTERVAL_MS)
    }

    private fun cancelDiscoveryRestart() {
        discoveryHandler.removeCallbacks(restartDiscoveryRunnable)
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) {
                            onStartupState?.invoke(StartupState.WIFI_DISABLED)
                            cancelDiscoveryRestart()
                        } else {
                            // P2P 刚启用（或重新启用），立即重新发现
                            startDiscovery()
                        }
                        Timber.d("Wi-Fi P2P state changed: ${if (enabled) "ENABLED" else "DISABLED"}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Timber.d("Wi-Fi P2P peers changed")
                        channel?.let { ch ->
                            manager?.requestPeers(ch) { peers: WifiP2pDeviceList ->
                                val now = System.currentTimeMillis()
                                peers.deviceList.forEach { device ->
                                    Timber.i("Peer: ${device.deviceName} status=${device.status}")
                                    // Already connected — nothing to do.
                                    // When we are GO (isGroupOwner=true), OPPO/Samsung system Miracast
                                    // sends a P2P PBC provision-discovery request and waits for the GO to
                                    // reply with connect().  If we skip connect(), their request times out
                                    // and the join never completes.  So: skip only if already CONNECTED.
                                    if (device.status != WifiP2pDevice.CONNECTED) {
                                        val last = lastConnectAttempt[device.deviceAddress] ?: 0L
                                        if (now - last < 2000L) return@forEach  // 2 秒冷却
                                        lastConnectAttempt[device.deviceAddress] = now
                                        Timber.i("Attempting connect() to ${device.deviceName} (${device.deviceAddress}) status=${device.status}")
                                        val config = WifiP2pConfig().apply {
                                            deviceAddress = device.deviceAddress
                                            wps.setup = WpsInfo.PBC
                                        }
                                        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                                            override fun onSuccess() {
                                                Timber.i("connect() sent for ${device.deviceName}")
                                            }
                                            override fun onFailure(reason: Int) {
                                                Timber.w("connect() failed for ${device.deviceName}: reason=$reason")
                                                // 失败立即解除冷却，允许下一次 PEERS_CHANGED 再试
                                                lastConnectAttempt.remove(device.deviceAddress)
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Timber.d("Wi-Fi P2P connection changed")
                        channel?.let { ch ->
                            manager?.requestConnectionInfo(ch) { info ->
                                if (info.groupFormed && info.isGroupOwner) {
                                    isGroupOwner = true
                                    Timber.i("P2P Group formed, we are GO. IP: ${info.groupOwnerAddress?.hostAddress}")
                                    // 已建组，停止定时重启（连接中不需要 discovery）
                                    cancelDiscoveryRestart()
                                } else if (info.groupFormed) {
                                    isGroupOwner = false
                                    Timber.i("P2P Group formed - we are client, GO: ${info.groupOwnerAddress?.hostAddress}")
                                    cancelDiscoveryRestart()
                                } else {
                                    isGroupOwner = false
                                    Timber.d("P2P group not formed (connection changed without group)")
                                    // 断开后：takeover 启用则重建 group（手机发现 GO）；否则仅 discovery
                                    if (AppSettings.isMiracastTakeover(context)) {
                                        createGroup()
                                    } else {
                                        startDiscovery()
                                    }
                                }
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Timber.d("This device changed: ${device?.deviceName}, Status: ${device?.status}")
                    }
                }
            }
        }

        try {
            // 必须走 ContextCompat：带 flags 的 registerReceiver 重载是 API 26 才有的，
            // 在 Android 5/6/7 上直接调用会抛 NoSuchMethodError 导致应用一启动就闪退
            // （minSdk 是 21，这类老电视确实装得上）。
            // 必须用 RECEIVER_EXPORTED：Wi-Fi P2P 广播由系统发出（跨进程），
            // NOT_EXPORTED 会导致 Permission Denial 收不到广播。
            ContextCompat.registerReceiver(
                context, receiver, intentFilter, ContextCompat.RECEIVER_EXPORTED
            )
            Timber.i("Wi-Fi P2P broadcast receiver registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register Wi-Fi P2P receiver")
        }
    }

    fun stop() {
        if (!isStarted) return

        try {
            // 移除本地服务
            channel?.let { ch ->
                manager?.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("Local services cleared")
                    }

                    override fun onFailure(reason: Int) {
                        Timber.w("Failed to clear local services: $reason")
                    }
                })

                // 移除组
                manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Timber.i("Wi-Fi Direct group removed") }
                    override fun onFailure(reason: Int) { Timber.w("Failed to remove group: $reason") }
                })
            }

            // 注销广播接收器
            receiver?.let {
                context.unregisterReceiver(it)
                receiver = null
            }

            lastConnectAttempt.clear()
            cancelDiscoveryRestart()
            channel = null
            isStarted = false
            isGroupOwner = false
            Timber.i("Wi-Fi Direct stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping Wi-Fi Direct")
        }
    }

    fun isRunning(): Boolean = isStarted

    /**
     * 立即应用接管开关状态。仅在 P2P 已启动时有效。
     * enabled=true：写 wifi_display_on=0，建立 P2P 组接管 Miracast。
     * enabled=false：写 wifi_display_on=1，移除 P2P 组还给系统。
     */
    fun applyTakeoverState(enabled: Boolean) {
        if (!isStarted) return
        val target = if (enabled) 0 else 1
        try {
            Settings.Global.putInt(context.contentResolver, "wifi_display_on", target)
            Timber.i("wifi_display_on set to $target (takeover=$enabled)")
        } catch (e: SecurityException) {
            Timber.w("Cannot write wifi_display_on: $e")
        }
        val ch = channel ?: return
        if (enabled) {
            if (isGroupOwner) {
                Timber.i("Already GO, skipping createGroup() for applyTakeoverState")
            } else {
                createGroup()
            }
        } else {
            manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Timber.i("P2P group removed (takeover off)") }
                override fun onFailure(reason: Int) { Timber.w("removeGroup failed: $reason") }
            })
        }
    }

    /** Drop the current sender's P2P link. CONNECTION_CHANGED will rebuild the group. */
    fun disconnectSender() {
        val ch = channel ?: return
        manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("Miracast sender disconnected; waiting for CONNECTION_CHANGED to rebuild group")
            }
            override fun onFailure(reason: Int) {
                Timber.w("Miracast disconnect group failed: $reason")
            }
        })
    }
}
