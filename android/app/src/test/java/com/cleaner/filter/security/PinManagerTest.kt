package com.cleaner.filter.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinManagerTest {
    @Test
    fun verifyPinMatchesHash() {
        val hash = PinManager.hashPin("1234")
        assertTrue(PinManager.verifyPin("1234", hash))
        assertFalse(PinManager.verifyPin("0000", hash))
    }

    @Test
    fun nullHashAllowsAnyPin() {
        assertTrue(PinManager.verifyPin("anything", null))
    }
}
