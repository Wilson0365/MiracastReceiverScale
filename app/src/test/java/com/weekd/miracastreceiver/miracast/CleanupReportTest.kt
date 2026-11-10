package com.weekd.miracastreceiver.miracast

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CleanupReportTest {
    @Test fun settingSuccessDoesNotMaskP2pFailure() = runBlocking {
        val report = CleanupReport()
        report.attempt("P2P") { false }
        report.attempt("setting") { true }
        assertFalse(report.clean)
        assertEquals(listOf("P2P"), report.failures)
    }
    @Test fun cleanupContinuesAfterExceptionAndRemembersFailure() = runBlocking {
        val report = CleanupReport()
        var attempted = false
        report.attempt("root IE") { error("timeout") }
        report.attempt("P2P") { attempted = true; true }
        assertTrue(attempted)
        assertFalse(report.clean)
    }
}
