package com.shilapi.xcertplay.telecom

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallStateTest {
    @Test
    fun parsesActiveOutgoingCallAsRecorded() {
        // As received from an iPhone 16 calling voicemail (2 Nov).
        val frame = Iap2Messages.buildRaw(CarPlayCallState.MESSAGE_ID) {
            string(0, "+6421700700")
            string(1, "Voicemail")
            u8(2, 4)
            u8(3, 2)
            string(4, "D964DE78-E4DE-4A12-BD76-113316E7ECA5")
        }
        val state = CarPlayCallState.parse(frame)!!
        assertEquals("+6421700700", state.remoteId)
        assertEquals("Voicemail", state.displayName)
        assertEquals(CarPlayCallState.STATUS_ACTIVE, state.status)
        assertEquals(CarPlayCallState.DIRECTION_OUTGOING, state.direction)
        assertEquals("D964DE78-E4DE-4A12-BD76-113316E7ECA5", state.callUuid)
    }

    @Test
    fun emptyStatusMeansTheCallEnded() {
        val frame = Iap2Messages.buildRaw(CarPlayCallState.MESSAGE_ID) {
            string(0, "+6421700700")
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
    fun callControlMessagesCarryTheCallUuid() {
        val uuid = "D964DE78-E4DE-4A12-BD76-113316E7ECA5"
        val end = Iap2BodyReader.of(CarPlayCallMessages.endCall(uuid))
        assertEquals(uuid, end.string(0))
        assertEquals(0, end.u8(1))
        val dtmf = Iap2BodyReader.of(CarPlayCallMessages.sendDtmf(uuid, '#')!!)
        assertEquals(uuid, dtmf.string(0))
        assertEquals(11, dtmf.u8(1))
        assertNull(CarPlayCallMessages.sendDtmf(uuid, 'A'))
        val accept = Iap2BodyReader.of(CarPlayCallMessages.acceptCall(uuid))
        assertEquals(uuid, accept.string(1))
    }
}
