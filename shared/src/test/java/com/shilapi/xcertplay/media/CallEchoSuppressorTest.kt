package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CallEchoSuppressorTest {
    @Before
    fun clearReference() {
        FarEndActivity.reset()
    }

    @Test
    fun playbackCountsOnlyOnceHeardAndForTheWindow() {
        // Chunk stamped to be heard at t=1800 (queued ahead of playback).
        val loud = tone(0.5)
        FarEndActivity.report(loud, 0, loud.size, nowMs = 1800)
        assertEquals(FarEndActivity.SILENT_DB, FarEndActivity.recentMaxDb(600, nowMs = 1000), 0.0)
        assertTrue(FarEndActivity.recentMaxDb(600, nowMs = 1900) > -20)
        assertEquals(FarEndActivity.SILENT_DB, FarEndActivity.recentMaxDb(600, nowMs = 2500), 0.0)
    }

    private fun tone(amplitude: Double, samples: Int = 320): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * 32767 * sin(2 * PI * 300 * i / 16000)).toInt()
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun rmsDb(buffer: ByteArray): Double {
        var sum = 0.0
        val n = buffer.size / 2
        for (i in 0 until n) {
            val s = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xff)).toShort() / 32768.0
            sum += s * s
        }
        return 10 * log10(sum / n + 1e-12)
    }

    @Test
    fun echoWhileFarEndPlaysIsSuppressed() {
        // As measured on the unit: playback about -12 dBFS, echo at the mic about -32 dBFS.
        val s = CallEchoSuppressor(farEndLevel = { -12.0 })
        var last = 0.0
        repeat(20) {
            val block = tone(0.035)
            s.process(block, block.size)
            last = rmsDb(block)
        }
        assertTrue("echo should drop well below -32 dBFS, was $last", last < -55)
        assertTrue(s.suppressing)
    }

    @Test
    fun silentMicBlocksDoNotStopSuppression() {
        val s = CallEchoSuppressor(farEndLevel = { -12.0 })
        val levels = mutableListOf<Double>()
        repeat(200) { i ->
            val echo = i % 3 != 0
            val block = if (echo) tone(0.035) else tone(0.00003)
            s.process(block, block.size)
            if (echo && i > 10) levels += rmsDb(block)
        }
        assertTrue("echo suppressed, max ${levels.max()}", levels.max() < -50)
    }

    @Test
    fun driverMuchLouderThanPlaybackPasses() {
        val s = CallEchoSuppressor(farEndLevel = { -25.0 })
        var last = 0.0
        repeat(50) {
            val block = tone(0.5) // about -9 dBFS, well above playback + margin
            s.process(block, block.size)
            last = rmsDb(block)
        }
        assertEquals(rmsDb(tone(0.5)), last, 1.0)
        assertFalse(s.suppressing)
    }

    @Test
    fun agcCannotLiftSuppressedEchoInTheCallChain() {
        // The same chain MicrophoneUplink uses.
        var far = FarEndActivity.SILENT_DB
        val s = CallEchoSuppressor(farEndLevel = { far })
        val agc = Pcm16VoiceAgc()
        fun chain(block: ByteArray) = applyCallGain(block, block.size, MicrophoneGain.DEFAULT_PERCENT, agc, s)
        repeat(200) { chain(tone(0.01)) } // quiet driver: AGC climbs to about +20 dB
        assertTrue("AGC should be boosting, was ${agc.currentGainDb}", agc.currentGainDb > 15)
        far = -12.0
        var last = 0.0
        val echo = tone(0.035) // about -32 dBFS at the mic
        repeat(20) {
            val block = echo.copyOf()
            chain(block)
            last = rmsDb(block)
        }
        assertTrue("echo must go out well below its captured level, was $last", last < rmsDb(echo) - 25)
    }

    @Test
    fun noFarEndMeansNoSuppression() {
        val s = CallEchoSuppressor(farEndLevel = { FarEndActivity.SILENT_DB })
        val block = tone(0.035)
        val before = rmsDb(block)
        s.process(block, block.size)
        assertEquals(before, rmsDb(block), 0.01)
    }
}
