package com.weekd.miracastreceiver.miracast
/** Real IPv4 prefix, bounded probing for large networks, excluding the local address. */
internal data class P2pSubnet(val localAddress: String, val prefixLength: Int) {
    fun candidates(): List<String> {
        require(prefixLength in 0..32)
        val octets = localAddress.split('.').map { it.toInt() }
        require(octets.size == 4 && octets.all { it in 0..255 })
        val local = octets.fold(0L) { n, octet -> (n shl 8) or octet.toLong() }
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        val network = local and mask
        val broadcast = network or (mask xor 0xffffffffL)
        val first = if (prefixLength >= 31) network else network + 1
        val last = if (prefixLength >= 31) broadcast else broadcast - 1
        // On very wide networks probe near the actual interface address, not an arbitrary /24.
        val start = maxOf(first, local - 512)
        val end = minOf(last, start + 1024)
        return (start..end).asSequence().filter { it != local }.take(1024).map { ip ->
            listOf(24, 16, 8, 0).joinToString(".") { shift -> ((ip shr shift) and 255).toString() }
        }.toList()
    }
}
