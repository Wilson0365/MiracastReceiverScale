package com.weekd.miracastreceiver.miracast

import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class AudioThreadStopTest {
    @Test fun liveAudioWorkerMustNotBeReportedStopped() {
        val release = CountDownLatch(1)
        val worker = Thread { release.await() }.apply { isDaemon = true; start() }
        try {
            assertFalse(awaitAudioThread(worker, 20))
        } finally { release.countDown() }
        assertTrue(awaitAudioThread(worker, 1000))
    }
}
