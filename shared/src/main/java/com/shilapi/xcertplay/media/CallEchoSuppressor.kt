package com.shilapi.xcertplay.media

import kotlin.math.log10
import kotlin.math.pow

/**
 * Level of the audio xcertplay is playing (the far end of a call, prompts, media), recorded as
 * it is written to the AudioTracks. Used as the echo reference: on head units whose microphone
 * hears the car speakers, whatever we play comes straight back into the call.
 */
internal object FarEndActivity {
    private const val SLOTS = 64
    private val times = LongArray(SLOTS)
    private val levels = DoubleArray(SLOTS) { SILENT_DB }
    private var next = 0

    const val SILENT_DB = -120.0

    /** 16-bit interleaved PCM just written for playback. */
    @Synchronized
    fun report(data: ByteArray, offset: Int, length: Int, nowMs: Long = System.currentTimeMillis()) {
        val samples = length / 2
        if (samples == 0) return
        var sum = 0.0
        var i = 0
        while (i < samples) {
            val o = offset + i * 2
            val s = ((data[o + 1].toInt() shl 8) or (data[o].toInt() and 0xff)).toShort() / 32768.0
            sum += s * s
            i++
        }
        times[next] = nowMs
        levels[next] = 10 * log10(sum / samples + 1e-12)
        next = (next + 1) % SLOTS
    }

    /** Loudest playback level in the last [windowMs]; [SILENT_DB] if nothing played. */
    @Synchronized
    fun recentMaxDb(windowMs: Long, nowMs: Long = System.currentTimeMillis()): Double {
        var max = SILENT_DB
        for (i in 0 until SLOTS) {
            if (nowMs - times[i] <= windowMs && levels[i] > max) max = levels[i]
        }
        return max
    }

    @Synchronized
    fun reset() {
        times.fill(0)
        levels.fill(SILENT_DB)
    }
}

/**
 * Echo suppressor for call microphone audio (16-bit mono PCM, processed in place).
 *
 * While the far end is playing, the expected echo level is estimated from the playback level plus
 * a learned speaker-to-mic coupling. Microphone blocks that aren't clearly louder than that echo
 * estimate are attenuated by [suppressDb]; when the driver talks over the far end (double talk) the
 * microphone passes. Gain moves down fast and back up slowly so word endings aren't chopped.
 */
internal class CallEchoSuppressor(
    private val farEndLevel: (windowMs: Long) -> Double = { FarEndActivity.recentMaxDb(it) },
    private val farActiveDb: Double = -55.0,
    private val doubleTalkMarginDb: Double = 8.0,
    private val suppressDb: Double = -30.0,
    private val windowMs: Long = 600,
    /** Called about once a second with the current levels, for diagnostics. */
    private val log: (String) -> Unit = {},
) {
    /**
     * Learned mic level minus playback level during far-end-only speech. The car amplifies the
     * playback, so the echo at the mic can be louder than the digital playback level: start high
     * (assume a loud echo) and learn down quickly, up slowly.
     */
    var couplingDb = 20.0
        private set
    private var blocks = 0
    private var gainDb = 0.0

    /** True when the last block was being suppressed (lets AGC hold its gain). */
    var suppressing = false
        private set

    fun process(buffer: ByteArray, length: Int) {
        val samples = length / 2
        if (samples == 0) return
        val micDb = rmsDb(buffer, length)
        val farDb = farEndLevel(windowMs)
        val farActive = farDb > farActiveDb
        var target = 0.0
        if (farActive) {
            val diff = micDb - farDb
            // Learn the coupling from blocks that look like echo: fall quickly toward quieter
            // echo, rise slowly (rising fast would mistake the driver talking for echo).
            // Near-silent mic blocks (the capture path drops out now and then) say nothing about
            // the echo; learning from them dragged the estimate to -75 dB on the unit.
            if (micDb > MIN_LEARN_MIC_DB) {
                if (diff < couplingDb) {
                    couplingDb += 0.1 * (diff - couplingDb)
                } else if (diff < couplingDb + doubleTalkMarginDb) {
                    couplingDb += 0.01 * (diff - couplingDb)
                }
                couplingDb = couplingDb.coerceIn(MIN_COUPLING_DB, MAX_COUPLING_DB)
            }
            val echoDb = farDb + couplingDb
            if (micDb < echoDb + doubleTalkMarginDb) target = suppressDb
        }
        if (++blocks % 50 == 0) {
            log("call echo far=%.0f mic=%.0f coupling=%.1f gain=%.0f".format(farDb, micDb, couplingDb, gainDb))
        }
        gainDb = if (target < gainDb) {
            maxOf(target, gainDb - 10.0) // fast down
        } else {
            minOf(target, gainDb + 1.5) // slow up
        }
        suppressing = gainDb < -6.0
        if (gainDb > -0.01) return
        val gain = 10.0.pow(gainDb / 20)
        for (i in 0 until samples) {
            val o = i * 2
            val s = ((buffer[o + 1].toInt() shl 8) or (buffer[o].toInt() and 0xff)).toShort()
            val out = (s * gain).toInt()
            buffer[o] = out.toByte()
            buffer[o + 1] = (out shr 8).toByte()
        }
    }

    private companion object {
        const val MIN_LEARN_MIC_DB = -65.0
        const val MIN_COUPLING_DB = -40.0
        const val MAX_COUPLING_DB = 30.0
    }

    private fun rmsDb(buffer: ByteArray, length: Int): Double {
        val samples = length / 2
        var sum = 0.0
        for (i in 0 until samples) {
            val s = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xff)).toShort() / 32768.0
            sum += s * s
        }
        return 10 * log10(sum / samples + 1e-12)
    }
}
