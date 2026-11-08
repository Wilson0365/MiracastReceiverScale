package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Activity 与 Service 共享的"Miracast 运行时视图"。
 * Activity 只做两件事：观察 [state]；用户拨开关时调 [requestMode]。
 */
object MiracastRuntime {
    val state = MutableStateFlow<MiracastState>(MiracastState.Initializing)

    @Volatile
    internal var controller: MiracastController? = null

    /**
     * 用户手动切换。持久化意图 + 标记"强制清理"，然后让 Controller 对齐；
     * 服务没在跑就拉起服务（标记是持久化的，服务启动后的第一次对齐会读到并执行）。
     */
    fun requestMode(ctx: Context, mode: MiracastMode) {
        MiracastModeStore.set(ctx, mode)
        MiracastModeStore.markCleanPending(ctx)
        val c = controller
        if (c != null) c.reconcile()
        else ctx.startService(Intent(ctx, CastReceiverService::class.java))
    }

    /** 手动"清理并重新应用"：不改模式，只是把当前模式从头干净地做一遍。 */
    fun cleanAndReapply(ctx: Context) = requestMode(ctx, MiracastModeStore.get(ctx))

    /** 更新"自动连接安卓"开关，立即生效（无需重启 APP 模式）。 */
    fun setAutoConnectAndroid(ctx: Context, enabled: Boolean) {
        AppSettings.setAutoConnectAndroid(ctx, enabled)
        controller?.p2p?.autoConnectAndroid = enabled
    }

    /** 上次因瞬时原因（没权限、Wi-Fi 没开）失败且意图仍是 APP 时，条件可能已满足，再试一次。 */
    fun retryIfNeeded(ctx: Context) {
        val s = state.value
        if (s is MiracastState.Failed && s.error.keepsIntent &&
            MiracastModeStore.get(ctx) == MiracastMode.APP
        ) controller?.reconcile()
    }
}

/**
 * Miracast 模式的唯一执行者。
 *
 * 不变量：
 *  - SYSTEM 状态下：没有 P2P channel / 组 / discovery / 本地服务 / WFD IE / 7236 监听，
 *    wifi_display_on = 0。app 对系统投屏服务零干扰。
 *  - APP 状态下：上述资源全部就位，wifi_display_on = 1。
 *  - 所有切换在一把进程级互斥锁里串行执行；任何一步失败都走 [enterSystem] 统一回滚。
 *
 * 退出语义：划任务 / 关界面都不碰这里。只有服务真的被销毁（通知栏停止、系统回收）才会
 * [shutdown]：撤销所有运行时资源并把投屏还给电视，但**不改用户意图**，下次服务启动自动回到 APP。
 */
