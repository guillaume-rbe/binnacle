package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingStoreTest {
    @Test fun keepsAtMostTheCapDroppingTheOldest() {
        val evicted = mutableListOf<String>()
        val store = PairingStore(onEvict = { evicted += it }, maxPairings = 3)
        for (i in 1..5) store.save("id$i", byteArrayOf(i.toByte()))
        assertEquals(listOf("id3", "id4", "id5"), store.identifiers())
        assertEquals(listOf("id1", "id2"), evicted)
        assertNull(store.get("id1"))
        assertArrayEquals(byteArrayOf(5), store.get("id5"))
    }

    @Test fun savingAgainMakesAPairingTheNewest() {
        val evicted = mutableListOf<String>()
        val store = PairingStore(onEvict = { evicted += it }, maxPairings = 2)
        store.save("phone", byteArrayOf(1))
        store.save("other", byteArrayOf(2))
        store.save("phone", byteArrayOf(3))   // the owner's phone pairs again
        store.save("third", byteArrayOf(4))
        assertEquals(listOf("other"), evicted)
        assertArrayEquals(byteArrayOf(3), store.get("phone"))
    }

    @Test fun defaultCapIsEight() {
        val store = PairingStore()
        for (i in 1..20) store.save("id$i", byteArrayOf(i.toByte()))
        assertEquals(8, store.identifiers().size)
    }
}
