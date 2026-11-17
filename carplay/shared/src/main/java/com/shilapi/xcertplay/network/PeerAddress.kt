package com.shilapi.xcertplay.network

import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Which network a connecting peer is on. The listeners bind every address, and Linux takes a
 * connection to any of the unit's addresses on any interface, so the address a peer connected to
 * doesn't say where it is: its own address does.
 */
internal object PeerAddress {
    /**
     * The connection came in on [iface] (no limit when null): the peer connected to one of the
     * interface's own addresses, and the peer's own address is on the interface's networks. A
     * link-local peer whose scope isn't reported is taken on the first test alone (Linux keeps
     * link-local addresses to their own link).
     */
    fun accepts(socket: Socket, iface: String?): Boolean {
        if (iface == null) return true
        val network = runCatching { NetworkInterface.getByName(iface) }.getOrNull() ?: return false
        val local = socket.localAddress ?: return false
        if (network.inetAddresses.toList().none { it.address.contentEquals(local.address) }) return false
        return onInterface(socket.inetAddress, network)
    }

    /**
     * [remote] is on one of [iface]'s own networks (no limit when [iface] is null). A link-local
     * IPv6 peer counts when it came in over [iface] (its scope), or with no scope given.
     */
    fun onInterface(remote: InetAddress?, iface: String?): Boolean {
        if (iface == null) return true
        val network = runCatching { NetworkInterface.getByName(iface) }.getOrNull() ?: return false
        return onInterface(remote, network)
    }

    private fun onInterface(remote: InetAddress?, network: NetworkInterface): Boolean {
        if (remote == null) return false
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

/**
 * Waits for a connection [accepts] takes; any other is closed and the next one awaited. A refused
 * connection is reported to [onRefused] the first time only, so a peer retrying in a loop can't
 * flood the log.
 */
internal fun ServerSocket.acceptMatching(
    accepts: (Socket) -> Boolean,
    onRefused: (Socket) -> Unit = {},
): Socket {
    var reported = false
    while (true) {
        val socket = accept()
        if (accepts(socket)) return socket
        if (!reported) {
            reported = true
            onRefused(socket)
        }
        runCatching { socket.close() }
    }
}
