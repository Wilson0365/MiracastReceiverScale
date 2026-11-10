package com.weekd.miracastreceiver.miracast

import android.content.ContextWrapper
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WfdSessionStopTest {
    @Test fun standardTeardownNotifiesOnceAndClosesSocket() {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { sender ->
                val sink = server.accept()
                val ends = AtomicInteger()
                val handler = WfdSessionHandler(ContextWrapper(null), sink, 5000)
                handler.onStreamStop = { ends.incrementAndGet() }
                val worker = Thread { handler.handleSession() }.apply { isDaemon = true; start() }
                sender.getOutputStream().write("TEARDOWN rtsp://localhost/wfd RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
                worker.join(3000)
                assertFalse("session should end", worker.isAlive)
                assertTrue(sink.isClosed)
                assertEquals(1, ends.get())
            }
        }
    }
}
