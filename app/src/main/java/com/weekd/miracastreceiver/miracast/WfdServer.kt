package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pInfo
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * WFD Sink session client: system-managed P2P provides the network and Sink advertising.
 * The app connects to Source TCP 7236 and owns RTSP/RTP and custom playback only.
 * No root advertising or active P2P management takes place here.
 */
class WfdServer internal constructor(
    private val context: Context,
    private val port: Int,
    private val rtpPort: Int,
    private val subnetProvider: () -> List<P2pSubnet>
) {
    constructor(context: Context, port: Int = 7236, rtpPort: Int = 19000) :
        this(context, port, rtpPort, ::readSystemP2pSubnets)
    @Volatile private var groupAvailable = true
    @Volatile private var networkGeneration = 0L
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

    /** Handshake failure: close media and rescan, leaving system P2P intact. */
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
                    val generation = networkGeneration
                    val socket = dialSource(generation)
                    if (socket != null) {
                        runSession(socket, generation)
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
    private suspend fun dialSource(generation: Long): Socket? = coroutineScope {
        if (!scanValid(generation)) return@coroutineScope null
        // p2p0 接口在 P2P-GROUP-FORMATION-SUCCESS 后还需要几秒才拿到 DHCP 地址，
        // 轮询等它就绪，最多等 15 秒，再开始扫描。
        val subnetDeadline = android.os.SystemClock.elapsedRealtime() + 15_000L
        while (scanValid(generation) && p2pSubnets().isEmpty()) {
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
                val s = tryConnect(goAddr, generation)
                if (s != null) return@coroutineScope s
                Timber.w("WFD: direct dial to GO $goAddr failed")
            }
        }

        val candidates = p2pSubnets().flatMap { it.candidates() }.distinct()
        Timber.d("WFD: scanning " + candidates.size + " P2P addresses")
        for (chunk in candidates.chunked(SCAN_CHUNK)) {
            if (!scanValid(generation)) return@coroutineScope null
            val connected = chunk.map { ip -> async { tryConnect(ip, generation) } }.awaitAll().filterNotNull()
            if (connected.isNotEmpty()) {
                val session = connected.first()
                connected.drop(1).forEach { runCatching { it.close() }; sockets.remove(it) }
                Timber.i("WFD: connected to source " + session.inetAddress.hostAddress)
                return@coroutineScope session
            }
        }
        Timber.d("WFD: scan complete, no source found on port $port")
        null
    }

    private fun scanValid(generation: Long): Boolean = isRunning && groupAvailable && networkGeneration == generation

    private fun tryConnect(ip: String, generation: Long): Socket? {
        val s = Socket()
        synchronized(sessionLock) {
            if (!scanValid(generation)) { s.close(); return null }
            sockets.add(s)
        }
        return try {
            s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            synchronized(sessionLock) {
                if (scanValid(generation)) s
                else { s.close(); sockets.remove(s); null }
            }
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
    private fun p2pSubnets(): List<P2pSubnet> = subnetProvider()

    /** 在已连接的 socket 上跑完整个会话；结束后返回，外层循环继续等待下一次投屏。 */
    private fun runSession(socket: Socket, generation: Long) {
        var streamStarted = false
        val (receiver, handler) = synchronized(sessionLock) {
        if (!scanValid(generation)) { socket.close(); sockets.remove(socket); return }
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        onConnectionRequested?.invoke("Windows PC", sourceIp)

        // RTP 接收必须先起来：M3 会把端口告诉 Source，M7 之后随时可能来包。
        // 送显目标复用镜像专用 SurfaceView（与 AirPlay 镜像同一块），
        // 播放页尚未创建时返回 null，解码器会等它就绪。
        val receiver = RtpReceiver(rtpPort, { com.weekd.miracastreceiver.ui.PlayerActivity.mirrorSurface }).apply {
            onError = { Timber.e("Miracast RTP error: $it") }
        }
        if (!receiver.start()) {
            receiver.stop()
            socket.close()
            sockets.remove(socket)
            onSessionFailed?.invoke()
            return
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
                Timber.w("WFD: session ended without PLAY (channel busy or handshake failed), resuming media scan")
                onSessionFailed?.invoke()
            }
        }
    }

    /** P2P 组已形成，立刻触发一次扫描，不等 3 秒周期。 */
    fun triggerScan(info: WifiP2pInfo) {
        synchronized(sessionLock) { groupAvailable = info.groupFormed }
        groupFormedAtMs = android.os.SystemClock.elapsedRealtime()
        tvIsGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress
        // Only trigger immediate if not inside a cooldown window (e.g. from disconnectSender).
        if (android.os.SystemClock.elapsedRealtime() >= reconnectAfterMs) {
            triggerImmediate = true
        }
        Timber.i("WFD: P2P group formed, isGO=$tvIsGroupOwner goAddr=$groupOwnerAddress, triggering immediate scan")
    }

    /** Lost system group: close media/probes, without changing P2P. */
    fun onGroupDropped() {
        synchronized(sessionLock) {
            groupAvailable = false
            networkGeneration++
            groupOwnerAddress = null
            groupFormedAtMs = 0L
            closedByUs = true
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
            rtpReceiver?.stop()
        }
    }

    fun stop() {
        synchronized(sessionLock) {
        isRunning = false
        networkGeneration++
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

private fun readSystemP2pSubnets(): List<P2pSubnet> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("p2p") && it.isUp }
            .flatMap { it.interfaceAddresses }
            .filter { !it.address.isLoopbackAddress && it.address.address.size == 4 }
            .map { P2pSubnet(it.address.hostAddress!!, it.networkPrefixLength.toInt()) }
    } catch (e: Exception) {
        Timber.w(e, "WFD: cannot read system P2P interfaces")
        emptyList()
    }
