package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.content.Context
import android.os.SystemClock

/**
 * Keeps CarPlay music off the head unit's own speaker while the car's Bluetooth audio is still
 * connecting. When the car wakes, the dash and CarPlay are often back a few seconds before the car
 * has connected to the dash as a Bluetooth audio device; music that starts in that gap plays from
 * the dash speaker and then jumps to the car (owner, 5 Nov).
 *
 * So when music starts while it doesn't go to Bluetooth, and the dash is paired with a car audio
 * device (Bluetooth class audio/video, as the Tesla is), music is dropped until the car link is
 * there, for at most [HOLD_MAX_MS]. A dash that isn't paired with a car never holds; calls and
 * Siri are never held.
 */
internal object CarAudioGate {
    private const val HOLD_MAX_MS = 20_000L
    private const val CHECK_EVERY_MS = 250L

    @Volatile private var holdUntil = 0L
    @Volatile private var lastCheck = 0L
    @Volatile private var lastHolding = false
    private var appContext: Context? = null

    /** Music is starting (a new media stream): hold it if the car's audio isn't connected yet. */
    fun onMusicStart(context: Context, log: (String) -> Unit) {
        val app = context.applicationContext
        appContext = app
        if (CarLinkVolume.musicGoesToBluetooth(app) || !pairedWithCarAudio()) {
            holdUntil = 0L
            return
        }
        holdUntil = SystemClock.elapsedRealtime() + HOLD_MAX_MS
        lastCheck = 0L
        log("music held until the car's Bluetooth audio connects (at most ${HOLD_MAX_MS / 1000} s)")
    }

    /** True while music should be dropped. Cheap enough for every audio buffer. */
    fun holding(log: (String) -> Unit): Boolean {
        val until = holdUntil
        if (until == 0L) return false
        val now = SystemClock.elapsedRealtime()
        if (now >= until) {
            holdUntil = 0L
            log("car Bluetooth audio not connected after ${HOLD_MAX_MS / 1000} s; playing on this unit")
            return false
        }
        if (now - lastCheck < CHECK_EVERY_MS) return lastHolding
        lastCheck = now
        val app = appContext ?: return false
        val holding = !CarLinkVolume.musicGoesToBluetooth(app)
        if (!holding) {
            holdUntil = 0L
            log("car Bluetooth audio connected; music released")
        }
        lastHolding = holding
        return holding
    }

    @Suppress("MissingPermission")
    private fun pairedWithCarAudio(): Boolean = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().any {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        }
    }.getOrDefault(false)
}
