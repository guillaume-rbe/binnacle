package com.shilapi.xcertplay.media

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Call-only voice clean-up for head units whose microphone sits in the dash, away from the
 * driver: a high-pass filter that removes road rumble and a fixed boost on top of the user's
 * microphone gain. The MIC source is kept: on a Unisoc head unit VOICE_COMMUNICATION plus the
 * vendor echo canceller made calls about 14 dB quieter (1 Nov test), so [attachEffects] is not
 * used by default.
 */
internal object CallVoiceProcessing {
    private const val TAG = "xcertplay-usb"

    /** Extra call gain (percent) for distant dash microphones: +6 dB. */
    const val CALL_BOOST_PERCENT = 200

    fun audioSourceFor(@Suppress("UNUSED_PARAMETER") audioType: String): Int = MediaRecorder.AudioSource.MIC

    fun isCall(audioType: String): Boolean = audioType.equals("telephony", ignoreCase = true)

    /** Attaches whichever platform voice effects exist; close the result with the recorder. */
    fun attachEffects(recorder: AudioRecord): Closeable {
        val session = recorder.audioSessionId
        val effects = mutableListOf<AudioEffect>()
        fun <T : AudioEffect> tryAttach(name: String, available: Boolean, create: () -> T?) {
            if (!available) {
                Log.i(TAG, "call voice: $name not available")
                return
            }
            try {
                create()?.let {
                    it.enabled = true
                    effects += it
                    Log.i(TAG, "call voice: $name on")
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "call voice: $name failed", error)
            }
        }
        tryAttach("echo canceller", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
        tryAttach("noise suppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
        tryAttach("gain control", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) }
        return Closeable { effects.forEach { runCatching { it.release() } } }
    }
}

/** Second-order Butterworth high-pass for 16-bit mono PCM, applied in place. */
internal class Pcm16HighPass(sampleRate: Int, cutoffHz: Double = 120.0) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / sqrt(2.0) // sin(w0) / (2Q) with Q = 1/sqrt(2)
        val cosW0 = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + cosW0) / 2 / a0
        b1 = -(1 + cosW0) / a0
        b2 = (1 + cosW0) / 2 / a0
        a1 = -2 * cosW0 / a0
        a2 = (1 - alpha) / a0
    }

    fun process(buffer: ByteArray, length: Int) {
        var i = 0
        while (i + 1 < length) {
            val x = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            val out = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = out.toByte()
            buffer[i + 1] = (out shr 8).toByte()
            i += 2
        }
    }
}
