package com.shilapi.xcertplay.telecom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Mirrors CarPlay phone calls into Android Telecom as calls of our own phone account, so a car
 * connected to this head unit's Bluetooth as a hands-free device sees a real call: caller name and
 * number on its screen, and its answer, hang-up and keypad buttons come back to us
 * ([CarPlayConnection]) and go on to the iPhone as CarPlay telephony keys ([CallControl]).
 *
 * The account must be enabled once (Settings > Calling accounts, or
 * `adb shell telecom set-phone-account-enabled <component> carplay 0`); until then nothing is
 * mirrored and calls fall back to a plain Bluetooth audio link.
 */
internal object CarPlayTelecom {
    interface CallControl {
        fun accept(callUuid: String)
        fun end(callUuid: String)
        fun sendDtmf(callUuid: String, tone: Char)
    }

    const val ACCOUNT_ID = "carplay"
    internal const val EXTRA_CALL_UUID = "com.shilapi.xcertplay.CALL_UUID"

    private const val STATE_SETTLE_MILLIS = 300L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var control: CallControl? = null
    private var log: (String) -> Unit = {}
    private val calls = HashMap<String, CarPlayCallState>()
    private val connections = HashMap<String, CarPlayConnection>()
    private val requested = HashSet<String>()
    /** Calls Telecom couldn't take; their audio falls back to the plain link. */
    private val refused = HashSet<String>()

    @Synchronized
    fun attach(context: Context, control: CallControl, log: (String) -> Unit) {
        appContext = context.applicationContext
        this.control = control
        this.log = log
        try {
            telecom()?.registerPhoneAccount(
                PhoneAccount.builder(handle(context), "CarPlay")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                    // Communication (VoIP) mode from the start: a dialling call otherwise gets the
                    // modem call mode first, and on a Unisoc unit (2 Nov) the audio HAL then left
                    // the car's Bluetooth audio path off, so the car got no sound.
                    .setExtras(Bundle().apply { putBoolean(PhoneAccount.EXTRA_ALWAYS_USE_VOIP_AUDIO_MODE, true) })
                    .build(),
            )
            log("carplay calls: phone account registered, enabled=${enabled()}")
        } catch (error: RuntimeException) {
            log("carplay calls: phone account not registered: ${error.message}")
        }
    }

    /** The session ended: no call can still be running. */
    @Synchronized
    fun endAll() {
        connections.values.toList().forEach { it.endFromPhone() }
        connections.clear()
        calls.clear()
        requested.clear()
        refused.clear()
    }

    @Synchronized
    fun detach(control: CallControl) {
        if (this.control !== control) return
        endAll()
        this.control = null
    }

    /** True when calls are mirrored into Telecom (account enabled); Telecom then owns call audio. */
    @Synchronized
    fun enabled(): Boolean = try {
        val context = appContext ?: return false
        telecom()?.getPhoneAccount(handle(context))?.isEnabled == true
    } catch (error: RuntimeException) {
        false
    }

    /** True when a call is (being) mirrored into Telecom, which then owns the call's audio link. */
    @Synchronized
    fun ownsCallAudio(): Boolean = calls.keys.any { it !in refused } && enabled()

    @Synchronized
    fun onCallState(state: CarPlayCallState) {
        val context = appContext ?: return
        if (state.ended) {
            calls.remove(state.callUuid)
            requested.remove(state.callUuid)
            refused.remove(state.callUuid)
            connections.remove(state.callUuid)?.endFromPhone()
            return
        }
        calls[state.callUuid] = state
        connections[state.callUuid]?.let {
            it.update(state)
            return
        }
        if (state.callUuid in requested || state.callUuid in refused || !enabled()) return
        if (state.direction == CarPlayCallState.DIRECTION_OUTGOING &&
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        ) {
            refuse(state.callUuid, "outgoing call not mirrored, no call permission")
            return
        }
        // The first update of a call placed on the phone has no number yet, and the car keeps
        // the first one it is given; wait a moment for it (it follows within about a second).
        if (state.remoteId.isEmpty() && state.status == CarPlayCallState.STATUS_SENDING) return
        requested += state.callUuid
        val extras = Bundle().apply { putString(EXTRA_CALL_UUID, state.callUuid) }
        try {
            if (state.direction == CarPlayCallState.DIRECTION_OUTGOING) {
                telecom()?.placeCall(
                    address(state),
                    Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
                        putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, extras)
                    },
                )
            } else {
                telecom()?.addNewIncomingCall(
                    handle(context),
                    extras.apply { putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address(state)) },
                )
            }
            log("carplay calls: added ${directionName(state)} call status=${state.status}")
        } catch (error: RuntimeException) {
            requested -= state.callUuid
            refuse(state.callUuid, "could not add call: ${error.message}")
        }
    }

    /** Listener for Telecom giving up a call's audio (the route then opens the link itself). */
    @Volatile
    var onAudioOwnershipLost: (() -> Unit)? = null

    private fun refuse(callUuid: String, reason: String) {
        refused += callUuid
        log("carplay calls: $reason")
        // Outside this object's lock: the route takes its own lock and then reads ownership.
        onAudioOwnershipLost?.let { handler.post(it) }
    }

    /** Telecom asked for the connection of a call we added. */
    @Synchronized
    internal fun createConnection(callUuid: String?): Connection {
        // Extras should carry the call UUID; only without one, take the single call waiting. A
        // UUID that is gone ended before Telecom asked.
        val state = if (callUuid != null) {
            calls[callUuid]
        } else {
            calls.values.singleOrNull { it.callUuid !in connections }
        }
        if (state == null) {
            log("carplay calls: call $callUuid already ended")
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.REMOTE))
        }
        return CarPlayConnection(state.callUuid, ::controlFor).also {
            // Telecom puts a new outgoing call into DIALING after this returns, overriding an
            // ACTIVE set here (2 Nov: the car then kept it as dialling and its keypad stayed
            // off). Start it dialling and apply the real state once Telecom has it.
            val outgoing = state.direction == CarPlayCallState.DIRECTION_OUTGOING
            it.update(if (outgoing) state.copy(status = CarPlayCallState.STATUS_CONNECTING) else state)
            connections[state.callUuid] = it
            if (outgoing) handler.postDelayed({ refresh(state.callUuid) }, STATE_SETTLE_MILLIS)
        }
    }

    @Synchronized
    private fun refresh(callUuid: String) {
        val state = calls[callUuid] ?: return
        connections[callUuid]?.update(state)
    }

    @Synchronized
    internal fun forget(connection: CarPlayConnection) {
        if (connections[connection.callUuid] === connection) connections.remove(connection.callUuid)
    }

    @Synchronized
    private fun controlFor(): CallControl? = control

    internal fun logLine(message: String) = log(message)

    private fun address(state: CarPlayCallState): Uri =
        Uri.fromParts(PhoneAccount.SCHEME_TEL, state.remoteId.ifEmpty { state.displayName }, null)

    private fun directionName(state: CarPlayCallState) =
        if (state.direction == CarPlayCallState.DIRECTION_OUTGOING) "outgoing" else "incoming"

    private fun telecom(): TelecomManager? = appContext?.getSystemService(TelecomManager::class.java)

    fun handle(context: Context) = PhoneAccountHandle(
        ComponentName(context, CarPlayConnectionService::class.java),
        ACCOUNT_ID,
    )
}
