package com.cleaner.filter.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.cleaner.filter.ml.ClassificationResult
import com.cleaner.filter.ml.DetectionBox
import com.cleaner.filter.ml.NsfwClassifier
import com.cleaner.filter.settings.FilterSettings
import com.cleaner.filter.text.TextHit
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

data class ProcessedFrame(
    val bitmap: Bitmap?,
    val classification: ClassificationResult?,
    val textHits: List<TextHit>,
    val coverAll: Boolean,
    val captureNanos: Long,
    val metrics: PipelineMetrics,
)

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
    private var inbox: LatestFrameInbox<Bitmap>? = null

    private var settings: FilterSettings = FilterSettings()
    private var lastHash: Long = 0
    private var hasHash = false
    /** Separate hash for the present path so scroll widen does not wait on classify. */
    private var lastPresentHash: Long = 0
    private var hasPresentHash = false
    private var probeWasShowing = false
    private val coverLock = Any()
    @Volatile
    private var heldCapture = emptyList<CoverRect>()
    /** Precise boxes once classify finishes; shown after the scene stays still. */
    @Volatile
    private var preciseCapture = emptyList<CoverRect>()
    /**
     * WIDE = content-band cover (fast, safe, chrome still visible).
     * TIGHT = only expanded detection boxes (after the scene is stable).
     */
    @Volatile
    private var coverWide = true
    private var stableTightStreak = 0
    private var sceneChangeStreak = 0
    /** Keep the blocked stream up after a miss so scroll stays covered. */
    @Volatile
    private var unsafeUntilNanos = 0L
    /** Require several clear frames before uncovering — threshold flicker must not flash skin. */
    private var clearStreak = 0
    private val hasClassifiedOnce = AtomicBoolean(false)
    private var lastPresentNanos = 0L
    private var lastClassifyNanos = 0L
    private var lastFullScanNanos = 0L
    private var framesProcessed = AtomicLong(0)
    private var framesSkipped = AtomicLong(0)
    private var framesPresented = AtomicLong(0)
    private var fpsWindowStart = System.nanoTime()
    private var fpsWindowCount = 0
    private var currentFps = 0f
    private var presentFpsWindowStart = System.nanoTime()
    private var presentFpsWindowCount = 0
    private var currentPresentFps = 0f
    private val coverPaint = Paint().apply { color = Color.BLACK }
    /** Last covered frame kept for ~30fps re-present when MediaProjection goes quiet. */
    private var lastCoveredBitmap: Bitmap? = null
    private val presentTick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            try {
                representLastCovered()
            } finally {
                captureHandler?.postDelayed(this, 33L)
            }
        }
    }

    private var width = 0
    private var height = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 0

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
        density = metrics.densityDpi
        val shortEdge = minOf(screenWidth, screenHeight).coerceAtLeast(1)
        // Keep enough resolution that 320n tiles still see body detail.
        val factor = ANALYSIS_SHORT_EDGE.toFloat() / shortEdge
        width = (screenWidth * factor).toInt().coerceAtLeast(64)
        height = (screenHeight * factor).toInt().coerceAtLeast(64)

        // Two buffers: producer keeps the newest, reader drops the rest via acquireLatestImage.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        captureThread = HandlerThread("cleaner-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        inbox = LatestFrameInbox { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        running.set(true)
        synchronized(coverLock) {
            // Cover content immediately — never wait for the first NudeNet pass.
            coverWide = true
            preciseCapture = emptyList()
            heldCapture = listOf(contentBandCover(width, height))
            stableTightStreak = 0
            sceneChangeStreak = 0
            clearStreak = 0
            unsafeUntilNanos = 0L
        }
        hasClassifiedOnce.set(false)
        hasHash = false
        hasPresentHash = false
        worker = Thread({ classifyLoop() }, "cleaner-classify").also { it.start() }
        captureHandler?.post(presentTick)

        mediaProjection.registerCallback(projectionCallback, captureHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "CleanerCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            captureHandler,
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            processImage(image)
        }, captureHandler)
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stop()
        }
    }

    private fun processImage(image: Image) {
        try {
            val bitmap = imageToBitmap(image) ?: return
            val mirror = com.cleaner.filter.FilterEngine.mirrorProcessedFrames
            if (mirror && !com.cleaner.filter.FilterEngine.overlayPausedForOwnUi) {
                // Widen on the present thread immediately when content moves.
                widenIfSceneMoved(bitmap)
                val covers = synchronized(coverLock) { coversForPresent(bitmap.width, bitmap.height) }
                val tightHoles = synchronized(coverLock) {
                    !coverWide && preciseCapture.isNotEmpty()
                }
                if (tightHoles) {
                    // Never present a live frame with region holes — scroll would
                    // slide uncovered pixels through. Ticker keeps last covered frame.
                } else {
                    // Content band / startup: live frame is fully masked → safe to show.
                    maybePresentProcessed(bitmap, covers, coverAll = false)
                }
            }
            val box = inbox
            if (box == null) {
                bitmap.recycle()
            } else {
                box.offer(bitmap)
            }
        } catch (error: Exception) {
            Log.w(TAG, "dropped a capture frame: ${error.message}")
        } finally {
            image.close()
        }
    }

    /** Capture-space chrome strips ignored when hashing (clock / nav icons). */
    private fun chromeIgnore(captureW: Int, captureH: Int): List<CoverRect> = listOf(
        CoverRect(0f, 0f, captureW.toFloat(), contentTopInset(captureH)),
        CoverRect(
            0f,
            captureH - contentBottomInset(captureH),
            captureW.toFloat(),
            captureH.toFloat(),
        ),
    )

    /**
     * Present-path scene check. If we are holding region covers and the content moved,
     * switch to the content band on this frame so nothing slides out from under boxes.
     */
    private fun widenIfSceneMoved(bitmap: Bitmap) {
        val hash = FrameHasher.averageHash(bitmap, chromeIgnore(bitmap.width, bitmap.height))
        synchronized(coverLock) {
            val distance = if (hasPresentHash) {
                FrameHasher.hammingDistance(hash, lastPresentHash)
            } else {
                0
            }
            lastPresentHash = hash
            hasPresentHash = true
            if (preciseCapture.isEmpty() && System.nanoTime() >= unsafeUntilNanos) return
            if (distance > SIMILAR_HASH_THRESHOLD) {
                if (!coverWide) {
                    Log.i(TAG, "present widen on motion dist=$distance")
                }
                coverWide = true
                stableTightStreak = 0
                heldCapture = listOf(contentBandCover(bitmap.width, bitmap.height))
            }
        }
    }

    /**
     * Never-seen present policy:
     * - Before first classify → content band (status/nav stay clear).
     * - Sticky hit → precise boxes (or content band while scrolling/wide).
     * - Clear only after sustained safe misses.
     */
    private fun coversForPresent(captureW: Int, captureH: Int): List<CoverRect> {
        synchronized(coverLock) {
            val band = listOf(contentBandCover(captureW, captureH))
            if (!hasClassifiedOnce.get()) return band
            val holding = System.nanoTime() < unsafeUntilNanos
            if (preciseCapture.isNotEmpty() && (holding || heldCapture.isNotEmpty())) {
                return if (coverWide) band else preciseCapture
            }
            if (coverWide && heldCapture.isNotEmpty()) return band
            return emptyList()
        }
    }

    private fun maybePresentProcessed(
        source: Bitmap,
        covers: List<CoverRect>,
        coverAll: Boolean,
    ) {
        val now = System.nanoTime()
        if (now - lastPresentNanos < PRESENT_INTERVAL_NS) return
        lastPresentNanos = now
        val shown = try {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) {
            return
        } ?: return
        if (coverAll) {
            Canvas(shown).drawColor(Color.BLACK)
        } else {
            paintCoversOn(shown, covers.map { clampToContent(it, source.width, source.height) })
        }
        framesPresented.incrementAndGet()
        updatePresentFps()
        if (coverAll || covers.isNotEmpty()) {
            retainCoveredFrame(shown)
        }
        onFrame(
            ProcessedFrame(
                bitmap = shown,
                classification = ClassificationResult(
                    isUnsafe = coverAll || covers.isNotEmpty(),
                    score = if (coverAll) 1f else 0f,
                ),
                textHits = emptyList(),
                coverAll = coverAll,
                captureNanos = clock.captureTimestampNanos(),
                metrics = currentMetrics(0).copy(fps = currentPresentFps),
            ),
        )
    }

    private fun retainCoveredFrame(shown: Bitmap) {
        val copy = try {
            shown.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } ?: return
        val previous: Bitmap?
        synchronized(coverLock) {
            previous = lastCoveredBitmap
            lastCoveredBitmap = copy
        }
        if (previous != null && !previous.isRecycled) previous.recycle()
    }

    /**
     * When MediaProjection stops sending frames (static UI), keep pushing the last
     * covered composite at ~30fps so the overlay never falls back to a live uncovered view.
     */
    private fun representLastCovered() {
        if (!com.cleaner.filter.FilterEngine.mirrorProcessedFrames) return
        if (com.cleaner.filter.FilterEngine.overlayPausedForOwnUi) return
        val now = System.nanoTime()
        if (now - lastPresentNanos < PRESENT_INTERVAL_NS) return
        val snap = synchronized(coverLock) { lastCoveredBitmap } ?: return
        if (snap.isRecycled) return
        val shown = try {
            snap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            return
        } ?: return
        lastPresentNanos = now
        framesPresented.incrementAndGet()
        updatePresentFps()
        onFrame(
            ProcessedFrame(
                bitmap = shown,
                classification = ClassificationResult(isUnsafe = true, score = 1f),
                textHits = emptyList(),
                coverAll = false,
                captureNanos = clock.captureTimestampNanos(),
                metrics = currentMetrics(0).copy(fps = currentPresentFps),
            ),
        )
    }

    private fun maybePresentBlocked(source: Bitmap) {
        maybePresentProcessed(source, emptyList(), coverAll = true)
    }

    /**
     * One classifier. It always takes the newest waiting frame. The overlay keeps showing the
     * last finished composite until this returns, so a slow model lowers fps instead of adding lag
     * or letting content scroll out from under stale boxes.
     */
    private fun classifyLoop() {
        val box = inbox ?: return
        while (running.get()) {
            val bitmap = box.take() ?: break
            if (!running.get()) {
                if (!bitmap.isRecycled) bitmap.recycle()
                break
            }
            try {
                publishOrHold(bitmap)
            } catch (_: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun paintCoversOn(bitmap: Bitmap, covers: List<CoverRect>) {
        if (covers.isEmpty()) return
        val canvas = Canvas(bitmap)
        for (cover in covers) {
            canvas.drawRect(cover.left, cover.top, cover.right, cover.bottom, coverPaint)
        }
    }

    private fun publishOrHold(bitmap: Bitmap) {
        // Hash content only — status/nav chrome (clock, icons) must not reset covers.
        val hash = FrameHasher.averageHash(bitmap, chromeIgnore(bitmap.width, bitmap.height))
        val probing = com.cleaner.filter.FilterEngine.probe != null
        val distance = if (hasHash) FrameHasher.hammingDistance(hash, lastHash) else 64
        val mildChange = hasHash && distance > SIMILAR_HASH_THRESHOLD
        val hardChange = !hasHash || distance > HARD_SCENE_HASH_THRESHOLD
        if (hardChange) {
            sceneChangeStreak = SCENE_CHANGE_STREAK_TO_WIDEN
        } else if (mildChange) {
            sceneChangeStreak++
        } else {
            sceneChangeStreak = 0
        }
        val holdingUnsafe = synchronized(coverLock) {
            preciseCapture.isNotEmpty() && System.nanoTime() < unsafeUntilNanos
        }
        // Sticky hold: only a hard scene change widens. Mild noise never does.
        val sceneChanged = if (holdingUnsafe) {
            hardChange
        } else {
            hardChange || sceneChangeStreak >= SCENE_CHANGE_STREAK_TO_WIDEN ||
                (mildChange && sceneChangeStreak >= 2)
        }

        // Still scene + known boxes → shrink content-band → detection regions.
        if (!sceneChanged && hasClassifiedOnce.get()) {
            synchronized(coverLock) {
                if (preciseCapture.isNotEmpty() && coverWide) {
                    stableTightStreak++
                    if (stableTightStreak >= STABLE_FRAMES_TO_TIGHTEN) {
                        coverWide = false
                        heldCapture = preciseCapture
                        Log.i(TAG, "cover tightened to ${preciseCapture.size} region(s)")
                    }
                }
            }
        }

        val hasPrecise = synchronized(coverLock) { preciseCapture.isNotEmpty() }
        val now = System.nanoTime()
        val refreshNs = if (hasPrecise && !sceneChanged) 40_000_000L else 200_000_000L
        // Sticky boxes: present stays at ~30fps via ticker; reclassify center-fast.
        if (hasPrecise && hasClassifiedOnce.get() && !sceneChanged && !probing &&
            now - lastClassifyNanos < refreshNs
        ) {
            lastHash = hash
            hasHash = true
            synchronized(coverLock) {
                if (preciseCapture.isNotEmpty()) {
                    unsafeUntilNanos = maxOf(unsafeUntilNanos, now + UNSAFE_HOLD_NS / 2)
                }
            }
            framesSkipped.incrementAndGet()
            bitmap.recycle()
            return
        }
        probeWasShowing = probing
        lastClassifyNanos = now

        // Scroll: temporarily prefer the content band until this classify returns boxes.
        if (sceneChanged) {
            synchronized(coverLock) {
                val shouldWiden = preciseCapture.isNotEmpty() ||
                    System.nanoTime() < unsafeUntilNanos ||
                    !hasClassifiedOnce.get()
                if (shouldWiden) {
                    coverWide = true
                    stableTightStreak = 0
                    heldCapture = listOf(contentBandCover(bitmap.width, bitmap.height))
                }
            }
        }

        val fullScan = synchronized(coverLock) {
            preciseCapture.isEmpty() || coverWide || sceneChanged ||
                now - lastFullScanNanos >= FULL_SCAN_INTERVAL_NS
        }
        if (fullScan) lastFullScanNanos = now
        val result = if (settings.visualNudityEnabled) {
            classifier.classify(bitmap, settings.visualSensitivity, fullScan)
        } else {
            ClassificationResult(isUnsafe = false, score = 0f)
        }
        val captureCovers = if (result.boxes.isNotEmpty()) {
            mergeCovers(
                result.boxes.map { box ->
                    expandCover(
                        box.bounds.left,
                        box.bounds.top,
                        box.bounds.right,
                        box.bounds.bottom,
                        bitmap.width.toFloat(),
                        bitmap.height.toFloat(),
                        label = box.label,
                    )
                },
                gap = minOf(bitmap.width, bitmap.height) * 0.08f,
            ).map { clampToContent(it, bitmap.width, bitmap.height) }
        } else {
            emptyList()
        }
        synchronized(coverLock) {
            if (captureCovers.isNotEmpty()) {
                preciseCapture = captureCovers
                unsafeUntilNanos = System.nanoTime() + UNSAFE_HOLD_NS
                clearStreak = 0
                // Always tighten once boxes exist — sceneChanged must not keep us wide
                // (that was flipping wide↔tight on every reclassify).
                coverWide = false
                heldCapture = captureCovers
                stableTightStreak = 0
                Log.i(TAG, "cover tight regions=${captureCovers.size} dist=$distance")
            } else if (System.nanoTime() < unsafeUntilNanos || !fullScan) {
                // Hold through brief misses and center-only refresh misses.
                clearStreak = 0
                if (!sceneChanged && preciseCapture.isNotEmpty()) {
                    coverWide = false
                    heldCapture = preciseCapture
                }
                if (!fullScan && preciseCapture.isNotEmpty()) {
                    unsafeUntilNanos = maxOf(unsafeUntilNanos, System.nanoTime() + UNSAFE_HOLD_NS / 2)
                }
            } else if (preciseCapture.isNotEmpty() || heldCapture.isNotEmpty()) {
                clearStreak++
                if (clearStreak >= CLEAR_STREAK_REQUIRED) {
                    heldCapture = emptyList()
                    preciseCapture = emptyList()
                    coverWide = true
                    stableTightStreak = 0
                    clearStreak = 0
                    Log.i(TAG, "covers cleared after sustained misses")
                }
            } else {
                clearStreak = 0
                if (sceneChanged) {
                    heldCapture = emptyList()
                }
            }
        }
        lastHash = hash
        hasHash = true
        hasClassifiedOnce.set(true)
        val captureW = bitmap.width
        val captureH = bitmap.height
        framesProcessed.incrementAndGet()
        updateFps()
        if (framesProcessed.get() % 15L == 1L || captureCovers.isNotEmpty()) {
            Log.i(
                TAG,
                "classified ${captureCovers.size} regions in ${result.inferenceMs}ms " +
                    "capture=${captureW}x${captureH} fps=${"%.1f".format(currentFps)}",
            )
        }
        val paintCovers = coversForPresent(captureW, captureH)
        val mirror = com.cleaner.filter.FilterEngine.mirrorProcessedFrames
        if (mirror && !com.cleaner.filter.FilterEngine.overlayPausedForOwnUi) {
            paintCoversOn(bitmap, paintCovers)
            if (paintCovers.isNotEmpty()) {
                retainCoveredFrame(bitmap)
            }
        }
        framesPresented.incrementAndGet()
        updatePresentFps()
        val wide = synchronized(coverLock) { coverWide }
        val preciseN = synchronized(coverLock) { preciseCapture.size }
        Log.i(
            TAG,
            "present wide=$wide regions=${paintCovers.size} precise=$preciseN " +
                "clear=$clearStreak mirror=$mirror",
        )
        onFrame(
            ProcessedFrame(
                bitmap = if (mirror) bitmap else null,
                classification = result.copy(
                    boxes = paintCovers.map { cover ->
                        DetectionBox(
                            RectF(
                                cover.left * screenWidth / bitmap.width,
                                cover.top * screenHeight / bitmap.height,
                                cover.right * screenWidth / bitmap.width,
                                cover.bottom * screenHeight / bitmap.height,
                            ),
                            result.score,
                            "nsfw",
                        )
                    },
                ),
                textHits = emptyList(),
                coverAll = false,
                captureNanos = clock.captureTimestampNanos(),
                metrics = currentMetrics(result.inferenceMs),
            ),
        )
        if (!mirror && !bitmap.isRecycled) bitmap.recycle()
    }

    /** Capture-space band that excludes status + nav bars. */
    private fun contentBandCover(captureW: Int, captureH: Int): CoverRect {
        val top = contentTopInset(captureH)
        val bottom = (captureH - contentBottomInset(captureH)).toFloat()
        return CoverRect(0f, top, captureW.toFloat(), bottom.coerceAtLeast(top + 1f))
    }

    private fun clampToContent(cover: CoverRect, captureW: Int, captureH: Int): CoverRect {
        val top = contentTopInset(captureH)
        val bottom = (captureH - contentBottomInset(captureH)).toFloat()
        val left = cover.left.coerceIn(0f, captureW.toFloat())
        val right = cover.right.coerceIn(0f, captureW.toFloat())
        val clampedTop = cover.top.coerceAtLeast(top).coerceAtMost(bottom)
        val clampedBottom = cover.bottom.coerceAtMost(bottom).coerceAtLeast(clampedTop)
        return CoverRect(left, clampedTop, right.coerceAtLeast(left), clampedBottom)
    }

    private fun contentTopInset(captureH: Int): Float {
        // ~status bar share of a typical phone frame (keeps notification shade uncovered).
        return captureH * 0.045f
    }

    private fun contentBottomInset(captureH: Int): Float {
        // ~nav / gesture bar share.
        return captureH * 0.055f
    }

    private fun currentMetrics(inferenceMs: Long) = PipelineMetrics(
        fps = currentFps,
        inferenceMs = inferenceMs,
        framesSkipped = framesSkipped.get(),
        framesDropped = inbox?.droppedCount() ?: 0,
        framesProcessed = framesProcessed.get(),
        presentationDelayMs = clock.delayMs,
    )

    private fun updateFps() {
        fpsWindowCount++
        val now = System.nanoTime()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            currentFps = fpsWindowCount * 1_000_000_000f / elapsed
            fpsWindowCount = 0
            fpsWindowStart = now
            Log.i(
                TAG,
                "processedFps=${"%.1f".format(currentFps)} presentFps=${"%.1f".format(currentPresentFps)}",
            )
        }
    }

    private fun updatePresentFps() {
        presentFpsWindowCount++
        val now = System.nanoTime()
        val elapsed = now - presentFpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            currentPresentFps = presentFpsWindowCount * 1_000_000_000f / elapsed
            presentFpsWindowCount = 0
            presentFpsWindowStart = now
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bmp = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bmp.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            bmp
        } else {
            Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
        }
    }

    fun stop() {
        if (!running.getAndSet(false) && inbox == null) return
        captureHandler?.removeCallbacks(presentTick)
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
        lastHash = 0
        hasHash = false
        lastPresentHash = 0
        hasPresentHash = false
        probeWasShowing = false
        val staleCovered: Bitmap?
        synchronized(coverLock) {
            heldCapture = emptyList()
            preciseCapture = emptyList()
            coverWide = true
            stableTightStreak = 0
            sceneChangeStreak = 0
            staleCovered = lastCoveredBitmap
            lastCoveredBitmap = null
        }
        if (staleCovered != null && !staleCovered.isRecycled) staleCovered.recycle()
        hasClassifiedOnce.set(false)
        framesPresented.set(0)
        lastPresentNanos = 0L
        lastClassifyNanos = 0L
        lastFullScanNanos = 0L
        unsafeUntilNanos = 0L
        clearStreak = 0
        currentPresentFps = 0f
        presentFpsWindowCount = 0
        presentFpsWindowStart = System.nanoTime()
    }

    companion object {
        private const val TAG = "CleanerFilter"
        // Short side matches 320n input; three tiles still cover a tall phone.
        private const val ANALYSIS_SHORT_EDGE = 320
        private const val PRESENT_INTERVAL_NS = 33_000_000L
        /** Hold the blocked stream after a hit so brief tile misses cannot uncover. */
        private const val UNSAFE_HOLD_NS = 6_000_000_000L
        private const val CLEAR_STREAK_REQUIRED = 8
        /** Still frames after boxes are known before shrinking wide → tight. */
        private const val STABLE_FRAMES_TO_TIGHTEN = 1
        /** Hamming distance on 64-bit average hash treated as "same scene". */
        private const val SIMILAR_HASH_THRESHOLD = 10
        /** Real page/scroll change while covers are sticky. */
        private const val HARD_SCENE_HASH_THRESHOLD = 22
        /** Consecutive mild hash changes before widening (safe / no-hold only). */
        private const val SCENE_CHANGE_STREAK_TO_WIDEN = 5
        /** Even with sticky covers, re-scan all tiles periodically. */
        private const val FULL_SCAN_INTERVAL_NS = 2_000_000_000L
    }
}

