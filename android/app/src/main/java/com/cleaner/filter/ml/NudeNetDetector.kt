package com.cleaner.filter.ml

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * NudeNet 320n (YOLOv8n, 320px) from the official nudenet 3.4.2 package.
 * Weights: assets/models/320n.onnx. MIT license, notAI-tech/NudeNet.
 */
class NudeNetDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            try {
                addXnnpack(mapOf("intra_op_num_threads" to "4"))
            } catch (_: Throwable) {
            }
        }
        session = env.createSession(model, options)
        inputName = session.inputNames.first()
    }

    fun detect(bitmap: Bitmap, scoreThreshold: Float): ClassificationResult {
        val start = System.nanoTime()
        val input = letterbox(bitmap)
        val shape = longArrayOf(1, 3, NudeNetDecoder.MODEL_SIZE.toLong(), NudeNetDecoder.MODEL_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val shapeInfo = output.info.shape
                val buffer = output.floatBuffer
                val dim1 = shapeInfo[1].toInt()
                val dim2 = shapeInfo[2].toInt()
                val channelsFirst = dim1 <= dim2
                val channelCount = if (channelsFirst) dim1 else dim2
                val anchorCount = if (channelsFirst) dim2 else dim1
                val boxes = NudeNetDecoder.decode(
                    channelCount = channelCount,
                    anchorCount = anchorCount,
                    valueAt = { channel, anchor ->
                        val index = if (channelsFirst) {
                            channel * anchorCount + anchor
                        } else {
                            anchor * channelCount + channel
                        }
                        buffer.get(index)
                    },
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    scoreThreshold = scoreThreshold,
                )
                val best = boxes.maxOfOrNull { it.score } ?: 0f
                val ms = (System.nanoTime() - start) / 1_000_000
                return ClassificationResult(
                    isUnsafe = boxes.isNotEmpty(),
                    score = best,
                    boxes = boxes,
                    inferenceMs = ms,
                )
            }
        }
    }

    override fun close() {
        session.close()
    }

    private fun letterbox(bitmap: Bitmap): FloatArray {
        val size = NudeNetDecoder.MODEL_SIZE
        val maxSide = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scaledW = (bitmap.width * size / maxSide).coerceAtLeast(1)
        val scaledH = (bitmap.height * size / maxSide).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val pixels = IntArray(scaledW * scaledH)
        scaled.getPixels(pixels, 0, scaledW, 0, 0, scaledW, scaledH)
        if (scaled !== bitmap) scaled.recycle()

        val plane = size * size
        val input = FloatArray(3 * plane)
        for (y in 0 until scaledH) {
            for (x in 0 until scaledW) {
                val pixel = pixels[y * scaledW + x]
                val dst = y * size + x
                input[dst] = ((pixel shr 16) and 0xFF) / 255f
                input[plane + dst] = ((pixel shr 8) and 0xFF) / 255f
                input[2 * plane + dst] = (pixel and 0xFF) / 255f
            }
        }
        return input
    }

    companion object {
        const val MODEL_ASSET = "models/320n.onnx"
    }
}
