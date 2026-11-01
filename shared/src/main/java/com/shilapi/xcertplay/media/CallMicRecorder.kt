package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic: saves the first [maxSeconds] of a call's microphone audio as raw 16-bit mono PCM,
 * once as captured ("raw") and once as sent after gain/AGC ("sent"), under
 * Android/data/<package>/files/mic/. Lets the captured and sent levels be measured off-device.
 */
internal class CallMicRecorder(context: Context, private val sampleRate: Int, maxSeconds: Int = 60) {
    private val dir = File(context.getExternalFilesDir(null), "mic")
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val limitBytes = sampleRate.toLong() * 2 * maxSeconds
    private var raw: BufferedOutputStream? = open("raw")
    private var sent: BufferedOutputStream? = open("sent")
    private var rawBytes = 0L
    private var sentBytes = 0L

    private fun open(kind: String): BufferedOutputStream? = try {
        dir.mkdirs()
        BufferedOutputStream(FileOutputStream(File(dir, "call-$stamp-${sampleRate}hz-$kind.pcm")))
    } catch (error: Exception) {
        Log.w("xcertplay-usb", "call mic recorder unavailable", error)
        null
    }

    fun raw(buffer: ByteArray, length: Int) {
        if (rawBytes >= limitBytes) return
        runCatching { raw?.write(buffer, 0, length) }
        rawBytes += length
    }

    fun sent(buffer: ByteArray, length: Int) {
        if (sentBytes >= limitBytes) return
        runCatching { sent?.write(buffer, 0, length) }
        sentBytes += length
    }

    fun close() {
        runCatching { raw?.close() }
        runCatching { sent?.close() }
        raw = null
        sent = null
    }
}
