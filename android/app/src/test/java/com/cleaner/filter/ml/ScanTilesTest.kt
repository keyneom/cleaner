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
    fun gridCoversPhoneCaptureAtModelResolution() {
        // 576 x 1280 capture, content band 57..1209.
        val tiles = scanGrid(576, 57, 1209)
        assertTrue(tiles.all { it.size == MODEL_INPUT_SIZE })
        assertEquals(listOf(0, 256), tiles.map { it.left }.distinct().sorted())
        assertEquals(57, tiles.minOf { it.top })
        assertEquals(1209, tiles.maxOf { it.bottom })
        // Every pixel of the band is inside some tile.
        for (y in 57 until 1209 step 7) {
            for (x in 0 until 576 step 7) {
                assertTrue("($x,$y) uncovered", tiles.any { x >= it.left && x < it.right && y >= it.top && y < it.bottom })
            }
        }
        // Neighbouring tiles overlap, so a part cut by one edge is whole in the next.
        assertTrue(tiles.map { it.top }.distinct().sorted().zipWithNext().all { (a, b) -> b - a <= TILE_STRIDE })
    }

    @Test
    fun gridOnSmallFrameUsesSquaresOfTheShortSide() {
        assertEquals(listOf(ScanTile(0, 10, 200)), scanGrid(200, 10, 210))
        assertEquals(listOf(ScanTile(0, 10, 200), ScanTile(0, 50, 200)), scanGrid(200, 10, 250))
    }

    @Test
    fun onlyTilesTouchingChangedRegionsAreKept() {
        val tiles = scanGrid(576, 57, 1209)
        val touched = tilesTouching(tiles, listOf(RegionI(0, 1150, 576, 1209)))
        assertTrue(touched.isNotEmpty())
        assertTrue(touched.size < tiles.size)
        assertTrue(touched.all { it.bottom > 1150 })
    }

    @Test
    fun tileInputIsPlanarRgbInUnitRange() {
        val w = 4
        val pixels = IntArray(w * 4) { 0xFF336699.toInt() }
        pixels[1 * w + 2] = 0xFFFF0000.toInt()
        val out = FloatArray(3 * 2 * 2)
        fillTileInput(pixels, w, ScanTile(2, 1, 2), out, modelSize = 2)
        // (0,0) of the tile is frame (2,1): pure red.
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(0f, out[4], 1e-6f)
        assertEquals(0f, out[8], 1e-6f)
        // (1,1) of the tile is frame (3,2).
        assertEquals(0x33 / 255f, out[3], 1e-6f)
        assertEquals(0x66 / 255f, out[7], 1e-6f)
        assertEquals(0x99 / 255f, out[11], 1e-6f)
    }
}
