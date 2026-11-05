package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Undoes a music volume left at zero on the head unit's Bluetooth link to the car.
 *
 * On the tested head unit the stock CAN app sets music volume to zero when the car goes to sleep,
 * and on wake restores it only for the output active then (the built-in speaker). When the car
 * later connects as a Bluetooth audio device, its music volume is still zero, so CarPlay music
 * plays silently (3 Nov). When CarPlay music starts over Bluetooth at zero, it is put back to
 * full: the car sets the loudness, and anything less on this link only makes the head unit quieter
 * than the car's other sources (4 Nov).
 */
internal object CarLinkVolume {
    fun onMusicStart(context: Context, log: (String) -> Unit) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        if (!musicGoesToBluetooth(audio)) return
        // the stock CAN app also mutes music (ADJUST_MUTE) when the car sleeps; a level set while
        // muted stays silent (drive 5 Nov)
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        if (!muted && audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val restored = if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) else max
        try {
            if (muted) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                log("car link music was muted; unmuted")
            }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restored, 0)
            log("car link music volume was 0; restored to $restored/$max")
        } catch (error: SecurityException) {
            log("car link music volume was 0; could not restore: ${error.message}")
        }
    }

    /** Whether music output goes to a Bluetooth audio device (the car link) right now. */
    fun musicGoesToBluetooth(context: Context): Boolean {
        val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return false
        return musicGoesToBluetooth(audio)
    }

    private fun musicGoesToBluetooth(audio: AudioManager): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } &&
            @Suppress("DEPRECATION") audio.isBluetoothA2dpOn
}
