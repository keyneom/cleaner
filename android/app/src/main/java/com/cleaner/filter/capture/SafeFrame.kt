package com.cleaner.filter.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Never-seen presentation, independent of Android so it can be unit tested.
 *
 * The rule: a pixel reaches the screen only if it matches a pixel the classifier has
 * already seen. The last classified frame is the [Reference]. Each new capture is
 * compared to it cell by cell:
 *
 * - STILL: the cell is unchanged. Show it live.
 * - SHIFTED: the cell is reference content moved by the frame's vertical scroll.
 *   Show it live; covers move with it.
 * - UNTRUSTED: new or changed pixels. If the frame did not scroll, show the
 *   reference's (classified) pixels there instead. If it scrolled, paint black
 *   until the classifier catches up.
 *
 * Scrolling therefore stays at capture rate, and only the newly exposed strip waits
 * on the model.
 */

/** One captured frame in ARGB_8888 order. */
internal class Frame(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val captureNanos: Long = 0L,
) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height)
    }
}

/** Rows that scroll with the app. Rows above [top] and from [bottom] are system bars. */
internal data class ContentBand(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top

    companion object {
        /** Status-bar and nav-bar shares of a typical phone frame. */
        fun forFrame(height: Int): ContentBand {
            val top = (height * 0.045f).toInt()
            val bottom = (height - height * 0.055f).toInt().coerceAtLeast(top + 1)
            return ContentBand(top, bottom)
        }
    }
}

/** A cover in capture coordinates, remembering when the model last saw it. */
internal data class Cover(val rect: CoverRect, val lastSeenNanos: Long)

/** The last classified frame and its covers. */
internal class Reference(
    val frame: Frame,
    val band: ContentBand,
    val covers: List<Cover>,
) {
    val profile: RowProfile = RowProfile.of(frame, band)
}

/** Per-row luma summary in a few column bands, used to estimate vertical scroll. */
internal class RowProfile(val bands: Int, val rows: Int, val values: FloatArray) {
    fun at(band: Int, row: Int): Float = values[row * bands + band]

    companion object {
        const val BANDS = 4

        fun of(frame: Frame, band: ContentBand): RowProfile {
            val values = FloatArray(frame.height * BANDS)
            val w = frame.width
            for (y in band.top until band.bottom) {
                val rowStart = y * w
                for (b in 0 until BANDS) {
                    val x0 = b * w / BANDS
                    val x1 = (b + 1) * w / BANDS
                    var sum = 0
                    var n = 0
                    var x = x0
                    while (x < x1) {
                        sum += luma(frame.pixels[rowStart + x])
                        n++
                        x += 2
                    }
                    values[y * BANDS + b] = if (n == 0) 0f else sum.toFloat() / n
                }
            }
            return RowProfile(BANDS, frame.height, values)
        }
    }
}

/** A little-endian read of RGBA_8888 bytes is 0xAABBGGRR; returns opaque 0xFFRRGGBB. */
internal fun rgbaLittleEndianToArgb(p: Int): Int =
    0xFF000000.toInt() or ((p and 0xFF) shl 16) or (p and 0xFF00) or ((p ushr 16) and 0xFF)

internal fun luma(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 77 + g * 150 + b * 29) shr 8
}

internal object FrameMatcher {
    const val STILL: Byte = 0
    const val SHIFTED: Byte = 1
    const val UNTRUSTED: Byte = 2

    /** Loose enough for sub-pixel resampling during scroll. Used for presentation. */
    val PRESENT = Tolerance(channel = 14, maxBadSamples = 1)

    /** Strict. Used before a frame replaces the reference, so small changes cannot chain. */
    val PROMOTE = Tolerance(channel = 8, maxBadSamples = 0)

    data class Tolerance(val channel: Int, val maxBadSamples: Int)

