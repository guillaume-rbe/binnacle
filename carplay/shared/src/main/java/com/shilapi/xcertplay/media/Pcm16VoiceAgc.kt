package com.shilapi.xcertplay.media

import kotlin.math.log10
import kotlin.math.pow

/**
 * Simple automatic gain control for 16-bit mono call audio from a distant microphone.
 *
 * Each block's RMS is measured; when it is speech-like (above [gateDb]) the gain moves toward the
 * value that brings it to [targetDb], rising slowly and falling fast. Quiet blocks (pauses,
 * background hiss) keep the current gain instead of being pulled up, so noise isn't amplified
 * between words. Output is limited to avoid clipping.
 */
internal class Pcm16VoiceAgc(
    private val targetDb: Double = -20.0,
    private val gateDb: Double = -55.0,
    private val maxGainDb: Double = 30.0,
    private val riseDbPerBlock: Double = 0.5,
    private val fallDbPerBlock: Double = 3.0,
) {
    private var gainDb = 12.0

    val currentGainDb: Double get() = gainDb

    /** [hold] keeps the current gain (e.g. while echo is being suppressed). */
    fun process(buffer: ByteArray, length: Int, hold: Boolean = false) {
        val samples = length / 2
        if (samples == 0) return
        var sum = 0.0
        for (i in 0 until samples) {
            val s = sample(buffer, i * 2) / 32768.0
            sum += s * s
        }
        val rmsDb = 10 * log10(sum / samples + 1e-12)
        if (!hold && rmsDb > gateDb) {
            val wanted = (targetDb - rmsDb).coerceIn(0.0, maxGainDb)
            gainDb = if (wanted > gainDb) {
                minOf(wanted, gainDb + riseDbPerBlock)
            } else {
                maxOf(wanted, gainDb - fallDbPerBlock)
            }
        }
        val gain = 10.0.pow(gainDb / 20)
        var peak = 0.0
        for (i in 0 until samples) peak = maxOf(peak, kotlin.math.abs(sample(buffer, i * 2) * gain))
        // Limiter: scale the whole block down if it would clip.
        val scale = if (peak > LIMIT) gain * LIMIT / peak else gain
        for (i in 0 until samples) {
            val out = (sample(buffer, i * 2) * scale).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i * 2] = out.toByte()
            buffer[i * 2 + 1] = (out shr 8).toByte()
        }
    }

    private fun sample(buffer: ByteArray, offset: Int): Double =
        ((buffer[offset + 1].toInt() shl 8) or (buffer[offset].toInt() and 0xff)).toShort().toDouble()

    private companion object {
        const val LIMIT = 30000.0
    }
}
