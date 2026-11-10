package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.weekd.miracastreceiver.service.CastReceiverService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

object MiracastRuntime {
    val state = MutableStateFlow<MiracastState>(MiracastState.Initializing)
    @Volatile internal var controller: MiracastController? = null
    fun requestMode(ctx: Context, mode: MiracastMode) {
        MiracastModeStore.set(ctx, mode)
        controller?.reconcile() ?: ctx.startService(Intent(ctx, CastReceiverService::class.java))
    }
    fun cleanAndReapply(ctx: Context) {
        MiracastModeStore.markCleanPending(ctx)
        controller?.reconcile() ?: ctx.startService(Intent(ctx, CastReceiverService::class.java))
    }
    fun retryIfNeeded(ctx: Context) {
        if (state.value is MiracastState.Failed && MiracastModeStore.get(ctx) == MiracastMode.APP)
            controller?.reconcile()
    }
}

/** P2P always belongs to the platform. APP owns media; SYSTEM uses native playback. */
class MiracastController(context: Context, private val observer: WifiP2pObserver, private val wfd: WfdServer) {
    companion object { private val mutex = Mutex() }
    private val ctx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handoff = SystemP2pHandoff(ctx)
    private val state = MiracastRuntime.state
    @Volatile private var stopping = false
    private var groupReady = false
    private var observationsAllowed = false
    private var healthJob: Job? = null
    private val lifecycle = MiracastMediaLifecycle(
        prepareSystemP2p = { withContext(Dispatchers.IO) { handoff.prepare() } },
        startObserver = { observationsAllowed = true; observer.start() }, stopObserver = { observer.stop() },
        startMedia = { wfd.start() },
        stopMedia = { observationsAllowed = false; groupReady = false; withContext(Dispatchers.IO) { wfd.stopAndAwait() } },
        restoreSetting = { handoff.restore() }, initiallyNeedsCleanup = handoff.needsRestore
    )
    init {
        observer.onGroupFormed = { info ->
            if (!stopping && observationsAllowed) {
                groupReady = true
                wfd.triggerScan(info)
                if (lifecycle.appActive) state.value = MiracastState.AppActive(true, false)
            }
        }
        observer.onGroupDropped = {
            if (!stopping && observationsAllowed) {
                groupReady = false
                wfd.onGroupDropped()
                if (lifecycle.appActive) state.value = MiracastState.AppActive(false, false)
            }
        }
        observer.onUnavailable = {
            val generation = lifecycle.observationGeneration
            if (!stopping) scope.launch { mutex.withLock {
                if (stopping || !lifecycle.acceptsObservationFailure(generation)) return@withLock
                healthJob?.cancel()
                lifecycle.enterSystem()
                state.value = MiracastState.Failed(MiracastError.PERMISSION_MISSING)
            } }
        }
        wfd.onSessionFailed = { Timber.w("Miracast: handshake failed; rescanning system P2P") }
    }
    fun reconcile() {
        if (stopping) return
        scope.launch { mutex.withLock {
            if (stopping) return@withLock
            val target = MiracastModeStore.get(ctx)
            val force = MiracastModeStore.takeCleanPending(ctx)
            try {
                if (target == MiracastMode.SYSTEM) {
                    healthJob?.cancel()
                    state.value = MiracastState.Switching(target)
                    val clean = lifecycle.enterSystem()
                    state.value = if (clean) MiracastState.SystemOwned else MiracastState.Failed(MiracastError.UNKNOWN)
                } else {
                    if (lifecycle.appActive && !force) return@withLock
                    preflight()
                    state.value = MiracastState.Switching(target)
                    if (!lifecycle.enterApp(force)) throw MiracastException(MiracastError.CANNOT_WRITE_SETTING)
                    state.value = MiracastState.AppActive(groupReady, false)
                    healthJob?.cancel()
                    healthJob = scope.launch { while (isActive) { delay(30_000); observer.refresh() } }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { lifecycle.enterSystem() }
                throw e
            } catch (e: Exception) {
                lifecycle.enterSystem()
                val error = when (e) {
                    is MiracastException -> e.error
                    is SecurityException -> MiracastError.PERMISSION_MISSING
                    else -> MiracastError.UNKNOWN
                }
                state.value = MiracastState.Failed(error)
                Timber.w(e, "Miracast: APP media unavailable")
            }
        } }
    }
    private fun preflight() {
        if (!observer.isSupported()) throw MiracastException(MiracastError.UNSUPPORTED)
        if (!MiracastPermissions.granted(ctx)) throw MiracastException(MiracastError.PERMISSION_MISSING)
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && !wifi.isWifiEnabled) throw MiracastException(MiracastError.WIFI_OFF)
    }
    fun disconnectSender() = wfd.disconnectSender()
    fun shutdown() { if (!stopping) scope.launch { shutdownAndAwait() } }
    suspend fun shutdownAndAwait(): Boolean = withContext(NonCancellable) {
        stopping = true
        scope.cancel()
        if (MiracastRuntime.controller === this@MiracastController) MiracastRuntime.controller = null
        observer.onGroupFormed = null
        observer.onGroupDropped = null
        observer.onUnavailable = null
        wfd.onSessionFailed = null
        mutex.withLock {
            val clean = lifecycle.enterSystem()
            state.value = if (clean) MiracastState.SystemOwned else MiracastState.Failed(MiracastError.UNKNOWN)
            clean
        }
    }
}
