package com.weekd.miracastreceiver.miracast

import android.content.ContextWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WfdServerStopTest {
    @Test fun shutdownAwaitsScannerAndIsRepeatable() = runBlocking {
        val server = WfdServer(ContextWrapper(null))
        server.start()
        delay(50)
        assertTrue(server.stopAndAwait())
        assertFalse(server.isRunning())
        assertTrue(server.stopAndAwait())
    }
}
