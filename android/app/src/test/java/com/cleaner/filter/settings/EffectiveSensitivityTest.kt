package com.cleaner.filter.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveSensitivityTest {
    @Test
    fun missingValueUsesDefault() {
        assertEquals(DEFAULT_SCORE_THRESHOLD, effectiveSensitivity(null), 0f)
    }

    @Test
    fun storedValueIsKeptInsideSliderRange() {
        assertEquals(0.25f, effectiveSensitivity(0.25f), 0f)
        assertEquals(MIN_SCORE_THRESHOLD, effectiveSensitivity(0.02f), 0f)
        assertEquals(MAX_SCORE_THRESHOLD, effectiveSensitivity(0.9f), 0f)
    }
}
