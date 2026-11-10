package com.weekd.miracastreceiver.miracast
import org.junit.Assert.*
import org.junit.Test
class P2pSubnetTest {
    @Test fun slash30FindsOnlyTheOtherHost() {
        assertEquals(listOf("192.168.49.2"), P2pSubnet("192.168.49.1", 30).candidates())
    }
    @Test fun clientScanIncludesDotOneButExcludesItsOwnAddress() {
        val hosts = P2pSubnet("192.168.49.100", 24).candidates()
        assertTrue(hosts.contains("192.168.49.1")); assertFalse(hosts.contains("192.168.49.100"))
        assertFalse(hosts.contains("192.168.49.0")); assertFalse(hosts.contains("192.168.49.255"))
        assertEquals(253, hosts.size)
    }
    @Test fun wideSubnetIsBoundedAndSlash32HasNoOtherHosts() {
        assertTrue(P2pSubnet("10.20.30.40", 16).candidates().size <= 1024)
        assertEquals(emptyList<String>(), P2pSubnet("10.20.30.40", 32).candidates())
        assertEquals(listOf("10.0.0.0"), P2pSubnet("10.0.0.1", 31).candidates())
    }
}
