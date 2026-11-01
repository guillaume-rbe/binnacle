package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.Intent

/**
 * Tells the stock head-unit software (ZIQI / XBS units) when a CarPlay phone call starts and ends,
 * with the same broadcast the stock ZLink app sends, addressed to the unit's CAN app only. The CAN
 * app reacts by putting the car-facing Bluetooth into call mode, so the car shows a phone call.
 *
 * Only the phone-call state is sent; on units without that software nobody listens.
 */
internal class HeadUnitCallState(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    // Start as "on" so the first sync always sends OFF: a previous run that was force-quit or
    // reinstalled mid-call leaves the head unit in CarPlay-call mode, which blocks every other app.
    private var callOn = true

    init {
        set(false)
    }

    /** modesChanged: follow the phone-call app state (see [phoneCallActive]). */
    @Synchronized
    fun onModesChanged(params: Map<String, Any?>) {
        phoneCallActive(params)?.let(::set)
    }

    /** The session ended or the controller closed: a call can't still be running. */
    @Synchronized
    fun end() = set(false)

    private fun set(on: Boolean) {
        if (on == callOn) return
        callOn = on
        val status = if (on) STATUS_CALL_ON else STATUS_CALL_OFF
        try {
            appContext.sendBroadcast(
                Intent(ACTION)
                    // Only the CAN app (which tells the head-unit MCU, so the car shows a call).
                    // The stock car service also listens and would enter "CarPlay call" mode,
                    // which refuses every app start except ZLink until the call ends.
                    .setPackage(CANAPP_PACKAGE)
                    .putExtra(EXTRA_PHONE_MODE, PHONE_MODE_WIRELESS)
                    .putExtra(EXTRA_STATUS, status),
            )
            log("head unit call state $status")
        } catch (error: RuntimeException) {
            log("head unit call state $status not sent: ${error.message}")
        }
    }

    private companion object {
        const val ACTION = "com.zjinnova.zlink"
        const val CANAPP_PACKAGE = "com.xbs.canapp"
        const val EXTRA_PHONE_MODE = "phoneMode"
        const val EXTRA_STATUS = "status"
        const val PHONE_MODE_WIRELESS = "carplay_wireless"
        const val STATUS_CALL_ON = "PHONE_CALL_ON"
        const val STATUS_CALL_OFF = "PHONE_CALL_OFF"
    }
}

/**
 * Phone-call state from modesChanged params, or null when they don't say. The appStates entry
 * with appStateID 2 is the phone call; the iPhone reports it as an owner, e.g.
 * {appStateID=2, entity=1} during a call and entity=0 when idle (a boolean "state" is accepted too).
 */
internal fun phoneCallActive(params: Map<String, Any?>): Boolean? {
    val appStates = params["appStates"] as? List<*> ?: return null
    for (entry in appStates) {
        val state = entry as? Map<*, *> ?: continue
        if ((state["appStateID"] as? Number)?.toInt() != APP_STATE_PHONE_CALL) continue
        return when (val value = state["entity"] ?: state["state"]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> null
        }
    }
    return null
}

private const val APP_STATE_PHONE_CALL = 2
