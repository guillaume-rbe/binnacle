package com.shilapi.xcertplay.telecom

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** Telecom's view of one CarPlay call; the call itself runs on the iPhone. */
internal class CarPlayConnection(
    /** The iPhone's call id; a call dialled on the car gets it once the iPhone reports the call. */
    var callUuid: String,
    private val control: () -> CarPlayTelecom.CallControl?,
) : Connection() {
    private var ended = false

    init {
        // VoIP audio mode: Telecom uses communication mode, which the CarPlay call audio expects.
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE
    }

    fun update(state: CarPlayCallState) {
        if (ended) return
        setAddress(
            Uri.fromParts(PhoneAccount.SCHEME_TEL, state.remoteId.ifEmpty { state.displayName }, null),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        if (state.displayName.isNotEmpty()) {
            setCallerDisplayName(state.displayName, TelecomManager.PRESENTATION_ALLOWED)
        }
        when (state.status) {
            CarPlayCallState.STATUS_RINGING -> setRinging()
            CarPlayCallState.STATUS_SENDING, CarPlayCallState.STATUS_CONNECTING -> setDialing()
            CarPlayCallState.STATUS_ACTIVE -> setActive()
            CarPlayCallState.STATUS_HELD -> setOnHold()
        }
    }

    /** The call ended on the phone side. */
    fun endFromPhone() {
        if (ended) return
        ended = true
        setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
        destroy()
    }

    override fun onAnswer() {
        CarPlayTelecom.logLine("carplay calls: answer from car")
        control()?.accept(callUuid)
    }

    override fun onAnswer(videoState: Int) = onAnswer()

    override fun onReject() = endFromCar("reject")

    override fun onDisconnect() = endFromCar("hang up")

    override fun onAbort() = endFromCar("abort")

    override fun onPlayDtmfTone(c: Char) {
        CarPlayTelecom.logLine("carplay calls: keypad '$c' from car")
        control()?.sendDtmf(callUuid, c)
    }

    /**
     * The call stays up on the car until the phone reports it ended, so a hang-up the phone
     * ignores doesn't leave the call running on the head unit's speaker. If the phone hasn't
     * ended it after [END_WAIT_MILLIS], Telecom's side is ended anyway.
     */
    private fun endFromCar(reason: String) {
        CarPlayTelecom.logLine("carplay calls: $reason from car")
        control()?.end(callUuid)
        handler.postDelayed({
            if (ended) return@postDelayed
            CarPlayTelecom.logLine("carplay calls: phone kept the call after $reason; ending it on the car")
            ended = true
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            CarPlayTelecom.forget(this)
            CarPlayTelecom.forgetPending(this)
        }, END_WAIT_MILLIS)
    }

    private companion object {
        const val END_WAIT_MILLIS = 5_000L
        val handler = Handler(Looper.getMainLooper())
    }
}

/** Bound by Telecom for calls of the CarPlay phone account ([CarPlayTelecom]). */
class CarPlayConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = CarPlayTelecom.createConnection(request.extras?.getString(CarPlayTelecom.EXTRA_CALL_UUID), outgoing = false)

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = CarPlayTelecom.createConnection(
        request.extras?.getString(CarPlayTelecom.EXTRA_CALL_UUID),
        outgoing = true,
        // a call dialled on the car (no CarPlay call behind it yet) carries only the number
        dialledNumber = request.address?.takeIf { it.scheme == PhoneAccount.SCHEME_TEL }?.schemeSpecificPart,
    )
}
