package com.cleaner.filter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpandCoverTest {
    @Test
    fun growsTheBoxWithoutLeavingTheScreen() {
        val expanded = expandCover(100f, 100f, 200f, 300f, 1000f, 2000f, 0.10f)
        assertEquals(90f, expanded.left, 0.01f)
        assertEquals(80f, expanded.top, 0.01f)
        assertEquals(210f, expanded.right, 0.01f)
        assertEquals(320f, expanded.bottom, 0.01f)
    }

    @Test
    fun clampsAtScreenEdges() {
        val expanded = expandCover(0f, 0f, 50f, 50f, 100f, 100f, 0.5f)
        assertEquals(0f, expanded.left, 0.01f)
        assertEquals(0f, expanded.top, 0.01f)
        assertEquals(75f, expanded.right, 0.01f)
        assertEquals(75f, expanded.bottom, 0.01f)
    }

    @Test
    fun breastCoverIsMuchLargerThanNippleBox() {
        // Tiny nipple box in a 432x960 capture.
        val expanded = expandCover(
            left = 200f,
            top = 300f,
            right = 220f,
            bottom = 320f,
            maxWidth = 432f,
            maxHeight = 960f,
            label = "FEMALE_BREAST_EXPOSED",
        )
        val width = expanded.right - expanded.left
        val height = expanded.bottom - expanded.top
        assertTrue("cover width $width", width >= 180f)
        assertTrue("cover height $height", height >= 200f)
        // Bias downward so chest below the nipple is covered.
        val boxCenterY = 310f
        val coverCenterY = (expanded.top + expanded.bottom) * 0.5f
        assertTrue("expected downward bias, center=$coverCenterY", coverCenterY > boxCenterY)
    }

    @Test
    fun bodySafetyPlateGrowsModestlyWithoutEatingTheFrame() {
        val nipple = CoverRect(200f, 300f, 220f, 320f)
        val plate = bodySafetyPlate(listOf(nipple), 432f, 960f)
        assertTrue(plate.right - plate.left >= 100f)
        assertTrue(plate.bottom - plate.top >= 100f)
        // Must not become a near-fullscreen wipe.
        assertTrue(plate.right - plate.left < 400f)
        assertTrue(plate.bottom - plate.top < 500f)
    }

    @Test
    fun mergesNearbyBreastCoversIntoOnePlate() {
        val left = CoverRect(40f, 200f, 180f, 400f)
        val right = CoverRect(160f, 210f, 300f, 410f)
        val merged = mergeCovers(listOf(left, right), gap = 20f)
        assertEquals(1, merged.size)
        assertEquals(40f, merged[0].left, 0.01f)
        assertEquals(300f, merged[0].right, 0.01f)
    }
}
