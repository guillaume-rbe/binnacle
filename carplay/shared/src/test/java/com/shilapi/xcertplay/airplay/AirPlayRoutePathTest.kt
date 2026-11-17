package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayRoutePathTest {
    @Test
    fun queryAndFragmentAreCut() {
        assertEquals("/info", airPlayRoutePath("/info?/command"))
        assertEquals("/info", airPlayRoutePath("/info#/feedback"))
        assertEquals("/info", airPlayRoutePath("/INFO?x=1"))
    }

    @Test
    fun plainPathsAreKept() {
        assertEquals("/command", airPlayRoutePath("/command"))
        assertEquals("rtsp://192.168.49.1/1234", airPlayRoutePath("rtsp://192.168.49.1/1234"))
    }
}
