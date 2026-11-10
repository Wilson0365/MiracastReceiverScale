package com.weekd.miracastreceiver.service

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.cast.CastPlayerBridge
import com.weekd.miracastreceiver.cast.GoogleCastReceiver
import com.weekd.miracastreceiver.airplay.AirPlayCapabilities
import com.weekd.miracastreceiver.airplay.fitMirrorResolution
import com.weekd.miracastreceiver.airplay.AirPlayReceiver
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.dlna.DlnaMediaRenderer
import com.weekd.miracastreceiver.dlna.SsdpServer
import com.weekd.miracastreceiver.dlna.UpnpHttpServer
import com.weekd.miracastreceiver.miracast.MiracastController
import com.weekd.miracastreceiver.miracast.MiracastRuntime
import com.weekd.miracastreceiver.miracast.MiracastMode
import com.weekd.miracastreceiver.miracast.MiracastModeStore
import com.weekd.miracastreceiver.miracast.WfdServer
import com.weekd.miracastreceiver.miracast.WifiP2pObserver
import com.weekd.miracastreceiver.util.AppLanguage
import com.weekd.miracastreceiver.utils.NetworkUtils
import timber.log.Timber
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 投屏接收后台服务
 * 保持应用在后台持续监听投屏连接
 */
class CastReceiverService : Service() {

    companion object {
        /** 由 BootReceiver 设置：标记这次启动来自开机广播。 */
        const val EXTRA_FROM_BOOT = "from_boot"

        private const val ACTION_STOP_CAST = "com.weekd.miracastreceiver.STOP_CAST"
        const val ACTION_EXIT_RECEIVER = "com.weekd.miracastreceiver.EXIT_RECEIVER"
        const val EXTRA_MANUAL_START = "manual_start"

        fun requestExit(context: Context) {
            MiracastModeStore.set(context, MiracastMode.SYSTEM)
            context.startService(Intent(context, CastReceiverService::class.java).setAction(ACTION_EXIT_RECEIVER))
        }
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val ACTION_UPDATE_POSITION = "com.weekd.miracastreceiver.ACTION_UPDATE_POSITION"
        private const val ACTION_PLAYBACK_STOPPED = "com.weekd.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        /** 开机后等网络就绪的轮询间隔与最长等待时间。 */
        private const val NETWORK_RETRY_INTERVAL_MS = 5_000L
        private const val NETWORK_MAX_WAIT_MS = 5 * 60_000L
    }

    private lateinit var airPlayReceiver: AirPlayReceiver
    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var castPlayerBridge: CastPlayerBridge
    private lateinit var googleCastReceiver: GoogleCastReceiver
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var miracast: MiracastController

    /** startAllServices() 已执行；防止重复 onStartCommand 再建一套接收器。 */
    private var servicesStarted = false
    private lateinit var deviceUuid: String
    private var airPlayPlayerStarted = false