    /**
     * Vertical content shift such that `cur(y) == ref(y - shift)`. Positive means the
     * content moved down. Returns 0 when there is no clearly better alignment.
     */
    fun estimateShift(
        ref: RowProfile,
        cur: RowProfile,
        band: ContentBand,
        maxShift: Int = (band.height * 3) / 4,
    ): Int {
        val limit = maxShift.coerceIn(0, band.height - 1)
        val minOverlap = max(16, band.height / 4)
        val still = alignmentError(ref, cur, band, 0, rowStep = 1, minOverlap)
        if (still <= 0.5f || limit == 0) return 0

        // Coarse pass on every other row and every other shift, then refine.
        var best = 0
        var bestError = alignmentError(ref, cur, band, 0, rowStep = 2, minOverlap)
        var s = -limit
        while (s <= limit) {
            if (s != 0) {
                val e = alignmentError(ref, cur, band, s, rowStep = 2, minOverlap)
                if (e < bestError) {
                    bestError = e
                    best = s
                }
            }
            s += 2
        }
        var refined = 0
        var refinedError = still
        for (candidate in (best - 2)..(best + 2)) {
            if (candidate == 0 || abs(candidate) > limit) continue
            val e = alignmentError(ref, cur, band, candidate, rowStep = 1, minOverlap)
            if (e < refinedError) {
                refinedError = e
                refined = candidate
            }
        }
        // Keep "no scroll" unless the shifted fit is clearly better.
        return if (refined != 0 && refinedError < still * 0.7f) refined else 0
    }

    private fun alignmentError(
        ref: RowProfile,
        cur: RowProfile,
        band: ContentBand,
        shift: Int,
        rowStep: Int,
        minOverlap: Int,
    ): Float {
        val from = max(band.top, band.top + shift)
        val to = min(band.bottom, band.bottom + shift)
        if (to - from < minOverlap) return Float.MAX_VALUE
        var sum = 0f
        var n = 0
        var y = from
        while (y < to) {
            val sy = y - shift
            for (b in 0 until cur.bands) {
                sum += abs(cur.at(b, y) - ref.at(b, sy))
            }
            n++
            y += rowStep
        }
        return if (n == 0) Float.MAX_VALUE else sum / (n * cur.bands)
    }

    /** Label every content cell of [cur] as STILL, SHIFTED, or UNTRUSTED against [ref]. */
    fun verifyCells(
        ref: Frame?,
        cur: Frame,
        band: ContentBand,
        shift: Int,
        tolerance: Tolerance,
        cellSize: Int = CELL_SIZE,
    ): CellVerdicts {
        val grid = CellGrid(cur.width, band, cellSize)
        val codes = ByteArray(grid.cellsX * grid.cellsY) { UNTRUSTED }
        if (ref == null || ref.width != cur.width || ref.height != cur.height) {
            return CellVerdicts(grid, codes, shift)
        }
        for (cy in 0 until grid.cellsY) {
            val y0 = grid.cellTop(cy)
            val y1 = grid.cellBottom(cy)
            for (cx in 0 until grid.cellsX) {
                val x0 = grid.cellLeft(cx)
                val x1 = grid.cellRight(cx)
                val index = cy * grid.cellsX + cx
                if (cellMatches(ref, cur, x0, y0, x1, y1, 0, band, tolerance)) {
                    codes[index] = STILL
                } else if (shift != 0 &&
                    y0 - shift >= band.top && y1 - shift <= band.bottom &&
                    cellMatches(ref, cur, x0, y0, x1, y1, shift, band, tolerance)
                ) {
                    codes[index] = SHIFTED
                }
            }
        }
        return CellVerdicts(grid, codes, shift)
    }

