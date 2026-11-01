package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioManager
import com.shilapi.xcertplay.telecom.CarPlayTelecom

/**
 * Sends CarPlay call audio to a car that is connected to this head unit's Android Bluetooth as a
 * hands-free device (the head unit acting as the car's phone). The call then uses the car's own
 * microphone, echo cancelling and call screen instead of the head unit's speaker and mic.
 *
 * It uses Android's Bluetooth SCO link for communication audio: the phone-call playback stream
 * (voice communication) and the microphone follow it while communication mode is on. When the
 * call is mirrored into Telecom ([CarPlayTelecom]), Telecom opens the link; otherwise this starts
 * it as a plain audio link, which the car shows as a call without name or working buttons.
 * Without a connected hands-free car nothing changes.
 */
internal object BluetoothCallRoute {
    /** True while a call's audio is meant to go to the car; the dash-mic clean-up is skipped then. */
    @Volatile
    var active: Boolean = false
        private set
    private var usedSco = false

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
        // With the call mirrored into Telecom, Telecom connects the car's audio link itself; a
        // second (virtual) call from here would be refused. The mic still follows the link.
        val telecomCall = CarPlayTelecom.ownsCallAudio()
        return try {
            if (!telecomCall) audio.startBluetoothSco()
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            usedSco = !telecomCall
            active = true
            log("call to car: audio over Bluetooth hands-free${if (telecomCall) " (Telecom call)" else ""}")
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
            if (usedSco) audio.stopBluetoothSco()
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