internal data class CoverRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * NudeNet boxes are often tiny (nipple / genital crop). Grow them into a body-sized
 * cover using the frame short side, not the box size, so the rest of the body does
 * not stay visible. [fraction] is kept for tests; live path uses [label]-aware padding.
 */
internal fun expandCover(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    maxWidth: Float,
    maxHeight: Float,
    fraction: Float = 0.12f,
    label: String = "",
): CoverRect {
    val boxW = (right - left).coerceAtLeast(1f)
    val boxH = (bottom - top).coerceAtLeast(1f)

    // Unlabeled / explicit fraction path keeps the old geometry (tests + callers).
    if (label.isBlank()) {
        val dx = boxW * fraction
        val dy = boxH * fraction
        return CoverRect(
            (left - dx).coerceAtLeast(0f),
            (top - dy).coerceAtLeast(0f),
            (right + dx).coerceAtMost(maxWidth),
            (bottom + dy).coerceAtMost(maxHeight),
        )
    }

    val shortSide = minOf(maxWidth, maxHeight).coerceAtLeast(1f)
    // Pad by a large fraction of the screen so a nipple-sized hit still blacks out torso.
    val (padX, padY, biasY) = when (coverKind(label)) {
        CoverKind.BREAST -> Triple(shortSide * 0.30f, shortSide * 0.36f, 0.40f)
        CoverKind.GENITAL -> Triple(shortSide * 0.32f, shortSide * 0.38f, -0.30f)
        CoverKind.BUTTOCKS -> Triple(shortSide * 0.34f, shortSide * 0.34f, 0.10f)
        CoverKind.GENERIC -> Triple(shortSide * 0.26f, shortSide * 0.26f, 0f)
    }

    val cx = (left + right) * 0.5f
    val cy = (top + bottom) * 0.5f + biasY * padY
    val halfW = maxOf(boxW * 0.5f + padX, shortSide * 0.22f)
    val halfH = maxOf(boxH * 0.5f + padY, shortSide * 0.24f)
    return CoverRect(
        (cx - halfW).coerceAtLeast(0f),
        (cy - halfH).coerceAtLeast(0f),
        (cx + halfW).coerceAtMost(maxWidth),
        (cy + halfH).coerceAtMost(maxHeight),
    )
}

