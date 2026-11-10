package com.weekd.miracastreceiver.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReceiverExitTest {
    @Test fun savesIntentBeforeCleanupAndStopsOtherReceiversAfterFailure() = runBlocking {
        val calls = mutableListOf<String>()
        val exit = ReceiverExit(
            { calls.add("SYSTEM") },
            { calls.add("miracast"); false },
            { calls.add("other receivers") },
            { calls.add("foreground and service") }
        )
        assertFalse(exit.exit())
        exit.exit()
        assertEquals(listOf("SYSTEM", "miracast", "other receivers", "foreground and service"), calls)
    }

    @Test fun failedCleanupStillFinishesService() = runBlocking {
        var stopped = false
        var finished = false
        val exit = ReceiverExit({}, { error("P2P failed") }, { stopped = true }, { finished = true })
        assertFalse(exit.exit())
        assertTrue(stopped)
        assertTrue(finished)
    }
}
