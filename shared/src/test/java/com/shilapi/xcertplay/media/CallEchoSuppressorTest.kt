package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEchoSuppressorTest {
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
    fun echoOnlyIsSuppressed() {
        // Far end playing at about -20 dBFS; mic hears it at about -35 dBFS.
        val s = CallEchoSuppressor(farEndLevel = { -20.0 })
        var last = 0.0
        repeat(100) {
            val block = tone(0.025)
            s.process(block, block.size)
            last = rmsDb(block)
        }
        assertTrue("echo should drop well below -35 dBFS, was $last", last < -55)
        assertTrue(s.suppressing)
    }

    @Test
    fun driverTalkingOverFarEndPasses() {
        val s = CallEchoSuppressor(farEndLevel = { -20.0 })
        repeat(100) { val b = tone(0.025); s.process(b, b.size) } // learn coupling on echo
        var last = 0.0
        repeat(50) {
            val block = tone(0.2) // driver much louder than the echo
            s.process(block, block.size)
            last = rmsDb(block)
        }
        assertEquals(rmsDb(tone(0.2)), last, 1.0)
        assertFalse(s.suppressing)
    }

    @Test
    fun noFarEndMeansNoSuppression() {
        val s = CallEchoSuppressor(farEndLevel = { FarEndActivity.SILENT_DB })
        val block = tone(0.025)
        val before = rmsDb(block)
        s.process(block, block.size)
        assertEquals(before, rmsDb(block), 0.01)
    }
}
