package com.cleaner.filter.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SafeFrameTest {
    private val w = 96
    private val h = 320
    private val band = ContentBand.forFrame(h)
    private val black = SafeCompositor.COVER_COLOR

    /** A long "page" of textured content; frames are windows into it. */
    private val page: IntArray = Random(7).let { rnd ->
        IntArray(w * PAGE_H) { i ->
            val x = i % w
            val y = i / w
            // Smooth-ish blocks plus fine texture, like photos and text on a page.
            val block = ((x / 12) * 31 + (y / 10) * 17) % 200
            val v = (block + rnd.nextInt(40)).coerceIn(0, 255)
            0xFF000000.toInt() or (v shl 16) or (((v * 3) % 256) shl 8) or ((255 - v))
        }
    }

    /** Frame showing page rows starting at [scroll]; system bars are a fixed colour. */
    private fun frameAt(scroll: Int, content: IntArray = page): Frame {
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (y < band.top || y >= band.bottom) {
                    0xFF202020.toInt()
                } else {
                    content[(scroll + y - band.top) * w + x]
                }
            }
        }
        return Frame(w, h, px)
    }

    private fun reference(frame: Frame, covers: List<Cover> = emptyList()) = Reference(frame, band, covers)

    @Test
    fun estimatesScrollDistance() {
        val ref = reference(frameAt(100))
        val cur = frameAt(137) // page scrolled down 37 rows → content moved up
        val shift = FrameMatcher.estimateShift(ref.profile, RowProfile.of(cur, band), band)
        assertEquals(-37, shift)
    }

    @Test
    fun identicalFrameIsStillEverywhere() {
        val ref = reference(frameAt(50))
        val verdicts = FrameMatcher.verifyCells(ref.frame, frameAt(50), band, 0, FrameMatcher.PROMOTE)
        assertEquals(verdicts.codes.size, verdicts.count(FrameMatcher.STILL))
        assertTrue(verdicts.allTrusted)
    }

    @Test
    fun scrolledFrameTrustsOverlapAndNotTheNewStrip() {
        val ref = reference(frameAt(100))
        val cur = frameAt(140)
        val verdicts = FrameMatcher.verifyCells(ref.frame, cur, band, -40, FrameMatcher.PRESENT)
        val grid = verdicts.grid
        for (cy in 0 until grid.cellsY) {
            for (cx in 0 until grid.cellsX) {
                val code = verdicts.code(cx, cy)
                // Rows whose source (y + 40) lies beyond the old band are brand new.
                val newRows = grid.cellBottom(cy) + 40 > band.bottom
                if (newRows) {
                    assertEquals("cell $cx,$cy is new content", FrameMatcher.UNTRUSTED, code)
                } else {
                    assertEquals("cell $cx,$cy scrolled", FrameMatcher.SHIFTED, code)
                }
            }
        }
    }

    @Test
    fun newlyExposedStripIsNeverShownLive() {
        val ref = reference(frameAt(100))
        // New content below the old reference is a distinctive "unsafe" colour.
        val unsafe = 0xFFFFC0A0.toInt()
        val content = page.copyOf()
        val oldEnd = 100 + band.height
        for (i in oldEnd * w until content.size) content[i] = unsafe
        val cur = frameAt(140, content)
        val shift = FrameMatcher.estimateShift(ref.profile, RowProfile.of(cur, band), band)
        val verdicts = FrameMatcher.verifyCells(ref.frame, cur, band, shift, FrameMatcher.PRESENT)
        val out = IntArray(w * h)
        SafeCompositor.compose(cur, ref, verdicts, out)

        for (i in out.indices) {
            if (cur.pixels[i] == unsafe) {
                assertTrue("unclassified pixel $i leaked", out[i] != unsafe)
            }
        }
        // The scrolled part is still live, so scrolling feels native.
        val liveRow = band.top + 10
        for (x in 0 until w) assertEquals(cur.pixels[liveRow * w + x], out[liveRow * w + x])
    }

    @Test
    fun inPlaceChangeShowsClassifiedPixelsNotNewOnes() {
        val refFrame = frameAt(0)
        val ref = reference(refFrame)
        val cur = frameAt(0).let { f ->
            val px = f.pixels.copyOf()
            // A video / GIF region changes in place.
            for (y in 120 until 180) for (x in 16 until 64) px[y * w + x] = 0xFF00FFFF.toInt()
            Frame(w, h, px)
        }
        val verdicts = FrameMatcher.verifyCells(ref.frame, cur, band, 0, FrameMatcher.PRESENT)
        val out = IntArray(w * h)
        SafeCompositor.compose(cur, ref, verdicts, out)
        for (y in 120 until 180) {
            for (x in 16 until 64) {
                assertEquals(refFrame.pixels[y * w + x], out[y * w + x])
            }
        }
    }

    @Test
    fun coversMoveWithScrolledContent() {
        val cover = Cover(CoverRect(20f, 150f, 60f, 200f), lastSeenNanos = 0L)
        val ref = reference(frameAt(100), listOf(cover))
        val cur = frameAt(130) // content moved up 30
        val verdicts = FrameMatcher.verifyCells(ref.frame, cur, band, -30, FrameMatcher.PRESENT)
        val out = IntArray(w * h)
        SafeCompositor.compose(cur, ref, verdicts, out)
        for (y in 120 until 170) {
            for (x in 20 until 60) {
                assertEquals("($x,$y) should be covered", black, out[y * w + x])
            }
        }
        // Where the cover used to be, content is live again.
        val y = 190
        val x = 40
        assertEquals(cur.pixels[y * w + x], out[y * w + x])
    }

    @Test
    fun withoutAReferenceContentIsBlackAndBarsAreLive() {
        val cur = frameAt(0)
        val verdicts = FrameMatcher.verifyCells(null, cur, band, 0, FrameMatcher.PRESENT)
        val out = IntArray(w * h)
        SafeCompositor.compose(cur, null, verdicts, out)
        for (y in 0 until h) {
            val expected = if (y < band.top || y >= band.bottom) cur.pixels[y * w] else black
            assertEquals("row $y", expected, out[y * w])
        }
    }

    @Test
    fun subPixelScrollIsStillTrustedForPresentation() {
        // Downscaled capture: a 5-row display scroll can land 2.5 rows apart.
        val ref = frameAt(100)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = page[(100 + y - band.top + 2).coerceIn(0, PAGE_H - 1) * w + x]
                val b = page[(100 + y - band.top + 3).coerceIn(0, PAGE_H - 1) * w + x]
                px[y * w + x] = if (y < band.top || y >= band.bottom) 0xFF202020.toInt() else average(a, b)
            }
        }
        val cur = Frame(w, h, px)
        val refProfile = RowProfile.of(ref, band)
        val shift = FrameMatcher.estimateShift(refProfile, RowProfile.of(cur, band), band)
        assertTrue("shift was $shift", shift == -2 || shift == -3)
        val verdicts = FrameMatcher.verifyCells(ref, cur, band, shift, FrameMatcher.PRESENT)
        val trusted = verdicts.codes.size - verdicts.count(FrameMatcher.UNTRUSTED)
        assertTrue("only $trusted of ${verdicts.codes.size} cells trusted", trusted > verdicts.codes.size * 0.8)
    }

    @Test
    fun strictToleranceRejectsSlowFades() {
        val ref = frameAt(0)
        val faded = Frame(w, h, IntArray(w * h) { i -> brighten(ref.pixels[i], 12) })
        val loose = FrameMatcher.verifyCells(ref, faded, band, 0, FrameMatcher.PRESENT)
        val strict = FrameMatcher.verifyCells(ref, faded, band, 0, FrameMatcher.PROMOTE)
        assertTrue(loose.count(FrameMatcher.STILL) > 0)
        assertEquals(0, strict.count(FrameMatcher.STILL))
    }

    @Test
    fun untrustedRectsSpanRunsOfCells() {
        val ref = frameAt(0)
        val cur = Frame(w, h, ref.pixels.copyOf().also { px ->
            for (y in band.top until band.top + 16) for (x in 0 until w) px[y * w + x] = 0xFFFFFFFF.toInt()
        })
        val verdicts = FrameMatcher.verifyCells(ref, cur, band, 0, FrameMatcher.PROMOTE)
        val rects = verdicts.untrustedRects()
        assertEquals(1, rects.size)
        assertEquals(CoverRect(0f, band.top.toFloat(), w.toFloat(), (band.top + 16).toFloat()), rects[0])
    }

    @Test
    fun coverTrackerKeepsFollowsAndExpires() {
        val now = 10_000_000_000L
        val hold = 6_000_000_000L
        val fresh = Cover(CoverRect(10f, 100f, 50f, 140f), now - 1_000_000_000L)
        val stale = Cover(CoverRect(10f, 100f, 50f, 140f), now - 9_000_000_000L)

        val still = frameAt(0)
        val stillVerdicts = FrameMatcher.verifyCells(still, frameAt(0), band, 0, FrameMatcher.PROMOTE)
        // Unchanged content keeps its cover between full scans, even when old.
        assertEquals(1, CoverTracker.inherit(reference(still, listOf(stale)), stillVerdicts, now, hold, false).size)
        // A full scan drops a cover the model has not confirmed for [hold].
        assertTrue(CoverTracker.inherit(reference(still, listOf(stale)), stillVerdicts, now, hold, true).isEmpty())
        assertEquals(1, CoverTracker.inherit(reference(still, listOf(fresh)), stillVerdicts, now, hold, true).size)

        val scrolledVerdicts = FrameMatcher.verifyCells(still, frameAt(20), band, -20, FrameMatcher.PROMOTE)
        val moved = CoverTracker.inherit(reference(still, listOf(fresh)), scrolledVerdicts, now, hold, false)
        assertTrue(moved.any { it.rect.top == 80f && it.rect.bottom == 120f })

        // Content under the cover replaced: kept only while recent.
        val replaced = Frame(w, h, IntArray(w * h) { 0xFF0000FF.toInt() })
        val replacedVerdicts = FrameMatcher.verifyCells(still, replaced, band, 0, FrameMatcher.PROMOTE)
        assertEquals(1, CoverTracker.inherit(reference(still, listOf(fresh)), replacedVerdicts, now, hold, false).size)
        assertTrue(CoverTracker.inherit(reference(still, listOf(stale)), replacedVerdicts, now, hold, false).isEmpty())
    }

    @Test
    fun combineMergesOverlapsAndKeepsNewestTime() {
        val a = Cover(CoverRect(0f, 0f, 10f, 10f), 1L)
        val b = Cover(CoverRect(5f, 5f, 20f, 20f), 9L)
        val c = Cover(CoverRect(50f, 50f, 60f, 60f), 3L)
        val merged = CoverTracker.combine(listOf(a), listOf(b, c), gap = 0f)
        assertEquals(2, merged.size)
        assertTrue(merged.contains(Cover(CoverRect(0f, 0f, 20f, 20f), 9L)))
        assertTrue(merged.contains(c))
    }

    @Test
    fun rgbaBytesBecomeOpaqueArgb() {
        // Bytes R=0x11 G=0x22 B=0x33 A=0x00 read little-endian.
        assertEquals(0xFF112233.toInt(), rgbaLittleEndianToArgb(0x00332211))
        assertEquals(0xFFFFFFFF.toInt(), rgbaLittleEndianToArgb(0xFFFFFFFF.toInt()))
    }

    @Test
    fun exclusionProbeFindsItsMarkerOnlyWhenDrawn() {
        val rect = CoverRect(8f, 100f, 40f, 132f)
        val plain = frameAt(0)
        assertFalse(ExclusionProbe.markerVisible(plain, rect))
        val px = plain.pixels.copyOf()
        val bw = (rect.right - rect.left) / ExclusionProbe.GRID
        val bh = (rect.bottom - rect.top) / ExclusionProbe.GRID
        for (y in rect.top.toInt() until rect.bottom.toInt()) {
            for (x in rect.left.toInt() until rect.right.toInt()) {
                val bx = ((x - rect.left) / bw).toInt()
                val by = ((y - rect.top) / bh).toInt()
                px[y * w + x] = ExclusionProbe.blockColor(bx, by)
            }
        }
        assertTrue(ExclusionProbe.markerVisible(Frame(w, h, px), rect))
    }

    private fun average(a: Int, b: Int): Int {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        val r = (ch(a, 16) + ch(b, 16)) / 2
        val g = (ch(a, 8) + ch(b, 8)) / 2
        val bl = (ch(a, 0) + ch(b, 0)) / 2
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    private fun brighten(p: Int, d: Int): Int {
        fun ch(s: Int) = (((p shr s) and 0xFF) + d).coerceAtMost(255)
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        private const val PAGE_H = 800
    }
}
