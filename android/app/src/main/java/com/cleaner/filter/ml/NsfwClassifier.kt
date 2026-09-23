package com.cleaner.filter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF

data class DetectionBox(
    val bounds: RectF,
    val score: Float,
    val label: String,
)

data class ClassificationResult(
    val isUnsafe: Boolean,
    val score: Float,
    val boxes: List<DetectionBox> = emptyList(),
    val inferenceMs: Long = 0,
    val topScore: Float = 0f,
)

/**
 * On-device nudity detector. Covers only the boxes NudeNet 320n returns.
 * If the model cannot be opened, nothing is covered. A full-frame cover would
 * hide the controls needed to leave the image.
 */
class NsfwClassifier(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    @Volatile private var detector: NudeNetDetector? = null
    @Volatile private var openFailed = false

    fun classify(
        bitmap: Bitmap,
        threshold: Float,
        includePartial: Boolean = true,
    ): ClassificationResult {
        val scoreThreshold = threshold.coerceIn(0.02f, 0.95f)
        detector()?.let {
            return it.detect(bitmap, scoreThreshold, NudeNetDecoder.blockingLabels(includePartial))
        }
        return ClassificationResult(isUnsafe = false, score = 0f)
    }

    /** Runs only [tiles] of an ARGB frame. Boxes come back in frame coordinates. */
    internal fun classifyTiles(
        pixels: IntArray,
        frameWidth: Int,
        tiles: List<ScanTile>,
        threshold: Float,
        includePartial: Boolean,
    ): ClassificationResult {
        val scoreThreshold = threshold.coerceIn(0.02f, 0.95f)
        detector()?.let {
            return it.detectTiles(
                pixels,
                frameWidth,
                tiles,
                scoreThreshold,
                NudeNetDecoder.blockingLabels(includePartial),
            )
        }
        return ClassificationResult(isUnsafe = false, score = 0f)
    }

    override fun close() {
        synchronized(lock) {
            detector?.close()
            detector = null
        }
    }

    private fun detector(): NudeNetDetector? {
        detector?.let { return it }
        synchronized(lock) {
            detector?.let { return it }
            if (openFailed) return null
            return try {
                NudeNetDetector(appContext).also { detector = it }
            } catch (error: Throwable) {
                openFailed = true
                android.util.Log.e("CleanerFilter", "nudenet unavailable; not covering the screen", error)
                null
            }
        }
    }
}
