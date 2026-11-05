package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Size caps on the control channel's framing (audit 5 Nov, X3): no unbounded buffering. */
class RtspMessageLimitsTest {
    @Test fun parsesANormalRequest() {
        val parsed = RtspMessage.parseMessages(ascii("GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 2\r\n\r\nhi"))
        assertEquals(1, parsed.messages.size)
        assertEquals("/info", parsed.messages[0].path)
        assertEquals(0, parsed.rest.size)
    }

    @Test fun keepsAPartialBodyForLater() {
        val parsed = RtspMessage.parseMessages(ascii("POST /x RTSP/1.0\r\nContent-Length: 10\r\n\r\nabc"))
        assertEquals(0, parsed.messages.size)
        assertEquals(ascii("POST /x RTSP/1.0\r\nContent-Length: 10\r\n\r\nabc").size, parsed.rest.size)
    }

    @Test fun refusesAnOversizedContentLength() {
        assertThrows(IllegalArgumentException::class.java) {
            RtspMessage.parseMessages(ascii("POST /x RTSP/1.0\r\nContent-Length: 2000000000\r\n\r\n"))
        }
    }

    @Test fun refusesAHeaderThatNeverEnds() {
        val endless = ascii("GET /info RTSP/1.0\r\nX: " + "a".repeat(RtspMessage.MAX_HEADER_BYTES))
        assertThrows(IllegalArgumentException::class.java) { RtspMessage.parseMessages(endless) }
    }

    @Test fun acceptsABodyAtTheCap() {
        val head = ascii("POST /x RTSP/1.0\r\nContent-Length: ${RtspMessage.MAX_BODY_BYTES}\r\n\r\n")
        val parsed = RtspMessage.parseMessages(head + ByteArray(RtspMessage.MAX_BODY_BYTES))
        assertEquals(RtspMessage.MAX_BODY_BYTES, parsed.messages.single().body.size)
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
}
