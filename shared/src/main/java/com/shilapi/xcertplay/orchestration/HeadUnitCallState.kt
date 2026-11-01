package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.Intent

/**
 * Tells the stock head-unit software (ZIQI / XBS units) when a CarPlay phone call starts and ends,
 * with the same broadcast the stock ZLink receiver sends. The unit's CAN app reacts by putting the
 * car-facing Bluetooth into call mode, so the car shows a phone call instead of music.
 *
 * Only the phone-call state is sent; on units without that software nobody listens.
 */
internal class HeadUnitCallState(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var callOn = false

    /** modesChanged params: appStates entry with appStateID 2 is the phone call. */
    @Synchronized
    fun onModesChanged(params: Map<String, Any?>) {
        val appStates = params["appStates"] as? List<*> ?: return
        for (entry in appStates) {
            val state = entry as? Map<*, *> ?: continue
            if ((state["appStateID"] as? Number)?.toInt() != APP_STATE_PHONE_CALL) continue
            val on = when (val value = state["state"]) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                else -> continue
            }
            set(on)
        }
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
                    .putExtra(EXTRA_PHONE_MODE, PHONE_MODE_WIRELESS)
                    .putExtra(EXTRA_STATUS, status),
            )
            log("head unit call state $status")
        } catch (error: RuntimeException) {
            log("head unit call state $status not sent: ${error.message}")
        }
    }

    private companion object {
        const val APP_STATE_PHONE_CALL = 2
        const val ACTION = "com.zjinnova.zlink"
        const val EXTRA_PHONE_MODE = "phoneMode"
        const val EXTRA_STATUS = "status"
        const val PHONE_MODE_WIRELESS = "carplay_wireless"
        const val STATUS_CALL_ON = "PHONE_CALL_ON"
        const val STATUS_CALL_OFF = "PHONE_CALL_OFF"
    }
}
