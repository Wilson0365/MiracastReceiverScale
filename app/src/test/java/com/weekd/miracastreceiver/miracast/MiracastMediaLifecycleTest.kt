package com.weekd.miracastreceiver.miracast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MiracastMediaLifecycleTest {
    private val events = mutableListOf<String>()
    private var handoffOk = true
    private var stopOk = true
    private var restoreOk = true
    private val lifecycle by lazy { MiracastMediaLifecycle(
        prepareSystemP2p = { events += "handoff"; handoffOk },
        startObserver = { events += "observe" },
        stopObserver = { events += "unobserve"; true },
        startMedia = { events += "media" },
        stopMedia = { events += "stop"; stopOk },
        restoreSetting = { events += "restore"; restoreOk }
    ) }

    @Test fun systemStartupDoesNotWriteSettingsOrOpenObserver() = runBlocking {
        assertTrue(lifecycle.enterSystem())
        assertEquals(emptyList<String>(), events)
    }

    @Test fun appEntryIsIdempotentAndHandoffPrecedesObservationAndMedia() = runBlocking {
        assertTrue(lifecycle.enterApp())
        assertTrue(lifecycle.enterApp())
        assertEquals(listOf("handoff", "observe", "media"), events)
    }

    @Test fun stopClosesMediaAndObserverThenRestoresSetting() = runBlocking {
        lifecycle.enterApp()
        events.clear()
        assertTrue(lifecycle.enterSystem())
        assertTrue(lifecycle.enterSystem())
        assertEquals(listOf("stop", "unobserve", "restore"), events)
    }

    @Test fun forceRestartWaitsForStopBeforeOpeningAnotherSession() = runBlocking {
        lifecycle.enterApp()
        events.clear()
        assertTrue(lifecycle.enterApp(force = true))
        assertEquals(listOf("stop", "unobserve", "restore", "handoff", "observe", "media"), events)
    }

    @Test fun failedHandoffNeverStartsMediaAndCanRetry() = runBlocking {
        handoffOk = false
        assertFalse(lifecycle.enterApp())
        assertFalse(events.contains("media"))
        handoffOk = true
        assertTrue(lifecycle.enterApp())
    }

    @Test fun failedStopStillStopsObserverButPreventsReplacementReceiver() = runBlocking {
        lifecycle.enterApp()
        stopOk = false
        events.clear()
        assertFalse(lifecycle.enterApp(force = true))
        assertEquals(listOf("stop", "unobserve", "restore"), events)
        stopOk = true
        assertTrue(lifecycle.enterSystem())
    }

    @Test fun failedRestoreIsRetriedAndPreventsNewSession() = runBlocking {
        lifecycle.enterApp()
        restoreOk = false
        assertFalse(lifecycle.enterSystem())
        assertFalse(lifecycle.enterApp())
        restoreOk = true
        assertTrue(lifecycle.enterApp())
    }

    @Test fun queuedFailureFromOldObserverCannotStopReplacementAppSession() = runBlocking {
        lifecycle.enterApp()
        val oldGeneration = lifecycle.observationGeneration
        lifecycle.enterApp(force = true)
        assertFalse(lifecycle.acceptsObservationFailure(oldGeneration))
        assertTrue(lifecycle.appActive)
        assertTrue(lifecycle.acceptsObservationFailure(lifecycle.observationGeneration))
    }

    @Test fun queuedFailureAfterAppToSystemIsIgnored() = runBlocking {
        lifecycle.enterApp()
        val oldGeneration = lifecycle.observationGeneration
        lifecycle.enterSystem()
        assertFalse(lifecycle.acceptsObservationFailure(oldGeneration))
    }
}
