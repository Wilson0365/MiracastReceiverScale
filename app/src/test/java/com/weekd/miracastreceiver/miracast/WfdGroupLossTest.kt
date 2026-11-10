package com.weekd.miracastreceiver.miracast

import android.content.ContextWrapper
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WfdGroupLossTest {
    @Test fun groupLossDuringCandidateReadPreventsObsoleteConnection() = runBlocking {
        ServerSocket(0).use { source ->
            source.soTimeout = 600
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server = WfdServer(ContextWrapper(null), source.localPort, 19001) {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                listOf(P2pSubnet("127.0.0.1", 30))
            }
            server.start()
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                server.onGroupDropped()
                release.countDown()
                assertThrows(SocketTimeoutException::class.java) { source.accept().close() }
                Unit
            } finally { release.countDown(); assertTrue(server.stopAndAwait()) }
        }
    }
}
