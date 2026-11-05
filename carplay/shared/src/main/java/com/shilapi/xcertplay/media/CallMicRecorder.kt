package com.shilapi.xcertplay.media

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in diagnostic (only when "Audio diagnostic capture" is on): saves the first [maxSeconds] of
 * a call's microphone audio as raw 16-bit mono PCM, once as captured ("raw") and once as sent
 * after gain/AGC ("sent"), in `<captureDirectory>/mic/`. Only the newest [keepCalls] calls are
 * kept; older recordings are deleted when a new one starts.
 */
internal class CallMicRecorder(
    captureDirectory: File,
    private val sampleRate: Int,
    maxSeconds: Int = 60,
    keepCalls: Int = 3,
) {
    private val dir = File(captureDirectory, "mic")
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val limitBytes = sampleRate.toLong() * 2 * maxSeconds
    private var raw: BufferedOutputStream?
    private var sent: BufferedOutputStream?
    private var rawBytes = 0L
    private var sentBytes = 0L

    init {
        prune(keepCalls - 1)
        raw = open("raw")
        sent = open("sent")
    }

    /** Keeps the newest [keep] calls' files (two per call). */
    private fun prune(keep: Int) {
        val files = dir.listFiles { file -> file.name.startsWith("call-") && file.name.endsWith(".pcm") }
            ?: return
        val calls = files.groupBy { it.name.substringBeforeLast('-') }.toSortedMap()
        calls.keys.toList().dropLast(keep.coerceAtLeast(0)).forEach { key ->
            calls[key]?.forEach { it.delete() }
        }
    }

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
