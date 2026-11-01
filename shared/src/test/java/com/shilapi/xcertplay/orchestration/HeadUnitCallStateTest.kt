package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadUnitCallStateTest {
    private fun modes(vararg states: Map<String, Any?>): Map<String, Any?> =
        mapOf("appStates" to states.toList(), "resources" to emptyList<Any>())

    @Test
    fun callOwnedByThePhoneIsActive() {
        // As sent by an iPhone during a call (decoded modesChanged bplist, 1 Nov).
        val params = modes(
            mapOf("entity" to 1L, "appStateID" to 2L),
            mapOf("entity" to 1L, "speechMode" to 1L, "appStateID" to 1L),
            mapOf("entity" to 0L, "appStateID" to 3L),
        )
        assertEquals(true, phoneCallActive(params))
    }

    @Test
    fun idleCallStateIsInactive() {
        val params = modes(
            mapOf("entity" to 0L, "appStateID" to 2L),
            mapOf("entity" to 0L, "speechMode" to -1L, "appStateID" to 1L),
        )
        assertEquals(false, phoneCallActive(params))
    }

    @Test
    fun booleanStateIsAccepted() {
        assertEquals(true, phoneCallActive(modes(mapOf("appStateID" to 2, "state" to true))))
        assertEquals(false, phoneCallActive(modes(mapOf("appStateID" to 2, "state" to false))))
    }

    @Test
    fun missingOrUnrelatedStatesSayNothing() {
        assertNull(phoneCallActive(emptyMap()))
        assertNull(phoneCallActive(modes(mapOf("appStateID" to 1, "speechMode" to 1))))
        assertNull(phoneCallActive(modes(mapOf("appStateID" to 2))))
    }
}
