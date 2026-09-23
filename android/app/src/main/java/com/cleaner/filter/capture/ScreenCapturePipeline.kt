package com.cleaner.filter.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import com.cleaner.filter.FilterEngine
import com.cleaner.filter.ml.DetectionBox
import com.cleaner.filter.ml.NsfwClassifier
import com.cleaner.filter.ml.RegionI
import com.cleaner.filter.ml.scanGrid
import com.cleaner.filter.ml.tilesTouching
import com.cleaner.filter.settings.FilterSettings
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class PipelineMetrics(
    val fps: Float = 0f,
    val inferenceMs: Long = 0,
    val framesSkipped: Long = 0,
    val framesDropped: Long = 0,
    val framesProcessed: Long = 0,
    val presentationDelayMs: Long = 50,
)

/**
 * Either a full safe composite to mirror ([bitmap]), or, when the overlay cannot be kept
 * out of capture, cover boxes in screen pixels to draw over the live screen ([boxes]).
 */
data class ProcessedFrame(
    val bitmap: Bitmap?,
    val boxes: List<DetectionBox>,
    val metrics: PipelineMetrics,
)

/**
 * Capture → safe composite → overlay, with one classifier working on the newest frame.
 *
 * Presentation runs on the capture thread at up to ~30 fps and never waits for the
 * model: each frame is checked against the last classified frame ([Reference]) and only
 * pixels that match classified content are shown (see SafeFrame.kt). The classifier
 * thread promotes new references, running the model only on tiles that changed.
 */
