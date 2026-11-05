package com.shilapi.xcertplay.media

import android.os.SystemClock

import kotlin.math.log10
import kotlin.math.pow

/**
 * Level of the audio xcertplay is playing (the far end of a call, prompts, media), recorded as
 * it is written to the AudioTracks. Used as the echo reference: on head units whose microphone
 * hears the car speakers, whatever we play comes straight back into the call.
 */
internal object FarEndActivity {
    private const val SLOTS = 256 // up to ~1 s queued ahead plus the window, at 20 ms writes
    private val times = LongArray(SLOTS)
    private val levels = DoubleArray(SLOTS) { SILENT_DB }
    private var next = 0

    const val SILENT_DB = -120.0

    /** 16-bit interleaved PCM just written for playback. */
    @Synchronized
    fun report(data: ByteArray, offset: Int, length: Int, nowMs: Long = SystemClock.elapsedRealtime()) {
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

    /**
     * Loudest playback heard in the last [windowMs]; [SILENT_DB] if nothing played. Chunks are
     * stamped with when they will be heard (they can be queued up to 1.5 s ahead), so chunks not
     * yet heard don't count.
     */
    @Synchronized
    fun recentMaxDb(windowMs: Long, nowMs: Long = SystemClock.elapsedRealtime()): Double {
        var max = SILENT_DB
        for (i in 0 until SLOTS) {
            val age = nowMs - times[i]
            if (age in 0..windowMs && levels[i] > max) max = levels[i]
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
 * Half-duplex: while the far end is playing (any playback above [farActiveDb] in the last
 * [windowMs]), the microphone is attenuated by [suppressDb], unless it is clearly louder than the
 * playback ([doubleTalkMarginDb]). On the test unit the echo reached the mic about as loud as the
 * driver's voice, so level-based echo/speech separation (learned coupling) did not work reliably.
 * Gain moves down fast and back up slowly so word endings aren't chopped.
 *
 * In the call chain, [update] runs on the captured level (before any gain) and [apply] runs last,
 * after the user gain and AGC: applied earlier, the AGC (holding a high gain for a quiet driver)
 * lifted the suppressed echo back up by ~20 dB and the far end heard it (2 Nov test).
 */
internal class CallEchoSuppressor(
    private val farEndLevel: (windowMs: Long) -> Double = { FarEndActivity.recentMaxDb(it) },
    private val farActiveDb: Double = -55.0,
    private val doubleTalkMarginDb: Double = 8.0,
    private val suppressDb: Double = -45.0,
    private val windowMs: Long = 600,
    /** Called about once a second with the current levels, for diagnostics. */
    private val log: (String) -> Unit = {},
) {
    private var blocks = 0
    private var gainDb = 0.0

    /** True when the last block was being suppressed (lets AGC hold its gain). */
    var suppressing = false
        private set

    /** Decide the suppression for this block from its captured (pre-gain) level. */
    fun update(buffer: ByteArray, length: Int) {
        if (length / 2 == 0) return
        val micDb = rmsDb(buffer, length)
        val farDb = farEndLevel(windowMs)
        val target = if (farDb > farActiveDb && micDb < farDb + doubleTalkMarginDb) suppressDb else 0.0
        if (++blocks % 50 == 0) {
            log("call echo far=%.0f mic=%.0f gain=%.0f".format(farDb, micDb, gainDb))
        }
        gainDb = if (target < gainDb) {
            maxOf(target, gainDb - 15.0) // fast down
        } else {
            minOf(target, gainDb + 2.0) // slow up
        }
        suppressing = gainDb < -6.0
    }

    /** Apply the current suppression; call last, after every gain stage. */
    fun apply(buffer: ByteArray, length: Int) {
        if (gainDb > -0.01) return
        val gain = 10.0.pow(gainDb / 20)
        for (i in 0 until length / 2) {
            val o = i * 2
            val s = ((buffer[o + 1].toInt() shl 8) or (buffer[o].toInt() and 0xff)).toShort()
            val out = (s * gain).toInt()
            buffer[o] = out.toByte()
            buffer[o + 1] = (out shr 8).toByte()
        }
    }

    /** [update] then [apply] on the same block (no gain stages in between). */
    fun process(buffer: ByteArray, length: Int) {
        update(buffer, length)
        apply(buffer, length)
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