    /** initReceivers() 是否已经跑过：没跑过时那些 lateinit 字段不能碰。 */
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkWaitRunnable: Runnable? = null
    private var networkWaitElapsedMs = 0L
    @Volatile private var stopping = false
    @Volatile private var miracastSessionStarted = false
    private var receiversStopped = false
    private var restartRequested = false
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (stopping) return
            when (intent?.action) {
                CastPlayerBridge.ACTION_STATE -> castPlayerBridge.onState(intent)
                "com.weekd.miracastreceiver.DISCONNECT_SENDER" -> {
                    if (intent.getBooleanExtra("miracast", false)) {
                        MiracastRuntime.controller?.disconnectSender()
                    } else {
                        airPlayPlayerStarted = false
                        airPlayReceiver.disconnectSender()
                    }
                }
                ACTION_UPDATE_POSITION -> {
                    val position = intent.getLongExtra("position", 0L)
                    val duration = intent.getLongExtra("duration", 0L)
                    val isPlaying = intent.getBooleanExtra("is_playing", false)
                    dlnaRenderer.updatePosition(position, duration)
                    if (isPlaying) dlnaRenderer.setPlaying() else dlnaRenderer.setPaused()
                    Timber.d("Player position updated: $position / $duration")
                }
                ACTION_PLAYBACK_STOPPED -> {
                    // TV 端本地退出/播放结束，需要让 DLNA 状态归零，
                    // 否则 Emby 会一直显示"正在播放"且无法取消/切换投屏。
                    dlnaRenderer.updatePosition(0L, 0L)
                    dlnaRenderer.setStopped()
                    Timber.i("Playback stopped locally, DLNA renderer reset to STOPPED")
                }
            }
        }
    }

    /** 通知、DLNA 默认标题等文案跟随应用内选择的语言 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        ReceiverStatus.service = ReceiverStatus.Service.STARTING
        super.onCreate()
        Timber.i("CastReceiverService created")
        createNotificationChannel()
        // startForeground 放在 onStartCommand 里：前台服务类型要看这次是不是开机拉起的
        // （Android 15 起 BOOT_COMPLETED 不允许启动 mediaPlayback 类型）。
    }

    /**
     * 真正的初始化。开机启动时网络还没就绪，DLNA/SSDP 需要本机 IP 才能正确绑定，
     * 所以推迟到 [startWhenNetworkReady] 拿到 IP 之后再做，且只做一次。
     */
    private fun initReceivers() {
        if (initialized) return
        initialized = true

        // 初始化设备 UUID
        deviceUuid = generateDeviceUuid()

        // A 4K display alone is insufficient: macOS may choose HEVC even when the
        // receiver has only advertised AVC. Keep AVC-only hardware within 1080p.
        val displayResolution = getBestDisplayResolution()
        val mirrorResolution = if (AirPlayCapabilities.hardwareHevc4k) {
            displayResolution
        } else {
            fitMirrorResolution(displayResolution.first, displayResolution.second, 1920, 1080)
        }
        val deviceName = DeviceInfoProvider(this).getDeviceName()
        Timber.i("AirPlay mirror advertised resolution: ${mirrorResolution.first}x${mirrorResolution.second}")

        // 初始化 AirPlay 2 接收器（基于 PhairPlay 实现，支持 iOS 屏幕镜像）
        airPlayReceiver = AirPlayReceiver(
            context = this,
            displayName = deviceName,
            mirrorWidth = mirrorResolution.first,
            mirrorHeight = mirrorResolution.second,
            audioEnabled = true,
            videoSurfaceProvider = { com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurface },
            onStateChanged = { state ->
                Timber.i("AirPlay state: $state")
                if (!stopping && state == com.weekd.miracastreceiver.airplay.AirPlayState.CONNECTED && !airPlayPlayerStarted) {
                    airPlayPlayerStarted = true
                    val intent = Intent(this, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
                        putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, getString(R.string.title_iphone_mirror))
                        putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_IS_AIRPLAY_MIRROR, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
            },
            onSessionEnded = { wasMirroring ->
                if (!stopping && wasMirroring) {
                    beginExit()
                } else if (!stopping && airPlayPlayerStarted) {
                    airPlayPlayerStarted = false
                    Timber.i("AirPlay session ended, closing player")
                    sendBroadcast(Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                        setPackage(packageName)
                    })
                }
            },
            onSenderNameChanged = { sender -> Timber.i("AirPlay sender: $sender") }
        )

        // 初始化 Miracast 控制器
        initMiracastController()

        // 初始化 DLNA/UPnP 服务
        initDlnaServices()
        castPlayerBridge = CastPlayerBridge(this)
        googleCastReceiver = GoogleCastReceiver(this, deviceName, deviceUuid, castPlayerBridge.session)
        val playerStateFilter = IntentFilter().apply {
            addAction(CastPlayerBridge.ACTION_STATE)
            addAction("com.weekd.miracastreceiver.DISCONNECT_SENDER")
            addAction(ACTION_UPDATE_POSITION)
            addAction(ACTION_PLAYBACK_STOPPED)
        }
        // ContextCompat 而非直接调用：带 flags 的重载是 API 26 才有的，
        // 老电视（Android 5/6/7）上直接调会 NoSuchMethodError 闪退。
        ContextCompat.registerReceiver(
            this, playerStateReceiver, playerStateFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun getBestDisplayResolution(): Pair<Int, Int> {
        // Display.getSupportedModes / getMode 是 API 23 起才有的，而 minSdk 是 21。
        // 这个方法在服务 onCreate 里调用，低版本上直接调会 NoSuchMethodError 闪退，
        // 所以 Android 5.x 直接用 displayMetrics（精度略低但够用）。
        var width = resources.displayMetrics.widthPixels
        var height = resources.displayMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.supportedModes
                ?.maxByOrNull { it.physicalWidth * it.physicalHeight }
                ?: display?.mode
            if (mode != null) {
                width = mode.physicalWidth
                height = mode.physicalHeight
            }
        }
        val longSide = maxOf(width, height).coerceIn(1280, 3840)
        val shortSide = minOf(width, height).coerceIn(720, 2160)
        return longSide to shortSide
    }

    private fun initMiracastController() {
        if (::miracast.isInitialized) return
        val wfdServer = WfdServer(this, 7236).apply {
            onConnectionRequested = { clientName, clientAddress ->
                Timber.i("Miracast connection requested: $clientName from $clientAddress")
            }
            onConnectionEstablished = { sessionId ->
                Timber.i("Miracast session established: $sessionId")
            }
            onStreamStarted = { rtpPort ->
                miracastSessionStarted = true
                Timber.i("Miracast stream started on RTP port: $rtpPort")
            }
            onStreamStopped = {
                val endedMirroring = miracastSessionStarted
                miracastSessionStarted = false
                Timber.i("Miracast stream stopped, closing player")
                // Windows 断开（RTSP 连接关闭或 TEARDOWN）后必须主动关掉播放页，
                // 否则手机会一直停在最后一帧画面上
                mainHandler.post {
                    if (!stopping && endedMirroring) sendBroadcast(
                        Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).setPackage(packageName)
                    )
                }
            }
        }
        val p2p = WifiP2pObserver(this)
        miracast = MiracastController(this, p2p, wfdServer)
        MiracastRuntime.controller = miracast
    }

    private fun initDlnaServices() {
        val deviceInfoProvider = DeviceInfoProvider(this)
        val localIp = NetworkUtils.getLocalIpAddress(this) ?: "127.0.0.1"

        // 创建 DLNA MediaRenderer
        dlnaRenderer = DlnaMediaRenderer()

        // 设置回调
        dlnaRenderer.onSetUri = { uri, metadata ->
            dispatchPlayback {
                Timber.i("DLNA SetURI: $uri")
                val state = dlnaRenderer.getState()
                // 启动 PlayerActivity 并播放
                val intent = Intent(this, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
                    if (state.playlist.size > 1) {
                        putStringArrayListExtra(
                            com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URIS,
                            ArrayList(state.playlist)
                        )
                        putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_START_INDEX, state.currentIndex)
                    } else {
                        putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URI, uri)
                    }
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, extractTitle(metadata))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
            }
        }

        dlnaRenderer.onPlay = {
            dispatchPlayback {
                Timber.i("DLNA Play")
                // 发送播放广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PLAY).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        dlnaRenderer.onPause = {
            dispatchPlayback {
                Timber.i("DLNA Pause")
                // 发送暂停广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PAUSE).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        dlnaRenderer.onStop = {
            dispatchPlayback {
                Timber.i("DLNA Stop")
                // 发送停止广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        dlnaRenderer.onSeek = { position ->
            dispatchPlayback {
                Timber.i("DLNA Seek: $position")
                // 发送跳转广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SEEK).apply {
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SEEK_POSITION, position)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        dlnaRenderer.onVolumeChanged = { volume ->
            dispatchPlayback {
                Timber.i("DLNA Volume: $volume")
                // 发送音量广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_VOLUME).apply {
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_VOLUME, volume)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        dlnaRenderer.onSpeedChanged = { speed ->
            dispatchPlayback {
                Timber.i("DLNA Speed: $speed")
                // 发送播放速度广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_SPEED).apply {
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SPEED, speed)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        dlnaRenderer.onQualityUriChanged = { uri ->
            dispatchPlayback {
                Timber.i("DLNA Quality URI: $uri")
                // 发送画质 URI 变化广播到 PlayerActivity
                val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_QUALITY_URL).apply {
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_QUALITY_URI, uri)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        // 创建 UPnP HTTP 服务器
        upnpHttpServer = UpnpHttpServer(
            context = this,
            renderer = dlnaRenderer,
            deviceUuid = deviceUuid,
            deviceName = deviceInfoProvider.getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            modelName = Build.MODEL,
            localIp = localIp,
            port = 8080
        )

        // 创建 SSDP 服务器
        ssdpServer = SsdpServer(
            context = this,
            deviceUuid = deviceUuid,
            localIp = localIp,
            httpPort = 8080
        )
    }

    private fun dispatchPlayback(action: () -> Unit) {
        mainHandler.post { if (!stopping) action() }
    }

    private fun extractTitle(metadata: String): String {
        // 简单解析 DIDL-Lite metadata 中的 title
        val titlePattern = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
        val match = titlePattern.find(metadata)
        return match?.groupValues?.getOrNull(1) ?: getString(R.string.title_dlna)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAST || intent?.action == ACTION_EXIT_RECEIVER) {
            beginExit()
            return START_NOT_STICKY
        }
        if (stopping) {
            if (intent?.getBooleanExtra(EXTRA_MANUAL_START, false) == true) restartRequested = true
            return START_NOT_STICKY
        }
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
        Timber.i("CastReceiverService started (fromBoot=$fromBoot)")

        startForegroundCompat(fromBoot)
        startWhenNetworkReady()

        return START_STICKY
    }

    private fun beginExit() {
        if (stopping) return
        stopping = true
        airPlayPlayerStarted = false
        if (::castPlayerBridge.isInitialized) castPlayerBridge.stop()
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null
        sendReceiverExitStop()
        val exit = ReceiverExit(
            saveSystem = { withContext(Dispatchers.IO) { MiracastModeStore.set(this@CastReceiverService, MiracastMode.SYSTEM) } },
            cleanupMiracast = {
                // Residue cleanup does not require LAN readiness or starting any receiver.
                initMiracastController()
                var airPlayClean = true
                if (initialized) {
                    // Stop audio immediately, rather than leaving it playing during P2P timeouts.
                    airPlayClean = withContext(Dispatchers.IO) { airPlayReceiver.stopAndAwait() }
                }
                val miracastClean = miracast.shutdownAndAwait()
                airPlayClean && miracastClean
            },
            stopReceivers = { withContext(Dispatchers.IO) { stopOtherReceivers() } },
            finish = {
                sendReceiverExitStop()
                ReceiverStatus.service = ReceiverStatus.Service.STOPPED
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        )
        exitScope.launch {
            runCatching { exit.exit() }.onSuccess { clean ->
                Timber.i("Receiver exit completed (Miracast clean=$clean)")
            }.onFailure { Timber.e(it, "Receiver exit failed") }
        }
    }

    private fun sendReceiverExitStop() {
        sendBroadcast(Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP)
            .setPackage(packageName).putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_EXIT_RECEIVER, true))
    }

    private fun stopOtherReceivers() {
        if (!initialized || receiversStopped) return
        receiversStopped = true
        com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurfaceListener = null
        runCatching { airPlayReceiver.stop() }.onFailure { Timber.e(it, "AirPlay stop failed") }
        runCatching { ssdpServer.stop() }.onFailure { Timber.e(it, "SSDP stop failed") }
        runCatching { upnpHttpServer.stop() }.onFailure { Timber.e(it, "UPnP stop failed") }
        runCatching { googleCastReceiver.stop() }.onFailure { Timber.e(it, "Cast stop failed") }
    }

    /**
     * 前台服务类型：
     * - 正常从界面启动用 mediaPlayback（投屏播放）；
     * - 开机自启必须避开 mediaPlayback —— Android 15 起从 BOOT_COMPLETED 启动该类型
     *   会抛 ForegroundServiceStartNotAllowedException，改用 connectedDevice
     *   （本来也符合"接收局域网设备投屏"的语义，CHANGE_WIFI_STATE 已满足前提条件）。
     */
    private fun startForegroundCompat(fromBoot: Boolean) {
        val type = if (fromBoot) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        // ServiceCompat：type 参数在 API 29 以下会被忽略，老电视上不会崩。
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), type)
    }

    /**
     * 等到拿得到局域网 IP 再启动各路接收器。
     *
     * 开机自启时 Wi-Fi 往往还在连，这时候启动 SSDP/mDNS 会绑到错误的地址，
     * 结果就是服务活着但手机搜不到设备。这里每 5 秒看一次，最多等 5 分钟。
     */
    private fun startWhenNetworkReady() {
        if (stopping) return
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        // 服务已在运行时再次 onStartCommand（例如首次授予 Wi-Fi Direct 权限后界面重启服务）：
        // 只重新对齐 Miracast，不重建整套接收器（重建会导致 AirPlay 等端口冲突）。
        if (servicesStarted) {
            Timber.i("Cast services already running — reconciling Miracast")
            if (initialized) miracast.reconcile()
            return
        }

        if (NetworkUtils.getLocalIpAddress(this) != null) {
            networkWaitElapsedMs = 0L
            initReceivers()
            startAllServices()
            return
        }

        if (networkWaitElapsedMs >= NETWORK_MAX_WAIT_MS) {
            Timber.w("Network still not ready after ${NETWORK_MAX_WAIT_MS / 1000}s, starting anyway")
            networkWaitElapsedMs = 0L
            initReceivers()
            startAllServices()
            return
        }

        Timber.i("Network not ready yet, retry in ${NETWORK_RETRY_INTERVAL_MS}ms")
        ReceiverStatus.service = ReceiverStatus.Service.WAITING_NETWORK
        val runnable = Runnable {
            networkWaitElapsedMs += NETWORK_RETRY_INTERVAL_MS
            startWhenNetworkReady()
        }
        networkWaitRunnable = runnable
        mainHandler.postDelayed(runnable, NETWORK_RETRY_INTERVAL_MS)
    }

    private fun startAllServices() {
        if (stopping) return
        servicesStarted = true
        // 启动 AirPlay 2 接收器
        airPlayReceiver.start()
        // Surface 重建时，恢复 AirPlay URL 视频与屏幕镜像的输出
        com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurfaceListener = { airPlayReceiver.onVideoSurfaceChanged() }

        // 启动 DLNA/UPnP 服务
        upnpHttpServer.start()
        ssdpServer.start()
        googleCastReceiver.start()

        // 按用户意图对齐 Miracast 模式
        miracast.reconcile()

        ReceiverStatus.service = ReceiverStatus.Service.RUNNING

        Timber.i("All cast services started (AirPlay + DLNA + Miracast/WFD + experimental Google Cast)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 用户从最近任务里划掉应用时不停服务：服务与 Activity 生命周期解耦，
     *  投屏在 "无界面" 状态下持续工作（同 AirPlay / DLNA 的行为）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("Task removed, cast service continues in background")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopping = true
        ReceiverStatus.service = ReceiverStatus.Service.STOPPED
        super.onDestroy()
        Timber.i("CastReceiverService destroyed")

        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        // 还没等到网络就被停掉时，下面那些 lateinit 字段根本没初始化，碰了会崩。
        if (initialized) try {
            unregisterReceiver(playerStateReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering playerStateReceiver")
        }

        if (!receiversStopped) {
            if (::castPlayerBridge.isInitialized) castPlayerBridge.stop()
            stopOtherReceivers()
            if (::miracast.isInitialized) miracast.shutdown()
        }
        exitScope.cancel()
        if (restartRequested) mainHandler.post {
            startService(Intent(this, CastReceiverService::class.java))
        }
    }

    private fun generateDeviceUuid(): String {
        // 使用设备信息生成一致的 UUID
        val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
        return UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                // 渠道已存在时再次创建只会更新名称和描述，切换系统语言后重启服务即可跟着变
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, com.weekd.miracastreceiver.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopCast = PendingIntent.getService(
            this, 1, Intent(this, CastReceiverService::class.java).setAction(ACTION_STOP_CAST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_waiting))
            .setSmallIcon(R.drawable.ic_stat_miracast)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop_cast), stopCast)
            .build()
    }
}
