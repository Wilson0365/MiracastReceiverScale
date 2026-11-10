package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pInfo
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Wi-Fi Display (Miracast) 会话发起器。
 *
 * 注意这里**不是** RTSP 服务器 —— WFD 规范里 Source 才是监听方。实测（Windows 11
 * MSMiracastSource）确认：Source 接入 P2P 组后会在自己的 7236 端口等待，Sink 必须
 * 主动连过去，否则双方互等到超时（约 60 秒）后断开。
 *
 * 因此本类的职责是：
 *   1. 在 P2P 网段里找出正在监听 7236 的 Source
 *   2. 连过去，交给 [WfdSessionHandler] 跑 M1–M7
 *   3. 会话期间用 [RtpReceiver] 收 RTP（MPEG-2 TS）
 *
 * 前置条件：Source 能发现本机，依赖 wpa_supplicant 广播 WFD IE，这需要 root 注入
 * （见 [WfdRootHelper]）—— 应用自身调 setWFDInfo 会被系统拒绝。
 *
 * @param port Source 的 RTSP 监听端口（WFD 标准 7236）
 * @param rtpPort 本机接收 RTP 的 UDP 端口
 */
class WfdServer(
    private val context: Context,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000
) {
    private var scope: CoroutineScope? = null
    private val sessionLock = Any()
    private val sockets = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private var stoppedJob: Job? = null
    private var stoppedReceiver: RtpReceiver? = null

    @Volatile
    private var isRunning = false
    @Volatile private var reconnectAfterMs = 0L
    @Volatile private var closedByUs = false

    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    // 与旧实现保持同样的回调签名，服务层无需改动
    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    /**
     * RTSP 握手阶段失败（连上了 7236 但没走到 PLAY）。
     * 典型原因：OPPO 还没释放上次的通道（"传输通道被占用"），或握手超时。
     * Controller 接到后应拆 P2P 组，让 OPPO 重置 WFD 状态机，再重建。
     */
    var onSessionFailed: (() -> Unit)? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val SCAN_INTERVAL_EAGER_MS = 1_000L  // P2P 刚建好时的高频间隔
        private const val EAGER_WINDOW_MS = 20_000L        // 高频扫描持续时间
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val SCAN_CHUNK = 32          // 每批并发探测的地址数
    }

    @Volatile private var groupFormedAtMs = 0L
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var tvIsGroupOwner = true

    @Volatile private var triggerImmediate = false

    fun start() {
        if (isRunning) {
            Timber.w("WFD session starter already running")
            return
        }
        isRunning = true
        closedByUs = false
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s
        Timber.i("WFD session starter running (will dial source:$port, RTP on $rtpPort)")

        s.launch {
            while (isRunning) {
                try {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!triggerImmediate && now < reconnectAfterMs) {
                        delay(SCAN_INTERVAL_MS)
                        continue
                    }
                    triggerImmediate = false
                    val socket = dialSource()
                    if (socket != null) {
                        runSession(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "WFD session loop error")
                }
                if (!isRunning) break
                // P2P 刚建好时高频扫描，等 OPPO 的 7236 就绪
                val elapsed = android.os.SystemClock.elapsedRealtime() - groupFormedAtMs
                val interval = if (elapsed < EAGER_WINDOW_MS) SCAN_INTERVAL_EAGER_MS else SCAN_INTERVAL_MS
                delay(interval)
            }
        }
    }

    /**
     * 在 P2P 网段里找到 Source 并**保持**那条连接。
     *
     * 关键：不能「探测后关闭再重连」—— Source 的监听队列只接受一次连接，探测连接会被它
     * 当成 Sink 正式接入，随后 listener 就关闭了；等我们再连时必然 ECONNREFUSED，
     * 而 Source 那边看到会话刚建立就断开，直接报"连接失败"。
     * 所以扫描成功的那条 socket 直接用作会话连接。
     */
    private suspend fun dialSource(): Socket? = coroutineScope {
        // p2p0 接口在 P2P-GROUP-FORMATION-SUCCESS 后还需要几秒才拿到 DHCP 地址，
        // 轮询等它就绪，最多等 15 秒，再开始扫描。
        val subnetDeadline = android.os.SystemClock.elapsedRealtime() + 15_000L
        while (isRunning && p2pSubnetPrefix() == null) {
            if (android.os.SystemClock.elapsedRealtime() >= subnetDeadline) {
                Timber.w("WFD: dialSource timed out waiting for p2p subnet")
                return@coroutineScope null
            }
            Timber.d("WFD: p2p subnet not ready yet, retrying in 500ms")
            delay(500)
        }

        // TV 不是 GO 时（罕见，OPPO 抢赢了），Source 在 goAddr（通常是 .1）。
        // 直接拨，不用扫全段。
        if (!tvIsGroupOwner) {
            val goAddr = groupOwnerAddress
            if (goAddr != null) {
                Timber.i("WFD: TV is P2P client, dialing GO directly at $goAddr")
                val s = tryConnect(goAddr)
                if (s != null) return@coroutineScope s
                Timber.w("WFD: direct dial to GO $goAddr failed")
            }
        }

        val prefix = p2pSubnetPrefix() ?: return@coroutineScope null
        Timber.d("WFD: scanning subnet ${prefix}2-254 for source on port $port")

        for (chunkStart in 2..254 step SCAN_CHUNK) {
            if (!isRunning) return@coroutineScope null
            val range = chunkStart until minOf(chunkStart + SCAN_CHUNK, 255)
            val connected = range.map { host ->
                async { tryConnect("$prefix$host") }
            }.awaitAll().filterNotNull()

            if (connected.isNotEmpty()) {
                val session = connected.first()
                connected.drop(1).forEach { runCatching { it.close() } }
                Timber.i("WFD: connected to source at ${session.inetAddress.hostAddress}:$port")
                return@coroutineScope session
            }
        }
        Timber.d("WFD: scan complete, no source found on port $port")
        null
    }

    private fun tryConnect(ip: String): Socket? {
        val s = Socket()
        synchronized(sessionLock) {
            if (!isRunning) { s.close(); return null }
            sockets.add(s)
        }
        return try {
            s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            s
        } catch (e: Exception) {
            runCatching { s.close() }
            sockets.remove(s)
            null
        }
    }

    /**
     * 取本机 P2P 接口的 /24 前缀（通常是 "192.168.49."）。
     * 组主地址本身要排除掉，剩下的就是 Source 可能拿到的地址。
     */
    private fun p2pSubnetPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name.startsWith("p2p") && it.isUp }
            ?.inetAddresses?.toList()
            ?.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
            ?.substringBeforeLast('.')
            ?.plus(".")
    } catch (e: Exception) {
        Timber.e(e, "Failed to resolve P2P subnet")
        null
    }

    /** 在已连接的 socket 上跑完整个会话；结束后返回，外层循环继续等待下一次投屏。 */
    private fun runSession(socket: Socket) {
        var streamStarted = false
        val (receiver, handler) = synchronized(sessionLock) {
        if (!isRunning) { socket.close(); sockets.remove(socket); return }
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        onConnectionRequested?.invoke("Windows PC", sourceIp)

        // RTP 接收必须先起来：M3 会把端口告诉 Source，M7 之后随时可能来包。
        // 送显目标复用镜像专用 SurfaceView（与 AirPlay 镜像同一块），
        // 播放页尚未创建时返回 null，解码器会等它就绪。
        val receiver = RtpReceiver(rtpPort, { com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurface }).apply {
            onError = { Timber.e("Miracast RTP error: $it") }
            start()
        }
        rtpReceiver = receiver

        closedByUs = false
        val handler = try { WfdSessionHandler(context, socket, rtpPort).apply {
            onSessionEstablished = { onConnectionEstablished?.invoke(it) }
            onStreamStart = {
                streamStarted = true
                onStreamStarted?.invoke(it)
            }
            onStreamStop = { onStreamStopped?.invoke() }
        } } catch (e: Exception) { receiver.stop(); throw e }
        sessionHandler = handler
        receiver to handler
        }

        try {
            handler.handleSession()          // 阻塞直到会话结束
        } finally {
            receiver.stop()
            sockets.remove(socket)
            rtpReceiver = null
            sessionHandler = null
            // 只有在没收到 PLAY 且不是我们主动关闭时，才认为是握手失败（"通道占用"）。
            // stop() 和 disconnectSender() 调用时 closedByUs=true，不触发 P2P reset。
            if (!streamStarted && !closedByUs) {
                Timber.w("WFD: session ended without PLAY (channel busy or handshake failed), requesting P2P reset")
                onSessionFailed?.invoke()
            }
        }
    }

    /** P2P 组已形成，立刻触发一次扫描，不等 3 秒周期。 */
    fun triggerScan(info: WifiP2pInfo) {
        groupFormedAtMs = android.os.SystemClock.elapsedRealtime()
        tvIsGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress
        // Only trigger immediate if not inside a cooldown window (e.g. from disconnectSender).
        if (android.os.SystemClock.elapsedRealtime() >= reconnectAfterMs) {
            triggerImmediate = true
        }
        Timber.i("WFD: P2P group formed, isGO=$tvIsGroupOwner goAddr=$groupOwnerAddress, triggering immediate scan")
    }

    fun stop() {
        synchronized(sessionLock) {
        isRunning = false
        closedByUs = true
        // Send TEARDOWN before closing the socket so Source can clean up its WFD state machine.
        // disconnectSender() is synchronous on the caller thread here (no scope needed).
        runCatching { sessionHandler?.disconnectSender() }
        sessionHandler = null
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        if (rtpReceiver != null) stoppedReceiver = rtpReceiver
        rtpReceiver?.stop()
        rtpReceiver = null
        if (scope != null) stoppedJob = scope?.coroutineContext?.get(Job)
        scope?.cancel()
        scope = null
        Timber.i("WFD session starter stopped")
        }
    }

    suspend fun stopAndAwait(): Boolean {
        stop()
        return withTimeoutOrNull(5_000L) {
            stoppedJob?.join()
            stoppedReceiver?.awaitStopped() ?: true
        } ?: false
    }

    fun isRunning(): Boolean = isRunning

    /** Returns true when a Source is connected and PLAY has been acknowledged (stream is live). */
    fun hasActiveSession(): Boolean = sessionHandler != null

    fun disconnectSender() {
        closedByUs = true
        reconnectAfterMs = android.os.SystemClock.elapsedRealtime() + 5000
        val current = sessionHandler
        scope?.launch { runCatching { current?.disconnectSender() } }
    }
}
