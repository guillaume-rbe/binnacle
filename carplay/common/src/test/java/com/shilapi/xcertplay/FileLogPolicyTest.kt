package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileLogPolicyTest {
    @Test fun debugLogsOnKeepsEverything() {
        val frame = "IAP2 RX [wireless] 0x4155 CallStateUpdate frame=80B body=3 params\n  [0] remoteID: \"+44 7700 900123\""
        assertEquals(frame, FileLogPolicy.forFile(frame, debugLogs = true))
        assertEquals("TRACE x", FileLogPolicy.forFile("TRACE x", debugLogs = true))
    }

    @Test fun traceLinesAreLeftOut() {
        assertNull(FileLogPolicy.forFile("TRACE MEDIA VIDEO wireHex=00ff", debugLogs = false))
    }

    @Test fun iap2FramesKeepTheirHeaderOnly() {
        val frame = "IAP2 RX [wireless] 0x4155 CallStateUpdate frame=80B body=3 params\n" +
            "  [0] remoteID: \"+44 7700 900123\"\n  [1] displayName: \"Alex\"\n  raw-body=00 ff"
        assertEquals("IAP2 RX [wireless] 0x4155 CallStateUpdate frame=80B body=3 params",
            FileLogPolicy.forFile(frame, debugLogs = false))
    }

    @Test fun hardwareAddressesKeepTheLastByte() {
        assertEquals("AirPlay device info deviceId=xx:xx:xx:xx:xx:9f wifiMac=xx:xx:xx:xx:xx:0a",
            FileLogPolicy.forFile("AirPlay device info deviceId=AA:BB:CC:DD:EE:9f wifiMac=aa-bb-cc-dd-ee-0a", debugLogs = false))
        assertEquals("wireless RFCOMM connected address=xx:xx:xx:xx:xx:01",
            FileLogPolicy.forFile("wireless RFCOMM connected address=12:34:56:78:9A:01", debugLogs = false))
    }

    @Test fun bluetoothDeviceNamesAreHidden() {
        assertEquals("wireless Bluetooth bonded name=(hidden) address=xx:xx:xx:xx:xx:01 class=5a020c",
            FileLogPolicy.forFile("wireless Bluetooth bonded name=Alex's iPhone address=12:34:56:78:9A:01 class=5a020c", debugLogs = false))
        assertEquals("wireless RFCOMM attempt=1/1 name=(hidden) address=xx:xx:xx:xx:xx:01 uuid=x",
            FileLogPolicy.forFile("wireless RFCOMM attempt=1/1 name=unknown address=12:34:56:78:9A:01 uuid=x", debugLogs = false))
        assertEquals("video decoder configured name=c2.hevc mime=video/hevc",
            FileLogPolicy.forFile("video decoder configured name=c2.hevc mime=video/hevc", debugLogs = false))
    }

    @Test fun otherLinesAreUnchanged() {
        assertEquals("AirPlay session active", FileLogPolicy.forFile("AirPlay session active", debugLogs = false))
        assertEquals("ipv6 fe80::2:3:4:5:6:7", FileLogPolicy.forFile("ipv6 fe80::2:3:4:5:6:7", debugLogs = false))
    }
}
