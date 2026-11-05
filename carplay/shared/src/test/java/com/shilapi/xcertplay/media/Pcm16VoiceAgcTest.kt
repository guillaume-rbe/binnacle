package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16VoiceAgcTest {
    private fun tone(amplitude: Double, samples: Int = 320): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (amplitude * 32767 * sin(2 * PI * 440 * i / 16000)).toInt()
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
    fun quietSpeechIsRaisedTowardTarget() {
        val agc = Pcm16VoiceAgc()
        var last = 0.0
        repeat(200) {
            val block = tone(0.01) // about -43 dBFS
            agc.process(block, block.size)
            last = rmsDb(block)
        }
        assertEquals(-20.0, last, 1.5)
    }

    @Test
    fun silenceIsNotPulledUp() {
        val agc = Pcm16VoiceAgc()
        val before = agc.currentGainDb
        repeat(200) {
            val block = tone(0.0001) // about -83 dBFS, below the gate
            agc.process(block, block.size)
        }
        assertEquals(before, agc.currentGainDb, 0.0)
    }

    @Test
    fun loudSpeechDoesNotClip() {
        val agc = Pcm16VoiceAgc()
        val block = tone(0.9)
        agc.process(block, block.size)
        assertTrue(rmsDb(block) < -1.0)
    }
}
