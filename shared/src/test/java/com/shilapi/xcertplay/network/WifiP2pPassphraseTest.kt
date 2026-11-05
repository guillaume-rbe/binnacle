package com.shilapi.xcertplay.network

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiP2pPassphraseTest {
    @Test fun randomPassphraseIsLongAndValid() {
        val passphrase = randomWifiP2pPassphrase(SecureRandom())
        assertEquals(20, passphrase.length)
        assertTrue(isValidWifiP2pPassphrase(passphrase))
    }

    @Test fun passphrasesDiffer() {
        val random = SecureRandom()
        assertNotEquals(randomWifiP2pPassphrase(random), randomWifiP2pPassphrase(random))
    }

    @Test fun oldDerivedPassphraseIsNotKept() {
        // the old 8-hex-digit passphrase from a previous build is replaced, not reused
        val old = mfiCertificateWifiP2pCredentials(byteArrayOf(1, 2, 3)).passphrase
        assertEquals(8, old.length)
        assertFalse(isValidWifiP2pPassphrase(old))
    }

    @Test fun ssidStillComesFromTheCertificate() {
        val a = mfiCertificateWifiP2pCredentials(byteArrayOf(1, 2, 3)).ssid
        assertEquals(a, mfiCertificateWifiP2pCredentials(byteArrayOf(1, 2, 3)).ssid)
        assertTrue(a.startsWith("DIRECT-xcertplay"))
    }
}
