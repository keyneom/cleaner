package com.cleaner.filter.ml

import kotlin.math.max
import kotlin.math.min

/** A square region of a frame fed to the model. */
internal data class ScanTile(val left: Int, val top: Int, val size: Int) {
    val right: Int get() = left + size
    val bottom: Int get() = top + size
}

/** Square windows along a tall or wide frame so 320n sees body-sized detail. */
internal fun scanTiles(width: Int, height: Int): List<ScanTile> {
    if (width <= 0 || height <= 0) return emptyList()
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    val origins = if (longSide * 2 < shortSide * 3) {
        listOf(0)
    } else {
        listOf(0, (longSide - shortSide) / 2, longSide - shortSide).distinct()
    }
    return if (height >= width) {
        origins.map { ScanTile(0, it, shortSide) }
    } else {
        origins.map { ScanTile(it, 0, shortSide) }
    }
}

/**
 * Overlapping [tile]-sized squares over rows [top, bottom) of a [width]-wide frame.
 * Tiles are at model resolution, so nothing is shrunk before the model sees it.
 * The last tile in each direction is pinned to the edge, so the whole area is covered.
 */
internal fun scanGrid(
    width: Int,
    top: Int,
    bottom: Int,
    tile: Int = MODEL_INPUT_SIZE,
    stride: Int = TILE_STRIDE,
): List<ScanTile> {
    val height = bottom - top
    if (width <= 0 || height <= 0) return emptyList()
    val side = min(tile, min(width, height))
    val xs = origins(width, side, min(stride, side))
    val ys = origins(height, side, min(stride, side)).map { it + top }
    return ys.flatMap { y -> xs.map { x -> ScanTile(x, y, side) } }
}

private fun origins(length: Int, side: Int, stride: Int): List<Int> {
    if (length <= side) return listOf(0)
    val out = ArrayList<Int>()
    var o = 0
    while (o + side < length) {
        out += o
        o += max(1, stride)
    }
    out += length - side
    return out.distinct()
}

/** Grid tiles that touch any of [regions]. */
internal fun tilesTouching(tiles: List<ScanTile>, regions: List<RegionI>): List<ScanTile> =
    tiles.filter { t ->
        regions.any { r -> r.left < t.right && r.right > t.left && r.top < t.bottom && r.bottom > t.top }
    }

internal data class RegionI(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Writes [tile] of an ARGB frame into [out] as planar RGB floats in 0..1 at model size.
 * Tiles already at model size are copied; others are nearest-neighbour resampled.
 */
internal fun fillTileInput(
    pixels: IntArray,
    frameWidth: Int,
    tile: ScanTile,
    out: FloatArray,
    modelSize: Int = MODEL_INPUT_SIZE,
) {
    val area = modelSize * modelSize
    require(out.size >= area * 3)
    for (y in 0 until modelSize) {
        val sy = tile.top + (y * tile.size) / modelSize
        val row = sy * frameWidth
        for (x in 0 until modelSize) {
            val sx = tile.left + (x * tile.size) / modelSize
            val p = pixels[row + sx]
            val i = y * modelSize + x
            out[i] = ((p shr 16) and 0xff) / 255f
            out[area + i] = ((p shr 8) and 0xff) / 255f
            out[area * 2 + i] = (p and 0xff) / 255f
        }
    }
}

/** NudeNet 320n input side. */
const val MODEL_INPUT_SIZE = 320

/** Overlap between neighbouring grid tiles is 320 - 256 = 64 px, so a part cut by one tile edge is whole in the next. */
internal const val TILE_STRIDE = 256
