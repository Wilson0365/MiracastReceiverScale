package com.weekd.miracastreceiver.miracast
import android.content.ContextWrapper
import android.content.Intent
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
class WfdResponseTest {
    private class Session : AutoCloseable {
        val listener = ServerSocket(0)
        val source = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 500 }
        val sink = listener.accept()
        val plays = AtomicInteger()
        val playerStarted = CountDownLatch(1)
        val handler = WfdSessionHandler(object : ContextWrapper(null) {
            override fun startActivity(intent: Intent) { playerStarted.countDown() }
        }, sink, 19000).apply { onStreamStart = { plays.incrementAndGet() } }
        val worker = Thread { handler.handleSession() }.apply { isDaemon = true; start() }
        fun send(message: String) { source.getOutputStream().write(message.toByteArray()); source.getOutputStream().flush() }
        fun read(): String? {
            val message = StringBuilder()
            try {
                while (!message.endsWith("\r\n\r\n")) {
                    val b = source.getInputStream().read()
                    if (b < 0) return null
                    message.append(b.toChar())
                }
            } catch (_: SocketTimeoutException) { return null }
            return message.toString()
        }
        fun setup(): String {
            val body = "wfd_trigger_method: SETUP\r\n"
            send("SET_PARAMETER rtsp://localhost/wfd RTSP/1.0\r\nCSeq: 9\r\nContent-Length: " + body.length + "\r\n\r\n" + body)
            assertTrue(read()!!.startsWith("RTSP/1.0 200"))
            return read()!!.also { assertTrue(it.startsWith("SETUP ")) }
        }
        fun cseq(message: String): String = message.lineSequence().first { it.startsWith("CSeq:") }.substringAfter(':').trim()
        fun reply(seq: String, status: String = "200 OK", session: Boolean = false) {
            send("RTSP/1.0 " + status + "\r\nCSeq: " + seq + "\r\n" + (if (session) "Session: test-session\r\n\r\n" else "\r\n"))
        }
        override fun close() { sink.close(); source.close(); listener.close(); worker.join(2000) }
    }
    @Test fun onlyMatchingSetupAndPlayResponsesStartPlayer() {
        Session().use { s ->
            val setupSeq = s.cseq(s.setup())
            s.reply("99", session = true)
            assertNull("Unrelated response must not send PLAY", s.read())
            s.reply(setupSeq, session = true)
            val play = s.read()!!
            assertTrue(play.startsWith("PLAY "))
            s.reply(setupSeq)
            assertFalse(s.playerStarted.await(150, TimeUnit.MILLISECONDS))
            assertEquals(0, s.plays.get())
            s.reply(s.cseq(play))
            assertTrue(s.playerStarted.await(1000, TimeUnit.MILLISECONDS))
            assertEquals(1, s.plays.get())
        }
    }
    @Test fun rejectedPlayNeverStartsPlayer() {
        Session().use { s ->
            s.reply(s.cseq(s.setup()), session = true)
            val play = s.read()!!
            s.reply(s.cseq(play), "453 Not Enough Bandwidth")
            s.worker.join(1000)
            assertFalse(s.worker.isAlive); assertEquals(0, s.plays.get())
        }
    }
}