    /**
     * Every sampled pixel of [cur] must lie inside the per-channel range of the
     * reference column at the source row and its two neighbours. That range absorbs
     * sub-pixel resampling from a downscaled capture without letting new content in.
     */
    private fun cellMatches(
        ref: Frame,
        cur: Frame,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        shift: Int,
        band: ContentBand,
        tolerance: Tolerance,
    ): Boolean {
        val w = cur.width
        var bad = 0
        var y = y0
        while (y < y1) {
            val sy = y - shift
            val above = max(band.top, sy - 1) * w
            val middle = sy * w
            val below = min(band.bottom - 1, sy + 1) * w
            val curRow = y * w
            var x = x0
            while (x < x1) {
                val c = cur.pixels[curRow + x]
                if (outsideEnvelope(
                        c,
                        ref.pixels[above + x],
                        ref.pixels[middle + x],
                        ref.pixels[below + x],
                        tolerance.channel,
                    )
                ) {
                    bad++
                    if (bad > tolerance.maxBadSamples) return false
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return true
    }

    private fun outsideEnvelope(c: Int, a: Int, b: Int, d: Int, tolerance: Int): Boolean {
        var shiftBits = 0
        while (shiftBits <= 16) {
            val v = (c shr shiftBits) and 0xFF
            val va = (a shr shiftBits) and 0xFF
            val vb = (b shr shiftBits) and 0xFF
            val vd = (d shr shiftBits) and 0xFF
            val lo = min(va, min(vb, vd))
            val hi = max(va, max(vb, vd))
            if (v < lo - tolerance || v > hi + tolerance) return true
            shiftBits += 8
        }
        return false
    }

    const val CELL_SIZE = 16
    private const val SAMPLE_STEP = 2
}

/** Square cells tiling the content band. Edge cells may be smaller. */
internal class CellGrid(val width: Int, val band: ContentBand, val cellSize: Int) {
    val cellsX: Int = (width + cellSize - 1) / cellSize
    val cellsY: Int = (band.height + cellSize - 1) / cellSize

    fun cellLeft(cx: Int) = cx * cellSize
    fun cellRight(cx: Int) = min(width, (cx + 1) * cellSize)
    fun cellTop(cy: Int) = band.top + cy * cellSize
    fun cellBottom(cy: Int) = min(band.bottom, band.top + (cy + 1) * cellSize)
}

internal class CellVerdicts(val grid: CellGrid, val codes: ByteArray, val shift: Int) {
    fun code(cx: Int, cy: Int): Byte = codes[cy * grid.cellsX + cx]

    fun count(code: Byte): Int = codes.count { it == code }

    val allTrusted: Boolean get() = codes.none { it == FrameMatcher.UNTRUSTED }

    /** Capture-space rectangles that still need the classifier, one per untrusted cell row run. */
    fun untrustedRects(): List<CoverRect> {
        val out = ArrayList<CoverRect>()
        for (cy in 0 until grid.cellsY) {
            var cx = 0
            while (cx < grid.cellsX) {
                if (code(cx, cy) != FrameMatcher.UNTRUSTED) {
                    cx++
                    continue
                }
                val start = cx
                while (cx < grid.cellsX && code(cx, cy) == FrameMatcher.UNTRUSTED) cx++
                out += CoverRect(
                    grid.cellLeft(start).toFloat(),
                    grid.cellTop(cy).toFloat(),
                    grid.cellRight(cx - 1).toFloat(),
                    grid.cellBottom(cy).toFloat(),
                )
            }
        }
        return out
    }
}

internal object SafeCompositor {
    const val COVER_COLOR: Int = 0xFF000000.toInt()

    /**
     * Writes the picture the user may see into [out] (width * height ARGB).
     * System-bar rows are copied live; content rows follow the cell verdicts.
     */
    fun compose(
        cur: Frame,
        reference: Reference?,
        verdicts: CellVerdicts,
        out: IntArray,
    ) {
        val w = cur.width
        val grid = verdicts.grid
        val band = grid.band
        // System bars pass through; they are not part of any app's content.
        System.arraycopy(cur.pixels, 0, out, 0, band.top * w)
        System.arraycopy(
            cur.pixels,
            band.bottom * w,
            out,
            band.bottom * w,
            (cur.height - band.bottom) * w,
        )
        val refFrame = reference?.frame
        val freezeUntrusted = verdicts.shift == 0 && refFrame != null
        for (cy in 0 until grid.cellsY) {
            val y0 = grid.cellTop(cy)
            val y1 = grid.cellBottom(cy)
            for (cx in 0 until grid.cellsX) {
                val x0 = grid.cellLeft(cx)
                val x1 = grid.cellRight(cx)
                val code = verdicts.code(cx, cy)
                val sourceShift: Int? = when {
                    code == FrameMatcher.STILL -> 0
                    code == FrameMatcher.SHIFTED -> verdicts.shift
                    freezeUntrusted -> 0
                    else -> null
                }
                val source = if (code == FrameMatcher.UNTRUSTED) refFrame else cur
                if (sourceShift == null || source == null) {
                    fill(out, w, x0, y0, x1, y1, COVER_COLOR)
                    continue
                }
                for (y in y0 until y1) {
                    System.arraycopy(source.pixels, y * w + x0, out, y * w + x0, x1 - x0)
                }
                reference?.covers?.forEach { cover ->
                    val r = cover.rect
                    val top = max(y0.toFloat(), r.top + sourceShift)
                    val bottom = min(y1.toFloat(), r.bottom + sourceShift)
                    val left = max(x0.toFloat(), r.left)
                    val right = min(x1.toFloat(), r.right)
                    if (right > left && bottom > top) {
                        fill(
                            out,
                            w,
                            left.toInt(),
                            top.toInt(),
                            ceilInt(right),
                            ceilInt(bottom),
                            COVER_COLOR,
                        )
                    }
                }
            }
        }
    }

    private fun ceilInt(v: Float): Int = kotlin.math.ceil(v).toInt()

    private fun fill(out: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        for (y in y0 until y1) {
            java.util.Arrays.fill(out, y * w + x0, y * w + x1, color)
        }
    }
}

internal object CoverTracker {
    /**
     * Carries [reference] covers onto the frame described by [verdicts] (strict
     * verdicts, from the frame about to become the reference).
     *
     * A cover follows its content: kept in place over STILL cells, moved by the scroll
     * over SHIFTED cells. If the content under it changed, it is kept only while the
     * model saw it within [holdNanos], so intermittent misses on video do not flicker.
     * On a [fullScan], covers over unchanged content also expire after [holdNanos]
     * without a fresh detection, so a false positive does not stick forever.
     */
    fun inherit(
        reference: Reference,
        verdicts: CellVerdicts,
        nowNanos: Long,
        holdNanos: Long,
        fullScan: Boolean,
    ): List<Cover> {
        val band = verdicts.grid.band
        val out = ArrayList<Cover>()
        for (cover in reference.covers) {
            val recent = nowNanos - cover.lastSeenNanos < holdNanos
            val still = anyCell(verdicts, cover.rect, 0, FrameMatcher.STILL)
            val moved = verdicts.shift != 0 &&
                anyCell(verdicts, cover.rect, verdicts.shift, FrameMatcher.SHIFTED)
            val keepTrusted = !fullScan || recent
            if (still && keepTrusted) out += cover
            if (moved && keepTrusted) {
                shifted(cover, verdicts.shift, band)?.let { out += it }
            }
            if (!still && !moved && recent) {
                shifted(cover, verdicts.shift, band)?.let { out += it }
            }
        }
        return out
    }

    /** Union [inherited] with [detected], merging overlaps; merged covers keep the newest time. */
    fun combine(inherited: List<Cover>, detected: List<Cover>, gap: Float): List<Cover> {
        val all = inherited + detected
        if (all.size <= 1) return all
        val remaining = all.toMutableList()
        val merged = ArrayList<Cover>()
        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var grew: Boolean
            do {
                grew = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    if (near(current.rect, other.rect, gap)) {
                        current = Cover(
                            CoverRect(
                                min(current.rect.left, other.rect.left),
                                min(current.rect.top, other.rect.top),
                                max(current.rect.right, other.rect.right),
                                max(current.rect.bottom, other.rect.bottom),
                            ),
                            max(current.lastSeenNanos, other.lastSeenNanos),
                        )
                        iterator.remove()
                        grew = true
                    }
                }
            } while (grew)
            merged += current
        }
        return merged
    }

    private fun near(a: CoverRect, b: CoverRect, gap: Float): Boolean =
        a.left <= b.right + gap && a.right + gap >= b.left &&
            a.top <= b.bottom + gap && a.bottom + gap >= b.top

    private fun shifted(cover: Cover, shift: Int, band: ContentBand): Cover? {
        val top = (cover.rect.top + shift).coerceAtLeast(band.top.toFloat())
        val bottom = (cover.rect.bottom + shift).coerceAtMost(band.bottom.toFloat())
        if (bottom <= top) return null
        return cover.copy(rect = cover.rect.copy(top = top, bottom = bottom))
    }

    private fun anyCell(verdicts: CellVerdicts, rect: CoverRect, shift: Int, code: Byte): Boolean {
        val grid = verdicts.grid
        val top = rect.top + shift - grid.band.top
        val bottom = rect.bottom + shift - grid.band.top
        val cy0 = (top / grid.cellSize).toInt().coerceAtLeast(0)
        val cy1 = ((bottom - 1) / grid.cellSize).toInt().coerceAtMost(grid.cellsY - 1)
        val cx0 = (rect.left / grid.cellSize).toInt().coerceAtLeast(0)
        val cx1 = ((rect.right - 1) / grid.cellSize).toInt().coerceAtMost(grid.cellsX - 1)
        for (cy in cy0..cy1) {
            for (cx in cx0..cx1) {
                if (verdicts.code(cx, cy) == code) return true
            }
        }
        return false
    }
}
