package com.weekd.miracastreceiver.miracast
import org.junit.Assert.*
import org.junit.Test
class SystemSettingLeaseTest {
    private var current: Int? = 0
    private var saved: SettingSnapshot? = null
    private var writes = mutableListOf<Int?>()
    private var allowWrite = true
    private fun lease() = SystemSettingLease(
        read = { current },
        write = { value -> writes += value; if (allowWrite) { current = value; true } else false },
        load = { saved }, save = { saved = it; true }
    )
    @Test fun ownedWriteRestoresPreviousValue() {
        val lease = lease()
        assertTrue(lease.prepare()); assertEquals(1, current)
        assertTrue(lease.restore()); assertEquals(0, current)
        assertEquals(listOf(1, 0), writes)
    }
    @Test fun preexistingEnabledValueIsNeverRestoredOrWritten() {
        current = 1
        val lease = lease()
        assertTrue(lease.prepare()); assertTrue(lease.restore())
        assertEquals(1, current); assertTrue(writes.isEmpty())
    }
    @Test fun externalOffOnCycleRelinquishesOwnershipPermanently() {
        val lease = lease()
        lease.prepare()
        current = 0; lease.onExternalChange()
        current = 1; lease.onExternalChange()
        assertTrue(lease.restore()); assertEquals(1, current)
        assertEquals(listOf(1), writes); assertNull(saved)
    }
    @Test fun changedValueWithoutNotificationIsAlsoPreserved() {
        val lease = lease(); lease.prepare(); current = 2
        assertTrue(lease.restore()); assertEquals(2, current)
        assertEquals(listOf(1), writes)
    }
    @Test fun failedRestorationRetainsRetryObligation() {
        val lease = lease(); lease.prepare(); allowWrite = false
        assertFalse(lease.restore()); assertNotNull(saved)
        allowWrite = true; assertTrue(lease.restore()); assertEquals(0, current)
    }
    @Test fun processRestartNeverClaimsAnUnverifiableOldWrite() {
        lease().prepare()
        val restarted = lease()
        assertTrue(restarted.restore()); assertEquals(1, current); assertNull(saved)
    }
}
