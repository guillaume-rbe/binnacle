package com.shilapi.xcertplay.telecom

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallStateTest {
    @Test
    fun parsesActiveOutgoingCallAsRecorded() {
        // As received from an iPhone 16 calling voicemail (2 Nov), with the number replaced by a fictional one.
        val frame = Iap2Messages.buildRaw(CarPlayCallState.MESSAGE_ID) {
            string(0, "+15555550123")
            string(1, "Voicemail")
            u8(2, 4)
            u8(3, 2)
            string(4, "D964DE78-E4DE-4A12-BD76-113316E7ECA5")
        }
        val state = CarPlayCallState.parse(frame)!!
        assertEquals("+15555550123", state.remoteId)
        assertEquals("Voicemail", state.displayName)
        assertEquals(CarPlayCallState.STATUS_ACTIVE, state.status)
        assertEquals(CarPlayCallState.DIRECTION_OUTGOING, state.direction)
        assertEquals("D964DE78-E4DE-4A12-BD76-113316E7ECA5", state.callUuid)
    }

    @Test
    fun emptyStatusMeansTheCallEnded() {
        val frame = Iap2Messages.buildRaw(CarPlayCallState.MESSAGE_ID) {
            string(0, "+15555550123")
            string(1, "Voicemail")
            raw(2, ByteArray(0))
            u8(3, 2)
            string(4, "D964DE78-E4DE-4A12-BD76-113316E7ECA5")
            raw(11, ByteArray(0))
        }
        assertTrue(CarPlayCallState.parse(frame)!!.ended)
    }

    @Test
    fun updateWithoutCallIsIgnored() {
        val frame = Iap2Messages.buildRaw(CarPlayCallState.MESSAGE_ID) { raw(2, ByteArray(0)) }
        assertNull(CarPlayCallState.parse(frame))
    }

    @Test
    fun keypadMapsToTelephonyHidIndexes() {
        // Index order of the "xcertplay Telephony" HID descriptor: 0xb0..0xb9, then 0xba, 0xbb.
        assertEquals(5, CarPlayTelephonyKeys.keypad('0'))
        assertEquals(14, CarPlayTelephonyKeys.keypad('9'))
        assertEquals(15, CarPlayTelephonyKeys.keypad('*'))
        assertEquals(16, CarPlayTelephonyKeys.keypad('#'))
        assertNull(CarPlayTelephonyKeys.keypad('A'))
    }
}