class MiracastController(
    context: Context,
    internal val p2p: WifiDirectManager,
    private val wfd: WfdServer
) {
    companion object {
        /** 进程级：服务快速重启时，新旧 Controller 的清理与切换也不会交叉。 */
        private val mutex = Mutex()
        private const val DRIFT_DEBOUNCE_MS = 2_000L
        private const val OVERRIDE_WINDOW_MS = 10_000L
        private const val REBUILD_ATTEMPTS = 3
        private const val REBUILD_RETRY_MS = 5_000L
        private const val ENTER_APP_TIMEOUT_MS = 60_000L
        private const val HEALTH_INTERVAL_MS = 60_000L
    }

    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MiracastRuntime.state

    private var observer: ContentObserver? = null
    private var driftJob: Job? = null
    private var rebuildJob: Job? = null
    private var healthJob: Job? = null
    private val overrideTimes = mutableListOf<Long>()

    init {
        p2p.onGroupDropped = { scheduleRebuild() }
        p2p.onGroupFormed = { wfd.triggerScan() }
        p2p.onP2pEnabledChanged = { enabled -> onP2pEnabled(enabled) }
        wfd.onSessionFailed = { scheduleP2pReset() }
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 按持久化的用户意图对齐运行状态。
     * - 普通对齐（服务启动、权限授予后重试等）：幂等，已对齐就什么都不做，不会打断正在进行的投屏。
     * - 带"强制清理"标记（用户手动操作过）：绕过所有"已对齐"捷径，先深度清理再按意图重做。
     */
    fun reconcile() {
        scope.launch {
            mutex.withLock {
                val force = MiracastModeStore.takeCleanPending(ctx)
                converge(MiracastModeStore.get(ctx), force)
            }
        }
    }

    /** 服务被真正销毁时调用。撤销运行时资源并还给电视；用户意图不变。 */
    fun shutdown() {
        if (MiracastRuntime.controller === this) MiracastRuntime.controller = null
        p2p.onGroupDropped = null
        p2p.onP2pEnabledChanged = null
        scope.launch {
            mutex.withLock {
                val ok = enterSystem()
                state.value = if (ok) MiracastState.SystemOwned
                else MiracastState.Failed(MiracastError.CANNOT_WRITE_SETTING)
            }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------ 对齐

    private suspend fun converge(target: MiracastMode, force: Boolean) {
        if (force) {
            // 手动清理：不信任任何缓存状态（in-memory state、标志位、Global 当前值），一律从头来
            Timber.i("Miracast: manual clean requested, target=$target")
            val ok = enterSystem(deep = true)
            if (target == MiracastMode.SYSTEM) {
                state.value = if (ok) MiracastState.SystemOwned
                else MiracastState.Failed(MiracastError.CANNOT_WRITE_SETTING)
            } else {
                enterApp()
            }
            return
        }
        var cur = state.value

        // 进程刚启动：先审计上次留下的状态。此刻我们自己没有任何东西在跑，
        // 所以"系统开关是 1 且是我们写的"只可能是残留，不可能是正在工作的 APP 会话。
        if (cur is MiracastState.Initializing) {
            when (classifyResidue()) {
                Residue.OURS -> {
                    Timber.w("Miracast: previous APP session was not closed cleanly, cleaning up")
                    val ok = enterSystem(deep = true)
                    cur = if (ok) MiracastState.SystemOwned
                    else MiracastState.Failed(MiracastError.CANNOT_WRITE_SETTING)
                    state.value = cur
                }
                Residue.UNKNOWN -> if (target == MiracastMode.SYSTEM) {
                    // wifi_display_on=1 但没有任何证据是我们留下的：不擅自改，交给用户
                    Timber.w("Miracast: wifi_display_on=1 of unknown origin, asking user")
                    state.value = MiracastState.Failed(MiracastError.UNCERTAIN_STATE)
                    return
                }
                Residue.FOREIGN, Residue.NONE -> Unit
            }
            if (cur is MiracastState.Initializing) {
                state.value = MiracastState.SystemOwned
                cur = MiracastState.SystemOwned
            }
        }

        // 已经判定为"来历不明"的状态，只有手动清理（force）才会动它，自动对齐不覆盖
        if (cur is MiracastState.Failed && cur.error == MiracastError.UNCERTAIN_STATE &&
            target == MiracastMode.SYSTEM
        ) return

        when (target) {
            MiracastMode.APP -> {
                if (cur is MiracastState.AppActive) {
                    if (!cur.groupReady) tryRebuild()
                    return
                }
                enterApp()
            }
            MiracastMode.SYSTEM -> {
                // SYSTEM 稳态下不碰系统开关：不是我们占用的东西，不该由我们去"纠正"
                if (cur is MiracastState.SystemOwned) return
                val ok = enterSystem()
                state.value = if (ok) MiracastState.SystemOwned
                else MiracastState.Failed(MiracastError.CANNOT_WRITE_SETTING)
            }
        }
    }

    private enum class Residue { NONE, OURS, FOREIGN, UNKNOWN }

    /**
     * 只在进程启动、尚未做任何事时调用。靠三种持久化证据判断来历：
     *  - session 标记：APP 会话没收尾 → 一定是我们的。
     *  - 所有权凭证 lastWritten：系统开关当前值 == 我们最后写的值 → 我们的；不同 → 别人改过。
     *  - 都没有（比如应用数据被清）而开关是 1 → 无法判断。
     */
    private fun classifyResidue(): Residue {
        if (MiracastModeStore.isSessionOpen(ctx)) return Residue.OURS
        val g = WifiDisplaySetting.current(ctx) ?: return Residue.NONE
        val w = MiracastModeStore.lastWritten(ctx)
        return when {
            w == null -> if (g == WifiDisplaySetting.VALUE_APP) Residue.UNKNOWN else Residue.NONE
            g == w -> if (g == WifiDisplaySetting.VALUE_APP) Residue.OURS else Residue.NONE
            else -> Residue.FOREIGN
        }
    }

    // ------------------------------------------------------------------ SYSTEM -> APP

    private suspend fun enterApp() {
        state.value = MiracastState.Switching(MiracastMode.APP)
        try {
            withTimeout(ENTER_APP_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { preflight() }

                // write-ahead：第一次产生副作用之前先登记"APP 会话已开"，
                // 之后无论在哪一步崩溃，下次启动都能证明残留是我们的
                withContext(Dispatchers.IO) { MiracastModeStore.setSessionOpen(ctx, true) }

                // 先占住系统开关，系统服务才会让出 P2P / 7236
                val written = withContext(Dispatchers.IO) { WifiDisplaySetting.write(ctx, true) }
                if (!written) throw MiracastException(MiracastError.CANNOT_WRITE_SETTING)

                p2p.open()
                p2p.autoConnectAndroid = AppSettings.isAutoConnectAndroid(ctx)
                p2p.registerLocalService()
                // autoConnectAndroid 模式：跳过 createGroup()，直接 discoverPeers()，
                // 由 handlePeersChanged 主动发起 connect(groupOwnerIntent=15)，走 P2P 协商
                // 而非 Autonomous GO join 路径（后者会触发 UserAuthorizingJoinState，app 无权授权）。
                val ie: Boolean
                if (p2p.autoConnectAndroid) {
                    ie = false
                    p2p.startDiscovery()
                } else {
                    p2p.createGroup()
                    // WFD IE 必须在建组之后：createGroup 是异步的，会覆盖先前注入的 IE
                    ie = advertiseWfdIe()
                    p2p.startDiscovery()
                }
                wfd.start()

                startDriftWatch()
                startHealthWatch()
                // autoConnectAndroid 模式无预建组，groupReady=false；连接后由 onGroupDropped/healthWatch 管理
                val groupReady = !p2p.autoConnectAndroid
                state.value = MiracastState.AppActive(groupReady = groupReady, wfdIeAdvertised = ie)
                Timber.i("Miracast: APP mode active (wfdIe=$ie)")
            }
        } catch (e: TimeoutCancellationException) {
            failToSystem(MiracastError.TIMEOUT, e)
        } catch (e: CancellationException) {
            enterSystem()
            throw e
        } catch (e: Exception) {
            failToSystem(e.toMiracastError(), e)
        }
    }

    /** 失败统一出口：回滚到 SYSTEM，永久性错误同时把意图改回 SYSTEM。 */
    private suspend fun failToSystem(error: MiracastError, cause: Throwable) {
        Timber.w(cause, "Miracast: enter APP failed -> $error, rolling back to SYSTEM")
        val ok = enterSystem()
        if (!error.keepsIntent) MiracastModeStore.set(ctx, MiracastMode.SYSTEM)
        // 回滚本身没写成功 = 系统开关还卡在 APP 值，这才是真正的"黑洞"，如实报告
        state.value = if (ok) MiracastState.Failed(error)
        else MiracastState.Failed(MiracastError.CANNOT_WRITE_SETTING)
    }

    /** 预检：全部是"还没改任何东西"之前就能判断的失败。 */
    private fun preflight() {
        if (!p2p.isSupported()) throw MiracastException(MiracastError.UNSUPPORTED)
        if (!MiracastPermissions.granted(ctx)) throw MiracastException(MiracastError.PERMISSION_MISSING)
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && !wifi.isWifiEnabled) throw MiracastException(MiracastError.WIFI_OFF)
        if (!WifiDisplaySetting.canWrite(ctx)) throw MiracastException(MiracastError.CANNOT_WRITE_SETTING)
    }

    /**
     * 尝试通过 setWFDInfo 向发送端声明本机是 Sink。
     * 需要 signature 级 CONFIGURE_WIFI_DISPLAY 权限，普通应用通常失败。
     * 失败后手机发送端（OPPO/Samsung/小米）靠 P2P 设备发现仍可正常连接；
     * Windows 等依赖 WFD IE 的发送端则看不到本机——这是无 root / 无系统签名的已知限制。
     */
    private suspend fun advertiseWfdIe(): Boolean = p2p.setWfdInfo()

    // ------------------------------------------------------------------ APP -> SYSTEM（也是统一的回滚）

    /**
     * 撤销本 app 对 Miracast 的一切占用，最后才把 wifi_display_on 交还系统。
     * 幂等、不抛异常、不可取消。
     *
     * @param deep 手动清理用：临时开 channel 撤掉残留的组 / 本地服务 / discovery。
     * @return 系统开关是否确实回到了"系统负责"。
     */
    private suspend fun enterSystem(deep: Boolean = false): Boolean = withContext(NonCancellable) {
        state.value = MiracastState.Switching(MiracastMode.SYSTEM)
        stopDriftWatch()
        healthJob?.cancel()
        rebuildJob?.cancel()
        runCatching { wfd.disconnectSender() }
        runCatching { wfd.stop() }
        runCatching { p2p.close() }
        if (deep) runCatching { p2p.purge() }
        withContext(Dispatchers.IO) {
            val ok = WifiDisplaySetting.write(ctx, false)
            // 只有确认系统开关已还回去，才算会话干净结束；否则保持"脏"，下次启动继续尝试
            if (ok) MiracastModeStore.setSessionOpen(ctx, false)
            ok
        }
    }

    // ------------------------------------------------------------------ APP 模式下的自愈

    private fun scheduleRebuild() {
        if (rebuildJob?.isActive == true) return
        rebuildJob = scope.launch {
            repeat(REBUILD_ATTEMPTS) {
                if (mutex.withLock { tryRebuild() }) return@launch
                delay(REBUILD_RETRY_MS)
            }
            // 重建多次仍失败：不允许长期停在"系统开关被占着、但 app 又没在工作"的黑洞里，把投屏还给电视。
            // 意图保留，回到界面或下次启动会自动再试。
            mutex.withLock {
                val cur = state.value
                if (cur is MiracastState.AppActive && !cur.groupReady) {
                    Timber.w("Miracast: group could not be rebuilt, giving the TV back")
                    val ok = enterSystem()
                    state.value = if (ok) MiracastState.Failed(MiracastError.GROUP_FAILED)
                    else MiracastState.Failed(MiracastError.CANNOT_WRITE_SETTING)
                }
            }
        }
    }

    /**
     * RTSP 握手失败（"传输通道被占用"等）：主动断开 P2P 组，让发送端重置 WFD 状态机，
     * 等 [onGroupDropped] 触发后 [scheduleRebuild] 会自动重建。
     * 不影响 APP 模式状态——只是把 P2P 组拆掉，让双方重新走 P2P 协商流程。
     */
    private fun scheduleP2pReset() {
        scope.launch {
            Timber.w("Miracast: RTSP handshake failed, resetting P2P group to unblock sender")
            p2p.disconnectSender()
            // disconnectSender 是异步的；P2P 组断开后系统广播 CONNECTION_CHANGED(groupFormed=false)
            // → onGroupDropped → scheduleRebuild，不需要在这里额外处理。
        }
    }

    /** 必须在锁内调用。非 APP 状态直接视为"没事可做"（丢弃过期回调）。 */
    private suspend fun tryRebuild(): Boolean {
        val cur = state.value as? MiracastState.AppActive ?: return true
        return try {
            var ie = cur.wfdIeAdvertised
            if (p2p.autoConnectAndroid) {
                // autoConnectAndroid 模式无 Autonomous GO，重建只需重启 discovery
                p2p.startDiscovery()
            } else {
                val created = p2p.createGroup()
                if (created) {
                    ie = advertiseWfdIe()
                    p2p.startDiscovery()
                }
            }
            state.value = MiracastState.AppActive(groupReady = true, wfdIeAdvertised = ie)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Miracast: rebuild group failed")
            state.value = cur.copy(groupReady = false)
            false
        }
    }

    private fun onP2pEnabled(enabled: Boolean) {
        val cur = state.value as? MiracastState.AppActive ?: return
        if (!enabled) state.value = cur.copy(groupReady = false)
        else if (!cur.groupReady) scheduleRebuild()
    }

    // ------------------------------------------------------------------ 状态与现实核对

    /** 每分钟核对一次"我们以为的"和"系统里实际的"，防止漏掉广播后状态悄悄漂移。 */
    private fun startHealthWatch() {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (true) {
                delay(HEALTH_INTERVAL_MS)
                mutex.withLock {
                    val cur = state.value as? MiracastState.AppActive ?: return@withLock
                    if (!p2p.autoConnectAndroid && cur.groupReady && !p2p.hasGroup()) {
                        Timber.w("Miracast: state says group ready but none exists, rebuilding")
                        state.value = cur.copy(groupReady = false)
                        scheduleRebuild()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 系统开关被外部改动

    private fun startDriftWatch() {
        if (observer != null) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onSettingChanged()
        }
        ctx.contentResolver.registerContentObserver(WifiDisplaySetting.uri, false, obs)
        observer = obs
    }

    private fun stopDriftWatch() {
        observer?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
        observer = null
        driftJob?.cancel()
        overrideTimes.clear()
    }

    private fun onSettingChanged() {
        if (state.value !is MiracastState.AppActive || WifiDisplaySetting.isAppOwned(ctx)) return
        driftJob?.cancel()
        driftJob = scope.launch {
            delay(DRIFT_DEBOUNCE_MS)
            mutex.withLock {
                if (state.value !is MiracastState.AppActive || WifiDisplaySetting.isAppOwned(ctx)) return@withLock
                val now = SystemClock.elapsedRealtime()
                overrideTimes.removeAll { now - it > OVERRIDE_WINDOW_MS }
                overrideTimes += now
                if (overrideTimes.size >= 2) {
                    // 系统在主动覆盖（比如用户进了电视的投屏设置）：不对抗，尊重系统，降级并告知
                    Timber.w("Miracast: wifi_display_on overridden by system, falling back to SYSTEM")
                    enterSystem()
                    MiracastModeStore.set(ctx, MiracastMode.SYSTEM)
                    state.value = MiracastState.Failed(MiracastError.SETTING_OVERRIDDEN)
                } else {
                    Timber.i("Miracast: wifi_display_on drifted, re-applying once")
                    withContext(Dispatchers.IO) { WifiDisplaySetting.write(ctx, true) }
                }
            }
        }
    }

    private fun Throwable.toMiracastError(): MiracastError = when (this) {
        is MiracastException -> error
        is SecurityException -> MiracastError.PERMISSION_MISSING
        else -> MiracastError.UNKNOWN
    }
}
