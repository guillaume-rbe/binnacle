package com.shilapi.xcertplay.network

import java.net.InetAddress
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
        assertFalse(PeerAddress.onInterface(null, "lo"))
    }

    @Test
    fun loopbackPeerIsOnLoopback() {
        assertTrue(PeerAddress.onInterface(ip("127.0.0.1"), "lo"))
    }
}
