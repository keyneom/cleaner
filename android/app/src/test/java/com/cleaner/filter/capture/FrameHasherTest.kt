package com.cleaner.filter.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameHasherTest {
    @Test
    fun identicalHashesAreSimilar() {
        val h = 0x123456789ABCDEF0L
        assertTrue(FrameHasher.isSimilar(h, h))
    }

    @Test
    fun differentHashesAreNotSimilar() {
        assertFalse(FrameHasher.isSimilar(0L, -1L))
    }
}
