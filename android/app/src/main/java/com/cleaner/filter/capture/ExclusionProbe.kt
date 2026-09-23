package com.cleaner.filter.capture

/**
 * Proves at runtime that the overlay really is left out of screen capture.
 *
 * The overlay briefly draws a magenta/green checkerboard. If that pattern shows up in
 * captured frames, the overlay is being filmed, and mirroring it would freeze the
 * screen (self-capture). The exclusion relies on a hidden API, so this is checked on
 * every attach instead of trusted.
 */
internal object ExclusionProbe {
    const val GRID = 4
    const val MAGENTA: Int = 0xFFFF00FF.toInt()
    const val GREEN: Int = 0xFF00FF00.toInt()

    /** Colour of checker block ([bx], [by]). */
    fun blockColor(bx: Int, by: Int): Int = if ((bx + by) % 2 == 0) MAGENTA else GREEN

    /** Marker square in screen pixels: left edge, vertically centred. */
    fun screenRect(screenWidth: Int, screenHeight: Int): CoverRect {
        val side = (minOf(screenWidth, screenHeight) * 0.08f).coerceAtLeast(GRID * 4f)
        val left = side * 0.25f
        val top = screenHeight * 0.5f - side / 2f
        return CoverRect(left, top, left + side, top + side)
    }

    /** True when most checker blocks appear in [frame] at [rect] (capture coordinates). */
    fun markerVisible(frame: Frame, rect: CoverRect): Boolean {
        val blockW = (rect.right - rect.left) / GRID
        val blockH = (rect.bottom - rect.top) / GRID
        var matches = 0
        for (by in 0 until GRID) {
            for (bx in 0 until GRID) {
                val x = (rect.left + (bx + 0.5f) * blockW).toInt()
                val y = (rect.top + (by + 0.5f) * blockH).toInt()
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                if (looksLike(frame.pixels[y * frame.width + x], blockColor(bx, by))) matches++
            }
        }
        return matches >= GRID * GRID - 3
    }

    private fun looksLike(pixel: Int, expected: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return if (expected == MAGENTA) {
            r > 170 && b > 170 && g < 90
        } else {
            g > 170 && r < 90 && b < 90
        }
    }
}