class ScreenCapturePipeline(
    private val classifier: NsfwClassifier,
    private val clock: PresentationClock,
    private val onFrame: (ProcessedFrame) -> Unit,
) {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private var inbox: LatestFrameInbox<Frame>? = null

    @Volatile
    private var settings: FilterSettings = FilterSettings()

    private var width = 0
    private var height = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var band = ContentBand(0, 1)

    /** Last classified frame. Written by the classifier, read by the capture thread. */
    @Volatile
    private var reference: Reference? = null

    // Capture-thread state.
    private var latestFrame: Frame? = null
    private var composeBuffer = IntArray(0)
    private var drainScheduled = false
    private var lastDrainNanos = 0L

    // Classifier-thread state.
    private var lastFullScanNanos = 0L

    // Exclusion self-check (capture thread).
    private var checkRunning = false
    private var checkStartNanos = 0L
    private var checkHits = 0

    private val framesProcessed = AtomicLong(0)
    private val framesSkipped = AtomicLong(0)
    private var presentWindowStart = System.nanoTime()
    private var presentWindowCount = 0
    private var untrustedWindowSum = 0L
    @Volatile
    private var presentFps = 0f
    @Volatile
    private var lastInferenceMs = 0L

    private val drainRunnable = Runnable {
        drainScheduled = false
        drain()
    }

    private val finishCheckRunnable = Runnable { finishExclusionCheck(timedOut = true) }

    fun updateSettings(newSettings: FilterSettings) {
        settings = newSettings
        clock.setDelayMs(newSettings.presentationDelayMs)
    }

    fun start(
        mediaProjection: MediaProjection,
        metrics: DisplayMetrics,
    ) {
        stop()
        projection = mediaProjection
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val shortEdge = minOf(screenWidth, screenHeight).coerceAtLeast(1)
        val factor = (CAPTURE_SHORT_EDGE.toFloat() / shortEdge).coerceAtMost(1f)
        width = (screenWidth * factor).toInt().coerceAtLeast(64)
        height = (screenHeight * factor).toInt().coerceAtLeast(64)
        band = ContentBand.forFrame(height)
        composeBuffer = IntArray(width * height)
        reference = null
        latestFrame = null
        lastFullScanNanos = 0L
        checkRunning = false

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        captureThread = HandlerThread("cleaner-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        inbox = LatestFrameInbox { }
        running.set(true)
        worker = Thread({ classifyLoop() }, "cleaner-classify").also { it.start() }

        mediaProjection.registerCallback(projectionCallback, captureHandler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CleanerCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler,
        )
        imageReader!!.setOnImageAvailableListener({ scheduleDrain() }, captureHandler)
        Log.i(TAG, "capture ${width}x$height band=${band.top}..${band.bottom} screen=${screenWidth}x$screenHeight")
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
        }
    }

    /** Converts at most one image per frame interval; the newest image always wins. */
    private fun scheduleDrain() {
        if (drainScheduled) return
        val handler = captureHandler ?: return
        drainScheduled = true
        val waitMs = ((lastDrainNanos + FRAME_INTERVAL_NS - System.nanoTime()) / 1_000_000L)
            .coerceAtLeast(0L)
        handler.postDelayed(drainRunnable, waitMs)
    }

    private fun drain() {
        if (!running.get()) return
        val image = try {
            imageReader?.acquireLatestImage()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "acquire failed: ${error.message}")
            null
        } ?: return
        lastDrainNanos = System.nanoTime()
        val frame = try {
            imageToFrame(image)
        } catch (error: Exception) {
            Log.w(TAG, "dropped a capture frame: ${error.message}")
            null
        } finally {
            image.close()
        } ?: return
        latestFrame = frame
        stepExclusionCheck(frame)
        presentLatest()
        inbox?.offer(frame)
    }

    /**
     * Re-evaluates the newest frame without waiting for a new capture: static screens
     * send no frames, but the exclusion check or a resumed overlay still needs one.
     */
    fun poke() {
        captureHandler?.post {
            val frame = latestFrame ?: return@post
            stepExclusionCheck(frame)
            presentLatest()
        }
    }

    /** Builds the safe composite of the newest frame and hands it to the overlay. */
    private fun presentLatest() {
        if (!FilterEngine.mirrorProcessedFrames || FilterEngine.overlayPausedForOwnUi) return
        val frame = latestFrame ?: return
        val ref = reference
        val shift = if (ref != null) {
            FrameMatcher.estimateShift(ref.profile, RowProfile.of(frame, band), band)
        } else {
            0
        }
        val verdicts = FrameMatcher.verifyCells(ref?.frame, frame, band, shift, FrameMatcher.PRESENT)
        SafeCompositor.compose(frame, ref, verdicts, composeBuffer)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(composeBuffer, 0, width, 0, 0, width, height)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "present bitmap failed: ${error.message}")
            return
        }
        notePresented(verdicts.count(FrameMatcher.UNTRUSTED), verdicts.codes.size, shift)
        onFrame(ProcessedFrame(bitmap = bitmap, boxes = emptyList(), metrics = currentMetrics()))
    }

    private fun classifyLoop() {
        val box = inbox ?: return
        while (running.get()) {
            val frame = box.take() ?: break
            if (!running.get()) break
            try {
                classify(frame)
            } catch (error: Exception) {
                Log.w(TAG, "classify failed: ${error.message}")
            }
        }
    }

    /**
     * Makes [frame] the new reference. Cells that strictly match the old reference keep
     * its covers; the model runs only on tiles over the rest, plus a periodic full scan
     * so misses and stale covers get corrected.
     */
    private fun classify(frame: Frame) {
        val now = System.nanoTime()
        val ref = reference
        val grid = scanGrid(frame.width, band.top, band.bottom)
        val fullScan = ref == null || now - lastFullScanNanos >= FULL_SCAN_INTERVAL_NS
        val verdicts = if (ref != null) {
            val shift = FrameMatcher.estimateShift(ref.profile, RowProfile.of(frame, band), band)
            FrameMatcher.verifyCells(ref.frame, frame, band, shift, FrameMatcher.PROMOTE)
        } else {
            null
        }
        if (!fullScan && verdicts != null && verdicts.shift == 0 &&
            verdicts.count(FrameMatcher.STILL) == verdicts.codes.size
        ) {
            framesSkipped.incrementAndGet()
            return
        }
        val tiles = when {
            fullScan || verdicts == null -> grid
            else -> tilesTouching(
                grid,
                verdicts.untrustedRects().map {
                    RegionI(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                },
            )
        }
        val current = settings
        val result = if (current.visualNudityEnabled && tiles.isNotEmpty()) {
            classifier.classifyTiles(
                frame.pixels,
                frame.width,
                tiles,
                current.visualSensitivity,
                current.coverPartialNudity,
            )
        } else {
            null
        }
        val shortSide = minOf(frame.width, frame.height).toFloat()
        val detected = mergeCovers(
            result?.boxes.orEmpty().map { box ->
                expandCover(
                    box.bounds.left,
                    box.bounds.top,
                    box.bounds.right,
                    box.bounds.bottom,
                    frame.width.toFloat(),
                    frame.height.toFloat(),
                    label = box.label,
                )
            },
            gap = shortSide * 0.08f,
        ).mapNotNull { clampToBand(it) }.map { Cover(it, now) }
        val inherited = if (ref != null && verdicts != null) {
            CoverTracker.inherit(ref, verdicts, now, COVER_HOLD_NS, fullScan)
        } else {
            emptyList()
        }
        val covers = CoverTracker.combine(inherited, detected, gap = 0f)
        reference = Reference(frame, band, covers)
        if (fullScan) lastFullScanNanos = now
        lastInferenceMs = result?.inferenceMs ?: 0L
        framesProcessed.incrementAndGet()
        if (detected.isNotEmpty() || framesProcessed.get() % 15L == 1L) {
            Log.i(
                TAG,
                "reference tiles=${tiles.size}/${grid.size} full=$fullScan " +
                    "shift=${verdicts?.shift ?: 0} detected=${detected.size} covers=${covers.size} " +
                    "inferMs=$lastInferenceMs",
            )
        }
        // Static screens send no new frames: redraw so newly classified cells appear.
        captureHandler?.post { presentLatest() }
        if (FilterEngine.boxOnlyFallback) {
            onFrame(
                ProcessedFrame(
                    bitmap = null,
                    boxes = covers.map { cover -> toScreenBox(cover.rect, result?.score ?: 1f) },
                    metrics = currentMetrics(),
                ),
            )
        }
    }

    private fun toScreenBox(rect: CoverRect, score: Float): DetectionBox {
        val sx = screenWidth.toFloat() / width
        val sy = screenHeight.toFloat() / height
        return DetectionBox(
            RectF(rect.left * sx, rect.top * sy, rect.right * sx, rect.bottom * sy),
            score,
            "nsfw",
        )
    }

    private fun clampToBand(cover: CoverRect): CoverRect? {
        val top = cover.top.coerceAtLeast(band.top.toFloat())
        val bottom = cover.bottom.coerceAtMost(band.bottom.toFloat())
        val left = cover.left.coerceAtLeast(0f)
        val right = cover.right.coerceAtMost(width.toFloat())
        if (bottom <= top || right <= left) return null
        return CoverRect(left, top, right, bottom)
    }

    /**
     * While [FilterEngine.exclusionState] is CHECKING, show the probe marker and look for
     * it in captured frames. Seen twice → the overlay is being captured. Not seen before
     * the deadline → excluded. Frames only arrive when the screen changes, so a marker
     * that is truly excluded usually produces no frames at all, which is the pass case.
     */
    private fun stepExclusionCheck(frame: Frame) {
        if (FilterEngine.exclusionState != FilterEngine.ExclusionState.CHECKING) {
            if (checkRunning) abortExclusionCheck()
            return
        }
        if (FilterEngine.overlayPausedForOwnUi) {
            // The marker is hidden with the rest of the overlay; a pass would mean nothing.
            if (checkRunning) abortExclusionCheck()
            return
        }
        if (!checkRunning) {
            startExclusionCheck()
            return
        }
        if (System.nanoTime() - checkStartNanos < CHECK_GRACE_NS) return
        val marker = ExclusionProbe.screenRect(screenWidth, screenHeight)
        val sx = width.toFloat() / screenWidth
        val sy = height.toFloat() / screenHeight
        val inCapture = CoverRect(marker.left * sx, marker.top * sy, marker.right * sx, marker.bottom * sy)
        if (ExclusionProbe.markerVisible(frame, inCapture)) {
            checkHits++
            if (checkHits >= 2) finishExclusionCheck(timedOut = false)
        }
    }

    private fun startExclusionCheck() {
        checkRunning = true
        checkHits = 0
        checkStartNanos = System.nanoTime()
        val marker = ExclusionProbe.screenRect(screenWidth, screenHeight)
        FilterEngine.showExclusionMarker(RectF(marker.left, marker.top, marker.right, marker.bottom))
        captureHandler?.postDelayed(finishCheckRunnable, CHECK_DURATION_MS)
        Log.i(TAG, "capture exclusion check started")
    }

    private fun abortExclusionCheck() {
        checkRunning = false
        captureHandler?.removeCallbacks(finishCheckRunnable)
        FilterEngine.hideExclusionMarker()
    }

    private fun finishExclusionCheck(timedOut: Boolean) {
        if (!checkRunning) return
        if (FilterEngine.overlayPausedForOwnUi) {
            abortExclusionCheck()
            return
        }
        checkRunning = false
        captureHandler?.removeCallbacks(finishCheckRunnable)
        FilterEngine.hideExclusionMarker()
        val excluded = timedOut && checkHits < 2
        Log.i(TAG, "capture exclusion check excluded=$excluded hits=$checkHits")
        FilterEngine.onExclusionChecked(excluded)
        if (excluded) presentLatest()
    }

    private fun notePresented(untrusted: Int, cells: Int, shift: Int) {
        presentWindowCount++
        untrustedWindowSum += untrusted
        val now = System.nanoTime()
        val elapsed = now - presentWindowStart
        if (elapsed >= 1_000_000_000L) {
            presentFps = presentWindowCount * 1_000_000_000f / elapsed
            Log.i(
                TAG,
                "presentFps=${"%.1f".format(presentFps)} " +
                    "untrustedCells=${untrustedWindowSum / presentWindowCount}/$cells shift=$shift " +
                    "refs=${framesProcessed.get()} inferMs=$lastInferenceMs",
            )
            presentWindowCount = 0
            untrustedWindowSum = 0
            presentWindowStart = now
        }
    }

    private fun currentMetrics() = PipelineMetrics(
        fps = presentFps,
        inferenceMs = lastInferenceMs,
        framesSkipped = framesSkipped.get(),
        framesDropped = inbox?.droppedCount() ?: 0,
        framesProcessed = framesProcessed.get(),
        presentationDelayMs = clock.delayMs,
    )

    /** RGBA_8888 image → opaque ARGB frame (alpha forced so the overlay never shows through). */
    private fun imageToFrame(image: Image): Frame {
        val plane = image.planes[0]
        val ints = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val rowInts = plane.rowStride / 4
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            ints.position(y * rowInts)
            ints.get(pixels, y * width, width)
        }
        for (i in pixels.indices) {
            pixels[i] = rgbaLittleEndianToArgb(pixels[i])
        }
        return Frame(width, height, pixels, System.nanoTime())
    }

    fun stop() {
        if (!running.getAndSet(false) && inbox == null) return
        captureHandler?.removeCallbacks(drainRunnable)
        if (checkRunning) abortExclusionCheck()
        inbox?.close()
        inbox = null
        worker?.join(1_000)
        worker = null
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        projection?.stop()
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        reference = null
        latestFrame = null
        drainScheduled = false
        lastDrainNanos = 0L
        lastFullScanNanos = 0L
        presentFps = 0f
        presentWindowCount = 0
        untrustedWindowSum = 0
        presentWindowStart = System.nanoTime()
    }

    companion object {
        private const val TAG = "CleanerFilter"

        /**
         * Capture short side. 576 lets two 320 model tiles span the width at native
         * resolution (1.8x the old 320-wide capture), and keeps the mirror sharp.
         */
        private const val CAPTURE_SHORT_EDGE = 576
        private const val FRAME_INTERVAL_NS = 33_000_000L
        /** Re-run every tile this often, so misses and stale covers get corrected. */
        private const val FULL_SCAN_INTERVAL_NS = 2_000_000_000L
        /** A cover survives misses (and in-place changes such as video) this long after its last detection. */
        private const val COVER_HOLD_NS = 6_000_000_000L
        private const val CHECK_DURATION_MS = 800L
        private const val CHECK_GRACE_NS = 150_000_000L
    }
}
