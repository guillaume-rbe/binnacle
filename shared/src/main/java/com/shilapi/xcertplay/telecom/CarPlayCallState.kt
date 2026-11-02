package com.shilapi.xcertplay.telecom

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * One iAP2 CallStateUpdate (0x4155) from the iPhone. Seen on an iPhone 16 (2 Nov):
 * [0] remote ID "+64..." (empty in the first update), [1] display name, [2] status,
 * [3] direction, [4] call UUID; the last update for a call carries an empty status and [11].
 */
internal data class CarPlayCallState(
    val callUuid: String,
    val remoteId: String,
    val displayName: String,
    val status: Int,
    val direction: Int,
) {
    val ended: Boolean get() = status == STATUS_DISCONNECTED || status == STATUS_DISCONNECTING

    companion object {
        const val MESSAGE_ID = 0x4155

        const val STATUS_DISCONNECTED = 0
        const val STATUS_SENDING = 1
        const val STATUS_RINGING = 2
        const val STATUS_CONNECTING = 3
        const val STATUS_ACTIVE = 4
        const val STATUS_HELD = 5
        const val STATUS_DISCONNECTING = 6

        const val DIRECTION_INCOMING = 1
        const val DIRECTION_OUTGOING = 2

        /** Null for the empty update the iPhone sends when nothing is going on (no call UUID). */
        fun parse(frame: Iap2Frame): CarPlayCallState? = parse(Iap2BodyReader.of(frame))

        fun parse(body: Iap2BodyReader): CarPlayCallState? {
            val uuid = body.optionalString(4)?.takeIf { it.isNotEmpty() } ?: return null
            return CarPlayCallState(
                callUuid = uuid,
                remoteId = body.optionalString(0).orEmpty(),
                displayName = body.optionalString(1).orEmpty(),
                status = body.first(2)?.payload?.takeIf { it.size == 1 }?.let { body.u8(2) }
                    ?: STATUS_DISCONNECTED,
                direction = body.first(3)?.payload?.takeIf { it.size == 1 }?.let { body.u8(3) }
                    ?: DIRECTION_INCOMING,
            )
        }
    }
}

/**
 * Call buttons as CarPlay telephony HID usages: indexes into the "xcertplay Telephony" HID device
 * ([com.shilapi.xcertplay.airplay.AirPlayHid]). During CarPlay the iPhone ignored iAP2 EndCall and
 * SendDTMF (2 Nov); CarPlay cars send these keys instead.
 */
internal object CarPlayTelephonyKeys {
    const val HOOK_SWITCH = 1
    const val DROP = 3

    /** Null for a character that isn't a keypad key. */
    fun keypad(tone: Char): Int? = when (tone) {
        in '0'..'9' -> 5 + (tone - '0')
        '*' -> 15
        '#' -> 16
        else -> null
    }
}
