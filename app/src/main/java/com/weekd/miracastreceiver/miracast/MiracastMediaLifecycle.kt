package com.weekd.miracastreceiver.miracast

/** Serialized by the controller; owns only this app's media and observer. */
internal class MiracastMediaLifecycle(
    private val prepareSystemP2p: suspend () -> Boolean,
    private val startObserver: () -> Unit,
    private val stopObserver: () -> Boolean,
    private val startMedia: () -> Unit,
    private val stopMedia: suspend () -> Boolean,
    private val restoreSetting: () -> Boolean,
    initiallyNeedsCleanup: Boolean = false
) {
    var appActive = false
        private set
    private var needsCleanup = initiallyNeedsCleanup

    suspend fun enterApp(force: Boolean = false): Boolean {
        if (appActive && !force) return true
        if (needsCleanup && !enterSystem()) return false
        needsCleanup = true
        try {
            if (!prepareSystemP2p()) { enterSystem(); return false }
            startObserver()
            startMedia()
            appActive = true
            return true
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { enterSystem() }
            throw e
        }
    }

    suspend fun enterSystem(): Boolean {
        appActive = false
        if (!needsCleanup) return true
        val mediaOk = runCatching { stopMedia() }.getOrDefault(false)
        val observerOk = runCatching { stopObserver() }.getOrDefault(false)
        val settingOk = runCatching { restoreSetting() }.getOrDefault(false)
        val clean = mediaOk && observerOk && settingOk
        needsCleanup = !clean
        return clean
    }
}
