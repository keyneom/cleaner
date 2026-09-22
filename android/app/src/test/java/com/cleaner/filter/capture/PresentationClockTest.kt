package com.cleaner.filter.capture

import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationClockTest {
    @Test
    fun presentationTimeIncludesDelay() {
        val clock = PresentationClock(50L)
        val capture = 1_000_000L
        assertTrue(clock.presentationTimeFor(capture) > capture)
    }
}
