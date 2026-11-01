package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager

/**
 * Sends CarPlay call audio to a car that is connected to this head unit's Android Bluetooth as a
 * hands-free device (the head unit acting as the car's phone). The call then uses the car's own
 * microphone, echo cancelling and call screen instead of the head unit's speaker and mic.
 *
 * It uses Android's Bluetooth SCO link for communication audio: the phone-call playback stream
 * (voice communication) and the microphone follow it while communication mode is on. Android
 * shows the car a call while the link is up. Without a connected hands-free car nothing changes.
 */
internal object BluetoothCallRoute {
    /** True while a call's audio is meant to go to the car; the dash-mic clean-up is skipped then. */
    @Volatile
    var active: Boolean = false
        private set

    /** Starts the car route; false when no hands-free car is connected (or Bluetooth is off). */
    @Synchronized
    fun start(context: Context, log: (String) -> Unit): Boolean {
        if (active) return true
        if (!handsFreeCarConnected()) return false
        val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return false
        if (!audio.isBluetoothScoAvailableOffCall) {
            log("call to car: Bluetooth SCO not available")
            return false
        }
        return try {
            audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            active = true
            log("call to car: audio over Bluetooth hands-free")
            true
        } catch (error: RuntimeException) {
            log("call to car: could not start Bluetooth SCO: ${error.message}")
            false
        }
    }

    @Synchronized
    fun stop(context: Context, log: (String) -> Unit) {
        if (!active) return
        active = false
        val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
            audio.stopBluetoothSco()
            log("call to car: ended")
        } catch (error: RuntimeException) {
            log("call to car: could not stop Bluetooth SCO: ${error.message}")
        }
    }

    private fun handsFreeCarConnected(): Boolean = try {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        adapter != null && adapter.isEnabled &&
            adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
    } catch (_: SecurityException) {
        false
    }
}