internal enum class CoverKind { BREAST, GENITAL, BUTTOCKS, GENERIC }

internal fun coverKind(label: String): CoverKind {
    val upper = label.uppercase()
    return when {
        upper.contains("BREAST") -> CoverKind.BREAST
        upper.contains("GENITALIA") || upper.contains("ANUS") -> CoverKind.GENITAL
        upper.contains("BUTTOCKS") -> CoverKind.BUTTOCKS
        else -> CoverKind.GENERIC
    }
}

/** Union of [covers] grown modestly into a torso plate that still fits the frame. */
internal fun bodySafetyPlate(covers: List<CoverRect>, maxWidth: Float, maxHeight: Float): CoverRect {
    var left = covers.minOf { it.left }
    var top = covers.minOf { it.top }
    var right = covers.maxOf { it.right }
    var bottom = covers.maxOf { it.bottom }
    val shortSide = minOf(maxWidth, maxHeight)
    // Modest growth — do not force a near-fullscreen plate (chrome must stay visible).
    left = (left - shortSide * 0.12f).coerceAtLeast(0f)
    right = (right + shortSide * 0.12f).coerceAtMost(maxWidth)
    top = (top - shortSide * 0.14f).coerceAtLeast(0f)
    bottom = (bottom + shortSide * 0.18f).coerceAtMost(maxHeight)
    return CoverRect(left, top, right, bottom)
}

/** Union nearby/overlapping covers so left+right breast become one torso plate. */
internal fun mergeCovers(covers: List<CoverRect>, gap: Float = 0f): List<CoverRect> {
    if (covers.size <= 1) return covers
    val remaining = covers.toMutableList()
    val merged = ArrayList<CoverRect>()
    while (remaining.isNotEmpty()) {
        var current = remaining.removeAt(0)
        var grew: Boolean
        do {
            grew = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val other = iterator.next()
                if (coversOverlapOrNear(current, other, gap)) {
                    current = CoverRect(
                        minOf(current.left, other.left),
                        minOf(current.top, other.top),
                        maxOf(current.right, other.right),
                        maxOf(current.bottom, other.bottom),
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

private fun coversOverlapOrNear(a: CoverRect, b: CoverRect, gap: Float): Boolean {
    return a.left <= b.right + gap &&
        a.right + gap >= b.left &&
        a.top <= b.bottom + gap &&
        a.bottom + gap >= b.top
}
