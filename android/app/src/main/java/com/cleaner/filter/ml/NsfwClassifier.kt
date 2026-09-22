package com.cleaner.filter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

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
 * Two-stage on-device vision filter.
 *
 * v1 uses a bundled heuristic gate when TFLite model is absent, and loads
 * [MODEL_ASSET] (MobileNet-style NSFW gate) when present in assets.
 *
 * Model choice documented in docs/architecture.md: a NudeNet / YOLO-nano detector
 * (one forward pass, boxes + score). Not OpenJev — that checkpoint is ~54 GB.
 */
class NsfwClassifier(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val inputSize = 224
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        .order(ByteOrder.nativeOrder())

    init {
        loadModel()
    }

    private fun loadModel() {
        val modelFile = ModelAssetManager.ensureModel(appContext)
        if (modelFile == null || !modelFile.exists()) return

        val options = Interpreter.Options().apply {
            numThreads = 4
            try {
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            } catch (_: Exception) {
                gpuDelegate = null
            }
        }
        interpreter = Interpreter(loadMappedFile(modelFile), options)
    }

    fun classify(bitmap: Bitmap, threshold: Float): ClassificationResult {
        val start = System.nanoTime()
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val tflite = interpreter
        val score = if (tflite != null) {
            runTflite(tflite, scaled)
        } else {
            HeuristicNsfwScorer.score(scaled)
        }

        if (scaled != bitmap) scaled.recycle()

        val unsafe = score >= threshold
        val boxes = if (unsafe) {
            listOf(
                DetectionBox(
                    bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                    score = score,
                    label = "nsfw",
                ),
            )
        } else {
            emptyList()
        }

        val ms = (System.nanoTime() - start) / 1_000_000
        return ClassificationResult(unsafe, score, boxes, ms)
    }

    private fun runTflite(interpreter: Interpreter, bitmap: Bitmap): Float {
        fillInputBuffer(bitmap)
        val output = Array(1) { FloatArray(2) }
        interpreter.run(inputBuffer, output)
        inputBuffer.rewind()
        return output[0][1]
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16 and 0xFF) - 127.5f) / 127.5f)
            inputBuffer.putFloat(((pixel shr 8 and 0xFF) - 127.5f) / 127.5f)
            inputBuffer.putFloat(((pixel and 0xFF) - 127.5f) / 127.5f)
        }
        inputBuffer.rewind()
    }

    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
        interpreter = null
        gpuDelegate = null
    }

    companion object {
        const val MODEL_ASSET = "models/nsfw_gate.tflite"

        private fun loadMappedFile(file: File): MappedByteBuffer =
            FileInputStream(file).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
    }
}

/** Lightweight skin-tone ratio heuristic when no TFLite model is bundled. */
private object HeuristicNsfwScorer {
    fun score(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = 4
        var skin = 0
        var total = 0
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val p = bitmap.getPixel(x, y)
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                if (isSkinTone(r, g, b)) skin++
                total++
            }
        }
        if (total == 0) return 0f
        val ratio = skin.toFloat() / total
        return (ratio * 2.5f).coerceIn(0f, 1f)
    }

    private fun isSkinTone(r: Int, g: Int, b: Int): Boolean {
        if (r < 60 || g < 40 || b < 20) return false
        if (r - g < 15) return false
        return r > g && g > b
    }
}
