package com.cleaner.filter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.util.Arrays
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * NudeNet 320n (YOLOv8n, 320px) from the official nudenet 3.4.2 package.
 * Weights: assets/models/320n.onnx. MIT license, notAI-tech/NudeNet.
 */
class NudeNetDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val modelBytes: ByteArray = context.assets.open(MODEL_ASSET).use { it.readBytes() }
    private var session: OrtSession
    private var inputName: String
    private var usingNnapi = false
    private val tileSessions = ArrayList<OrtSession>(3)
    private val tileInputs = ArrayList<String>(3)
    private val tilePool = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "nudenet-tile").also { it.isDaemon = true }
    }
    private val tileScratch = Array(3) { TileScratch() }

    init {
        // Prefer XNNPACK; NNAPI often partitions YOLOv8 poorly on Pixel.
        val cpu = openSession(modelBytes, nnapi = false)
        if (cpu != null) {
            session = cpu
            usingNnapi = false
        } else {
            session = openSession(modelBytes, nnapi = true)
                ?: error("NudeNet session failed to start")
            usingNnapi = true
        }
        inputName = session.inputNames.first()
        tileSessions += session
        tileInputs += inputName
        repeat(2) {
            val extra = openSession(modelBytes, nnapi = usingNnapi) ?: return@repeat
            tileSessions += extra
            tileInputs += extra.inputNames.first()
        }
        Log.i(TAG, "nudenet ready sessions=${tileSessions.size} nnapi=$usingNnapi")
    }

    private fun openSession(model: ByteArray, nnapi: Boolean): OrtSession? {
        val layouts = if (nnapi) listOf(true, false) else listOf(false)
        for (useNchw in layouts) {
            try {
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    if (nnapi) {
                        setIntraOpNumThreads(1)
                        val flags = EnumSet.of(NNAPIFlags.USE_FP16)
                        if (useNchw) flags.add(NNAPIFlags.USE_NCHW)
                        addNnapi(flags)
                    } else {
                        // One thread per session: three tiles run in parallel without fighting.
                        setIntraOpNumThreads(1)
                        try {
                            addXnnpack(mapOf("intra_op_num_threads" to "1"))
                        } catch (_: Throwable) {
                        }
                    }
                }
                return env.createSession(model, options)
            } catch (error: Throwable) {
                Log.w(TAG, "nudenet session nnapi=$nnapi nchw=$useNchw failed: ${error.message}")
            }
        }
        return null
    }

    fun detect(bitmap: Bitmap, scoreThreshold: Float, fullScan: Boolean = true): ClassificationResult {
        val start = System.nanoTime()
        val allTiles = scanTiles(bitmap.width, bitmap.height)
        if (allTiles.isEmpty()) {
            return ClassificationResult(false, 0f, inferenceMs = 0)
        }
        // Full scan = every tile (first hit / scroll). Sticky refresh = center only (faster).
        val tiles = if (fullScan || allTiles.size == 1) {
            allTiles
        } else {
            listOf(allTiles[allTiles.size / 2])
        }
        val parts = arrayOfNulls<ClassificationResult>(tiles.size)
        val parallel = tiles.size > 1 && tileSessions.size >= allTiles.size
        if (parallel) {
            val latch = CountDownLatch(tiles.size)
            for (index in tiles.indices) {
                val sessionIndex = if (fullScan) index else allTiles.size / 2
                tilePool.execute {
                    val scratch = TileScratch()
                    try {
                        val tile = tiles[index]
                        parts[index] = infer(
                            tileSessions[sessionIndex.coerceAtMost(tileSessions.lastIndex)],
                            tileInputs[sessionIndex.coerceAtMost(tileInputs.lastIndex)],
                            bitmap,
                            Rect(tile.left, tile.top, tile.left + tile.size, tile.top + tile.size),
                            scoreThreshold,
                            usingNnapi,
                            scratch,
                        )
                    } catch (error: Throwable) {
                        Log.w(TAG, "tile $index failed: ${error.message}")
                    } finally {
                        scratch.scaled?.recycle()
                        latch.countDown()
                    }
                }
            }
            if (!latch.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "tile infer timed out remaining=${latch.count}")
            }
        } else {
            for (index in tiles.indices) {
                val sessionIndex = if (fullScan) {
                    index.coerceAtMost(tileSessions.lastIndex)
                } else {
                    (allTiles.size / 2).coerceAtMost(tileSessions.lastIndex)
                }
                val scratch = TileScratch()
                try {
                    val tile = tiles[index]
                    parts[index] = infer(
                        tileSessions[sessionIndex],
                        tileInputs[sessionIndex],
                        bitmap,
                        Rect(tile.left, tile.top, tile.left + tile.size, tile.top + tile.size),
                        scoreThreshold,
                        usingNnapi,
                        scratch,
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "tile $index failed: ${error.message}")
                } finally {
                    scratch.scaled?.recycle()
                }
            }
        }
        val found = ArrayList<DetectionBox>()
        var topScore = 0f
        for (index in tiles.indices) {
            val result = parts[index] ?: continue
            topScore = maxOf(topScore, result.topScore)
            val tile = tiles[index]
            for (box in result.boxes) {
                found.add(
                    DetectionBox(
                        RectF(
                            box.bounds.left + tile.left,
                            box.bounds.top + tile.top,
                            box.bounds.right + tile.left,
                            box.bounds.bottom + tile.top,
                        ),
                        box.score,
                        box.label,
                    ),
                )
            }
        }
        val kept = NudeNetDecoder.nms(found, NudeNetDecoder.NMS_IOU)
        val ms = (System.nanoTime() - start) / 1_000_000
        Log.i(
            TAG,
            "tiles=${tiles.size}/${allTiles.size} boxes=${kept.size} ${ms}ms " +
                "top=${"%.3f".format(topScore)} full=$fullScan nnapi=$usingNnapi",
        )
        return ClassificationResult(
            isUnsafe = kept.isNotEmpty(),
            score = kept.maxOfOrNull { it.score } ?: 0f,
            boxes = kept,
            inferenceMs = ms,
            topScore = topScore,
        )
    }

    private fun infer(
        active: OrtSession,
        activeInput: String,
        bitmap: Bitmap,
        region: Rect,
        scoreThreshold: Float,
        nnapiFlag: Boolean,
        scratch: TileScratch,
    ): ClassificationResult {
        val start = System.nanoTime()
        val input = letterbox(bitmap, region, scratch)
        val shape = longArrayOf(1, 3, NudeNetDecoder.MODEL_SIZE.toLong(), NudeNetDecoder.MODEL_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            active.run(mapOf(activeInput to tensor)).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val shapeInfo = output.info.shape
                val buffer = output.floatBuffer
                val dim1 = shapeInfo[1].toInt()
                val dim2 = shapeInfo[2].toInt()
                val channelsFirst = dim1 <= dim2
                val channelCount = if (channelsFirst) dim1 else dim2
                val anchorCount = if (channelsFirst) dim2 else dim1
                val valueAt = { channel: Int, anchor: Int ->
                    val index = if (channelsFirst) {
                        channel * anchorCount + anchor
                    } else {
                        anchor * channelCount + channel
                    }
                    buffer.get(index)
                }
                val (topLabel, topScore) = NudeNetDecoder.peak(channelCount, anchorCount, valueAt)
                val blocking = NudeNetDecoder.blockingPeak(channelCount, anchorCount, valueAt)
                val boxes = NudeNetDecoder.decode(
                    channelCount = channelCount,
                    anchorCount = anchorCount,
                    valueAt = valueAt,
                    imageWidth = region.width(),
                    imageHeight = region.height(),
                    scoreThreshold = scoreThreshold,
                )
                val best = boxes.maxOfOrNull { it.score } ?: 0f
                val ms = (System.nanoTime() - start) / 1_000_000
                if (boxes.isNotEmpty() || blocking >= scoreThreshold) {
                    Log.i(
                        TAG,
                        "top=$topLabel ${"%.3f".format(topScore)} block=${"%.3f".format(blocking)} " +
                            "boxes=${boxes.size} ${ms}ms nnapi=$nnapiFlag",
                    )
                }
                return ClassificationResult(
                    isUnsafe = boxes.isNotEmpty(),
                    score = best,
                    boxes = boxes,
                    inferenceMs = ms,
                    topScore = topScore,
                )
            }
        }
    }

    override fun close() {
        tilePool.shutdownNow()
        tileScratch.forEach { it.scaled?.recycle() }
        tileSessions.forEach { runCatching { it.close() } }
    }

    private fun letterbox(bitmap: Bitmap, region: Rect, scratch: TileScratch): FloatArray {
        val size = NudeNetDecoder.MODEL_SIZE
        val input = scratch.input
        val pixels = scratch.pixels
        val scaled = scratch.scaled?.takeIf { it.width == size && it.height == size }
            ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { scratch.scaled = it }

        if (region.width() == size && region.height() == size) {
            bitmap.getPixels(pixels, 0, size, region.left, region.top, size, size)
        } else {
            scratch.src.set(region)
            // Square tiles just scale into the model input.
            scratch.dst.set(0, 0, size, size)
            val canvas = Canvas(scaled)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, scratch.src, scratch.dst, scratch.paint)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        }
        val area = size * size
        for (i in 0 until area) {
            val p = pixels[i]
            input[i] = ((p shr 16) and 0xff) / 255f
            input[area + i] = ((p shr 8) and 0xff) / 255f
            input[area * 2 + i] = (p and 0xff) / 255f
        }
        return input
    }

    companion object {
        private const val TAG = "CleanerFilter"
        const val MODEL_ASSET = "models/320n.onnx"
    }
}

internal data class ScanTile(val left: Int, val top: Int, val size: Int)

private class TileScratch {
    val input = FloatArray(3 * NudeNetDecoder.MODEL_SIZE * NudeNetDecoder.MODEL_SIZE)
    val pixels = IntArray(NudeNetDecoder.MODEL_SIZE * NudeNetDecoder.MODEL_SIZE)
    var scaled: Bitmap? = null
    val src = Rect()
    val dst = Rect()
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
}

/** Square windows along a tall or wide frame so 320n sees body-sized detail. */
internal fun scanTiles(width: Int, height: Int): List<ScanTile> {
    if (width <= 0 || height <= 0) return emptyList()
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    val origins = if (longSide * 2 < shortSide * 3) {
        listOf(0)
    } else {
        listOf(0, (longSide - shortSide) / 2, longSide - shortSide).distinct()
    }
    return if (height >= width) {
        origins.map { ScanTile(0, it, shortSide) }
    } else {
        origins.map { ScanTile(it, 0, shortSide) }
    }
}
