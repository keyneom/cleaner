package com.cleaner.filter.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTilesTest {
    @Test
    fun tallFrameIsSplitIntoOverlappingSquares() {
        val tiles = scanTiles(144, 320)
        assertEquals(3, tiles.size)
        assertEquals(ScanTile(0, 0, 144), tiles[0])
        assertEquals(ScanTile(0, 88, 144), tiles[1])
        assertEquals(ScanTile(0, 176, 144), tiles[2])
    }

    @Test
    fun squareFrameIsOneWindow() {
        val tiles = scanTiles(320, 320)
        assertEquals(listOf(ScanTile(0, 0, 320)), tiles)
    }

    @Test
    fun phoneSizedCaptureKeepsTilesAtModelResolution() {
        val tiles = scanTiles(432, 960)
        assertEquals(3, tiles.size)
        assertTrue(tiles.all { it.size >= 320 })
        assertEquals(0, tiles.first().top)
        assertEquals(960, tiles.last().top + tiles.last().size)
    }
}
