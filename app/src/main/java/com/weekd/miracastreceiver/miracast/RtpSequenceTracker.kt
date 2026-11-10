package com.weekd.miracastreceiver.miracast
/** RTP's 16-bit serial arithmetic: backward/duplicate arrivals never count as 65535 lost. */
internal class RtpSequenceTracker {
    private var expected = -1
    var lost = 0L
        private set
    var lateOrDuplicate = 0L
        private set
    fun accept(sequence: Int): Boolean {
        val seq = sequence and 0xffff
        if (expected >= 0) {
            val delta = (seq - expected) and 0xffff
            if (delta >= 0x8000) { lateOrDuplicate++; return false }
            lost += delta
        }
        expected = (seq + 1) and 0xffff
        return true
    }
}
