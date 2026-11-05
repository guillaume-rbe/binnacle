package com.shilapi.xcertplay.media

import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import java.io.Closeable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Call-only voice clean-up for head units whose microphone sits in the dash, away from the
 * driver: a high-pass filter that removes road rumble ([Pcm16HighPass]), automatic gain
 * ([Pcm16VoiceAgc]) on top of the user's microphone gain, and the echo suppressor
 * ([CallEchoSuppressor]). The platform echo canceller is attached too, though on the Unisoc test
 * unit it made no measurable difference. The MIC source is kept: VOICE_COMMUNICATION made calls
 * much quieter there (1 Nov test).
 */
internal object CallVoiceProcessing {
    private const val TAG = "xcertplay-usb"

    /** Calls and Siri both record from MIC (see the class comment). */
    const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    fun isCall(audioType: String): Boolean = audioType.equals("telephony", ignoreCase = true)

    /**
     * Attaches only the platform echo canceller (the mic hears the car speakers as loudly as the
     * driver). Keeps the MIC source and the rest of the call path unchanged.
     */
    fun attachEchoCanceller(recorder: AudioRecord): Closeable {
        val session = recorder.audioSessionId
        if (!AcousticEchoCanceler.isAvailable()) {
            Log.i(TAG, "call voice: echo canceller not available")
            return Closeable {}
        }
        val aec = try {
            AcousticEchoCanceler.create(session)?.also {
                it.enabled = true
                Log.i(TAG, "call voice: echo canceller on (enabled=${it.enabled})")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "call voice: echo canceller failed", error)
            null
        }
        return Closeable { runCatching { aec?.release() } }
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

/**
 * Gain and echo suppression for one microphone block, in place. The suppressor decides from the
 * captured level; while it is suppressing, the user gain and AGC are skipped so they can't lift the
 * echo back up (2 Nov: the AGC's held +20 dB undid most of the suppression). Without a suppressor
 * (Siri) this is just the user gain and, if given, the AGC.
 */
internal fun applyCallGain(
    buffer: ByteArray,
    count: Int,
    userGainPercent: Int,
    agc: Pcm16VoiceAgc?,
    suppressor: CallEchoSuppressor?,
) {
    suppressor?.update(buffer, count)
    if (suppressor?.suppressing != true) {
        MicrophoneGain.applyPcm16InPlace(buffer, count, userGainPercent)
        agc?.process(buffer, count)
    }
    suppressor?.apply(buffer, count)
}
