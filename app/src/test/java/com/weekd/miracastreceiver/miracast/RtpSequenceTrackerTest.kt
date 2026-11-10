package com.weekd.miracastreceiver.miracast
import org.junit.Assert.*
import org.junit.Test
class RtpSequenceTrackerTest {
    @Test fun forwardGapIsCountedOnceAndLatePacketsAreIgnored() {
        val t = RtpSequenceTracker()
        assertTrue(t.accept(100)); assertTrue(t.accept(102))
        assertEquals(1L, t.lost)
        assertFalse(t.accept(101)); assertFalse(t.accept(102))
        assertEquals(1L, t.lost); assertEquals(2L, t.lateOrDuplicate)
        assertTrue(t.accept(103)); assertEquals(1L, t.lost)
    }
    @Test fun wrapAroundDoesNotLookLikeLoss() {
        val t = RtpSequenceTracker()
        listOf(65534, 65535, 0, 1).forEach { assertTrue(t.accept(it)) }
        assertEquals(0L, t.lost)
        assertFalse(t.accept(65535)); assertEquals(0L, t.lost)
    }
}
