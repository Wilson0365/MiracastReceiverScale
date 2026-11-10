package com.weekd.miracastreceiver.miracast
import java.net.DatagramSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class RtpBindTest {
    @Test fun occupiedPortFailsSynchronouslyAndDoesNotBecomeActive() = runBlocking {
        DatagramSocket(0).use { occupied ->
            val receiver = RtpReceiver(occupied.localPort) { null }
            try {
                assertFalse(receiver.start())
                assertFalse(receiver.isRunning())
                assertNotSame(receiver, RtpReceiver.active)
            } finally { receiver.stop(); receiver.awaitStopped() }
        }
    }
    @Test fun successfulStartReturnsOnlyAfterPortIsBound() = runBlocking {
        val port = DatagramSocket(0).use { it.localPort }
        val receiver = RtpReceiver(port) { null }
        try {
            assertTrue(receiver.start())
            assertTrue(receiver.isRunning())
            try { DatagramSocket(port).close(); fail("RTP port should already be bound") }
            catch (_: java.net.BindException) { }
        } finally { receiver.stop(); assertTrue(receiver.awaitStopped()) }
        DatagramSocket(port).close()
    }
}
