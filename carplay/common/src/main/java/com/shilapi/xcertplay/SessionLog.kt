package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import com.shilapi.xcertplay.airplay.VideoCodec

/** Process-owned log sink shared by Activities and the background CarPlay session. */
internal class SessionLog : Closeable {
    private val lock = Any()
    @Volatile private var activeLog: SessionLogFile? = null
    @Volatile var videoFramesEnabled = false

    fun start(file: File, header: String, onError: (String) -> Unit = {}) {
        synchronized(lock) {
            if (activeLog != null) return
            keepPreviousLogs(file)
            val next = SessionLogFile(file, onError = onError)
            activeLog = next
            next.reset(header)
        }
    }

    fun append(line: String) {
        synchronized(lock) { activeLog?.append(line) }
    }

    fun appendTimestamped(message: String, timestampMillis: Long) {
        synchronized(lock) { activeLog?.appendTimestamped(message, timestampMillis) }
    }

    fun appendVideoPacket(
        type: Int, codec: VideoCodec, sequence: Long,
        header: ByteArray, wire: ByteArray, plain: ByteArray?,
    ) = appendVideoPacket(type, codec, sequence, header, wire, wire.size, plain, plain?.size ?: 0)

    fun appendVideoPacket(
        type: Int, codec: VideoCodec, sequence: Long,
        header: ByteArray, wire: ByteArray, wireLength: Int,
        plain: ByteArray?, plainLength: Int,
    ) {
        if (!videoFramesEnabled) return
        activeLog?.appendVideoPacket(
            type, codec.name, sequence, header, wire, wireLength, plain, plainLength,
        )
    }

    /**
     * Each app start begins a new log; keep the previous ones as xcertplay.1.log (newest) up to
     * .[KEPT_LOGS].log, so a restart (car sleep, crash) doesn't erase the drive before it.
     */
    private fun keepPreviousLogs(file: File) {
        if (!file.exists()) return
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        fun numbered(n: Int) = File(file.parentFile, "$stem.$n$ext")
        numbered(KEPT_LOGS).delete()
        for (n in KEPT_LOGS - 1 downTo 1) numbered(n).takeIf { it.exists() }?.renameTo(numbered(n + 1))
        file.renameTo(numbered(1))
    }

    override fun close() {
        synchronized(lock) {
            activeLog?.close()
            activeLog = null
        }
    }

    private companion object {
        const val KEPT_LOGS = 5
    }
}
