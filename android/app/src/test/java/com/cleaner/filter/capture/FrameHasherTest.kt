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
    fun pixelsInsideAHeldCoverDoNotChangeTheHash() {
        val outside = IntArray(64) { 0xFF101010.toInt() }
        outside[0] = 0xFFFFFFFF.toInt()
        val covered = outside.copyOf()
        covered[63] = 0xFFFF00FF.toInt()
        val ignored = BooleanArray(64)
        ignored[63] = true
        val stable = FrameHasher.hashSamples(outside, ignored)
        val changedInside = FrameHasher.hashSamples(covered, ignored)
        assertTrue(FrameHasher.isSimilar(stable, changedInside))
    }

    @Test
    fun pixelsOutsideAHeldCoverChangeTheHash() {
        val base = IntArray(64) { 0xFF101010.toInt() }
        val moved = base.copyOf()
        for (i in 0 until 16) moved[i] = 0xFFFFFFFF.toInt()
        val ignored = BooleanArray(64)
        ignored[63] = true
        assertFalse(FrameHasher.isSimilar(FrameHasher.hashSamples(base, ignored), FrameHasher.hashSamples(moved, ignored)))
    }
}
