package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Which network a connecting peer is on. The listeners bind every address, and Linux takes a
 * connection to any of the unit's addresses on any interface, so the address a peer connected to
 * doesn't say where it is: its own address does.
 */
internal object PeerAddress {
    /**
     * [remote] is on one of [iface]'s own networks (no limit when [iface] is null). A link-local
     * IPv6 peer counts when it came in over [iface] (its scope), or with no scope given.
     */
    fun onInterface(remote: InetAddress?, iface: String?): Boolean {
        if (iface == null) return true
        if (remote == null) return false
        val network = runCatching { NetworkInterface.getByName(iface) }.getOrNull() ?: return false
        if (remote is Inet6Address && remote.isLinkLocalAddress) {
            return remote.scopeId == 0 || remote.scopeId == network.index
        }
        return network.interfaceAddresses.any { inSubnet(remote, it.address, it.networkPrefixLength.toInt()) }
    }

    /** [remote] is inside [network]/[prefix]; a whole-internet prefix (0) never counts. */
    fun inSubnet(remote: InetAddress, network: InetAddress?, prefix: Int): Boolean {
        if (network == null) return false
        val a = remote.address
        val b = network.address
        if (a.size != b.size || prefix <= 0 || prefix > a.size * 8) return false
        val whole = prefix / 8
        for (i in 0 until whole) if (a[i] != b[i]) return false
        val bits = prefix % 8
        if (bits == 0) return true
        val mask = (0xff shl (8 - bits)) and 0xff
        return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
    }
}
