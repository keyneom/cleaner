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
import com.cleaner.filter.ml.ClassificationResult
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
    private var framesProcessed = AtomicLong(0)
    private var framesSkipped = AtomicLong(0)
    private var fpsWindowStart = System.nanoTime()
    private var fpsWindowCount = 0
    private var currentFps = 0f
    private val coverPaint = Paint().apply { color = Color.BLACK }

    private var width = 0
    private var height = 0
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
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        // Two buffers: producer keeps the newest, reader drops the rest via acquireLatestImage.
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        captureThread = HandlerThread("cleaner-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        inbox = LatestFrameInbox { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        running.set(true)
        worker = Thread({ classifyLoop() }, "cleaner-classify").also { it.start() }

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
            val box = inbox
            if (box == null) {
                bitmap.recycle()
            } else {
                box.offer(bitmap)
            }
        } finally {
            image.close()
        }
    }

    /**
     * One classifier. It always takes the newest waiting frame. The overlay keeps showing the
     * last finished composite until this returns, so a slow model lowers fps instead of adding lag.
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

    private fun publishOrHold(bitmap: Bitmap) {
        val hash = FrameHasher.averageHash(bitmap)
        if (FrameHasher.isSimilar(hash, lastHash)) {
            framesSkipped.incrementAndGet()
            bitmap.recycle()
            return
        }
        lastHash = hash

        val result = if (settings.visualNudityEnabled) {
            classifier.classify(bitmap, settings.visualSensitivity)
        } else {
            ClassificationResult(isUnsafe = false, score = 0f)
        }
        val regions = result.boxes.map { it.bounds }
        if (result.isUnsafe && regions.isEmpty()) {
            bitmap.recycle()
            return
        }
        if (regions.isNotEmpty()) {
            paintCovers(bitmap, regions)
        }

        framesProcessed.incrementAndGet()
        updateFps()
        onFrame(
            ProcessedFrame(
                bitmap = bitmap,
                classification = result,
                textHits = emptyList(),
                coverAll = false,
                captureNanos = clock.captureTimestampNanos(),
                metrics = currentMetrics(result.inferenceMs),
            ),
        )
    }

    private fun paintCovers(bitmap: Bitmap, regions: List<RectF>) {
        val canvas = Canvas(bitmap)
        for (region in regions) {
            canvas.drawRect(region, coverPaint)
        }
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
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
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
    }
}
