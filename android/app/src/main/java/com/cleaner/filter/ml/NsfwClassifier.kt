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
)

/**
 * On-device nudity detector. Uses bundled NudeNet 320n when the ONNX file loads.
 * Falls back to a skin-tone ratio only if the model cannot be opened.
 */
class NsfwClassifier(context: Context) : AutoCloseable {
    private val detector: NudeNetDetector? = try {
        NudeNetDetector(context.applicationContext)
    } catch (_: Throwable) {
        null
    }

    fun classify(bitmap: Bitmap, threshold: Float): ClassificationResult {
        val scoreThreshold = threshold.coerceIn(0.05f, 0.95f)
        detector?.let { return it.detect(bitmap, scoreThreshold) }
        return heuristic(bitmap)
    }

    override fun close() {
        detector?.close()
    }

    private fun heuristic(bitmap: Bitmap): ClassificationResult {
        val start = System.nanoTime()
        val score = skinRatio(bitmap)
        val ms = (System.nanoTime() - start) / 1_000_000
        val unsafe = score >= 0.85f
        val boxes = if (unsafe) {
            listOf(
                DetectionBox(
                    RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    score,
                    "heuristic",
                ),
            )
        } else {
            emptyList()
        }
        return ClassificationResult(unsafe, score, boxes, ms)
    }

    private fun skinRatio(bitmap: Bitmap): Float {
        val step = 8
        var skin = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (r > 60 && g > 40 && b > 20 && r - g >= 15 && r > g && g > b) skin++
                total++
                x += step
            }
            y += step
        }
        if (total == 0) return 0f
        return (skin.toFloat() / total * 2.5f).coerceIn(0f, 1f)
    }
}
