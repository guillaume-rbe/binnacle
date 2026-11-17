package com.shilapi.xcertplay

/**
 * What the session log file keeps of a debug message. The file is in shared storage and users
 * attach it to public issues, so with Debug logs off it holds no personal data:
 * - `TRACE ` lines (decrypted control traffic) are left out;
 * - iAP2 frames keep their header line only (direction, message name, sizes): their bodies carry
 *   callers' numbers and names, numbers dialled, now playing, Bluetooth devices and location;
 * - hardware addresses (Bluetooth, Wi-Fi, device IDs) keep only their last byte.
 * With Debug logs on, everything is kept as it is.
 */
internal object FileLogPolicy {
    private val HARDWARE_ADDRESS = Regex("""(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})(?![0-9A-Fa-f])""")

    fun forFile(message: String, debugLogs: Boolean): String? {
        if (debugLogs) return message
        if (message.startsWith("TRACE ")) return null
        val kept = if (message.startsWith("IAP2 ")) message.substringBefore('\n') else message
        return maskAddresses(kept)
    }

    fun maskAddresses(text: String): String =
        HARDWARE_ADDRESS.replace(text) { "xx:xx:xx:xx:xx:${it.groupValues[1]}" }
}
