package com.shilapi.xcertplay.media

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.telecom.CarPlayTelecom

/**
 * Sends CarPlay call audio to a car that is connected to this head unit's Android Bluetooth as a
 * hands-free device (the head unit acting as the car's phone). The call then uses the car's own
 * microphone, echo cancelling and call screen instead of the head unit's speaker and mic.
 *
 * It uses Android's Bluetooth SCO link for communication audio: the phone-call playback stream
 * (voice communication) and the microphone follow it while communication mode is on. When the
 * call is mirrored into Telecom ([CarPlayTelecom]), Telecom opens the link; otherwise this starts
 * it (on this unit Android then shows the car a call, without name or working buttons).
 * Without a connected hands-free car nothing changes.
 *
 * [active] only turns on once the car's audio link is actually up. If the link this route started
 * never comes up, or the link drops during the call, the route gives up and calls its fallback.
 */
internal object BluetoothCallRoute {
    /** True while the call's audio is on the car's link; the dash-mic clean-up is skipped then. */
    @Volatile
    var active: Boolean = false
        private set

    private const val CONNECT_TIMEOUT_MILLIS = 5_000L
    private const val TELECOM_WAIT_MILLIS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private var routing = false
    private var usedSco = false
    private var linkSeen = false
    private var appContext: Context? = null
    private var log: (String) -> Unit = {}
    private var onFallback: (() -> Unit)? = null
    private var receiver: BroadcastReceiver? = null
    private val connectTimeout = Runnable { fail("car audio link did not connect") }
    private val telecomCheck = Runnable { telecomGaveUp() }

    init {
        CarPlayTelecom.onAudioOwnershipLost = ::telecomGaveUp
    }

    /** Telecom couldn't take the call after this route left the link to it: open it here. */
    private fun telecomGaveUp() {
        synchronized(this) {
            if (!routing || usedSco || active || CarPlayTelecom.ownsCallAudio()) return
            val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
            try {
                audio.startBluetoothSco()
                usedSco = true
                handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MILLIS)
                log("call to car: Telecom refused the call, starting the link here")
                return
            } catch (error: RuntimeException) {
                log("call to car: could not start Bluetooth SCO: ${error.message}")
            }
        }
        fail("car audio link not available")
    }

    /**
     * Starts the car route; false when no hands-free car is connected (or Bluetooth is off).
     * [onFallback] runs if the route later gives up while the call is still on.
     */
    @Synchronized
    fun start(context: Context, log: (String) -> Unit, onFallback: () -> Unit): Boolean {
        if (routing) return true
        if (!handsFreeCarConnected()) return false
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        if (!audio.isBluetoothScoAvailableOffCall) {
            log("call to car: Bluetooth SCO not available")
            return false
        }
        // With the call mirrored into Telecom, Telecom connects the car's audio link itself (only
        // once the call is active); a second, virtual call from here would be refused.
        // The phone's call update usually comes just before this; if not yet, give Telecom a
        // moment (3 Nov: the update came 50 ms later and both paths opened a link).
        val telecomCall = CarPlayTelecom.ownsCallAudio()
        val telecomPending = !telecomCall && CarPlayTelecom.enabled()
        return try {
            appContext = app
            this.log = log
            this.onFallback = onFallback
            linkSeen = false
            registerReceiver(app)
            // Telecom may have connected the link before this call (no new event then).
            val sco = app.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            val scoUp = sco?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
            if (!telecomCall && !telecomPending) {
                audio.startBluetoothSco()
                handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MILLIS)
            }
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = true
            usedSco = !telecomCall && !telecomPending
            routing = true
            log("call to car: starting${if (telecomCall || telecomPending) " (Telecom call)" else ""}")
            if (telecomPending) handler.postDelayed(telecomCheck, TELECOM_WAIT_MILLIS)
            if (scoUp) handler.post { onLinkState(BluetoothHeadset.STATE_AUDIO_CONNECTED) }
            true
        } catch (error: RuntimeException) {
            log("call to car: could not start Bluetooth SCO: ${error.message}")
            release()
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!routing) return
        release()
        log("call to car: ended")
    }

    private fun onLinkState(state: Int) {
        // fail() runs the fallback, which must happen outside this lock (see fail()).
        val lost = synchronized(this) {
            if (!routing) return
            when (state) {
                BluetoothHeadset.STATE_AUDIO_CONNECTED -> {
                    handler.removeCallbacks(connectTimeout)
                    linkSeen = true
                    if (!active) log("call to car: audio over Bluetooth hands-free")
                    active = true
                    false
                }
                BluetoothHeadset.STATE_AUDIO_CONNECTING -> {
                    linkSeen = true
                    false
                }
                BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> linkSeen
                else -> false
            }
        }
        if (lost) fail("car audio link lost")
    }

    private fun fail(reason: String) {
        // The fallback runs outside this lock: it takes the caller's own lock, which is held
        // around start().
        val fallback = synchronized(this) {
            if (!routing) return
            // A Telecom call the phone has already ended (car hang-up) drops the link as expected.
            val callGone = !usedSco && !CarPlayTelecom.ownsCallAudio()
            onFallback.takeUnless { callGone }.also {
                release()
                log(if (callGone) "call to car: ended" else "call to car: $reason, back to the head unit's own call audio")
            }
        }
        fallback?.invoke()
    }

    private fun release() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(telecomCheck)
        active = false
        routing = false
        onFallback = null
        val app = appContext ?: return
        receiver?.let {
            try {
                app.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
            }
        }
        receiver = null
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        try {
            @Suppress("DEPRECATION")
            audio.isBluetoothScoOn = false
            if (usedSco) audio.stopBluetoothSco()
        } catch (error: RuntimeException) {
            log("call to car: could not stop Bluetooth SCO: ${error.message}")
        }
        usedSco = false
    }

    private fun registerReceiver(context: Context) {
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onLinkState(intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED))
            }
        }
        val filter = IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Sent by the Bluetooth process, which a non-exported receiver doesn't hear.
            context.registerReceiver(next, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(next, filter)
        }
        receiver = next
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
