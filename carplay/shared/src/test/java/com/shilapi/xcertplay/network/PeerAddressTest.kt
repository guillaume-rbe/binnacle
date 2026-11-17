package com.shilapi.xcertplay.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAddressTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun hotspotPeerIsInside() {
        assertTrue(PeerAddress.inSubnet(ip("192.168.49.23"), ip("192.168.49.1"), 24))
    }

    @Test
    fun homeWifiPeerIsOutside() {
        assertFalse(PeerAddress.inSubnet(ip("192.168.1.50"), ip("192.168.49.1"), 24))
    }

    @Test
    fun oddPrefixComparesPartialByte() {
        assertTrue(PeerAddress.inSubnet(ip("10.0.0.130"), ip("10.0.0.129"), 25))
        assertFalse(PeerAddress.inSubnet(ip("10.0.0.20"), ip("10.0.0.129"), 25))
    }

    @Test
    fun zeroPrefixAndMixedFamiliesNeverCount() {
        assertFalse(PeerAddress.inSubnet(ip("8.8.8.8"), ip("192.168.49.1"), 0))
        assertFalse(PeerAddress.inSubnet(ip("2001:db8::1"), ip("192.168.49.1"), 24))
        assertFalse(PeerAddress.inSubnet(ip("192.168.49.2"), null, 24))
    }

    @Test
    fun noInterfaceMeansNoLimitAndUnknownInterfaceRefuses() {
        assertTrue(PeerAddress.onInterface(ip("192.168.1.50"), null))
        assertFalse(PeerAddress.onInterface(ip("192.168.49.2"), "no-such-iface0"))
        assertFalse(PeerAddress.onInterface(null, NetworkInterface.getByInetAddress(ip("127.0.0.1")).name))
    }

    @Test
    fun loopbackPeerIsOnLoopback() {
        val loopback = ip("127.0.0.1")
        val name = NetworkInterface.getByInetAddress(loopback).name   // "lo" on Linux, "lo0" on macOS
        assertTrue(PeerAddress.onInterface(loopback, name))
    }

    @Test
    fun loopbackConnectionIsAcceptedOnLoopbackOnly() {
        val loopback = ip("127.0.0.1")
        ServerSocket(0, 50, loopback).use { server ->
            Socket(loopback, server.localPort).use {
                server.accept().use { accepted ->
                    assertTrue(PeerAddress.accepts(accepted, NetworkInterface.getByInetAddress(loopback).name))
                    assertFalse(PeerAddress.accepts(accepted, "no-such-iface0"))
                    assertTrue(PeerAddress.accepts(accepted, null))
                }
            }
        }
    }

    @Test
    fun acceptMatchingSkipsRefusedConnectionsAndReportsOnce() {
        val loopback = ip("127.0.0.1")
        ServerSocket(0, 50, loopback).use { server ->
            val clients = (1..3).map { Socket().apply { connect(InetSocketAddress(loopback, server.localPort)) } }
            var seen = 0
            var reported = 0
            val accepted = server.acceptMatching({ ++seen == 3 }, { reported++ })
            assertEquals(3, seen)
            assertEquals(1, reported)
            accepted.close()
            clients.forEach { it.close() }
        }
    }
}
