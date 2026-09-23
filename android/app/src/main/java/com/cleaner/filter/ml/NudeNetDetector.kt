package com.cleaner.filter.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    private val tileScratch = Array(3) { FloatArray(3 * NudeNetDecoder.MODEL_SIZE * NudeNetDecoder.MODEL_SIZE) }

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

    /** Whole-bitmap scan (test image, androidTest, window-screenshot fallback). */
    fun detect(
        bitmap: Bitmap,
        scoreThreshold: Float,
        blocking: Set<String> = NudeNetDecoder.blockingLabels,
    ): ClassificationResult {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return detectTiles(pixels, w, scanTiles(w, h), scoreThreshold, blocking)
    }

    /**
     * Runs the model on each tile of an ARGB frame and returns boxes in frame
     * coordinates. Tiles are shared across the sessions, one thread per session.
     * Synchronized because each session owns one input buffer.
     */
    @Synchronized
    internal fun detectTiles(
        pixels: IntArray,
        frameWidth: Int,
        tiles: List<ScanTile>,
        scoreThreshold: Float,
        blocking: Set<String>,
    ): ClassificationResult {
        val start = System.nanoTime()
        if (tiles.isEmpty()) return ClassificationResult(false, 0f, inferenceMs = 0)
        val parts = arrayOfNulls<ClassificationResult>(tiles.size)
        val workers = minOf(tileSessions.size, tiles.size)
        val next = AtomicInteger(0)
        val runWorker = { worker: Int ->
            while (true) {
                val index = next.getAndIncrement()
                if (index >= tiles.size) break
                try {
                    fillTileInput(pixels, frameWidth, tiles[index], tileScratch[worker])
                    parts[index] = infer(
                        tileSessions[worker],
                        tileInputs[worker],
                        tileScratch[worker],
                        tiles[index].size,
                        scoreThreshold,
                        blocking,
                    )
                } catch (error: Throwable) {
                    Log.w(TAG, "tile $index failed: ${error.message}")
                }
            }
        }
        if (workers <= 1) {
            runWorker(0)
        } else {
            val latch = CountDownLatch(workers)
            for (worker in 0 until workers) {
                tilePool.execute {
                    try {
                        runWorker(worker)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            if (!latch.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "tile infer timed out remaining=${latch.count}")
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
            "tiles=${tiles.size} boxes=${kept.size} ${ms}ms " +
                "top=${"%.3f".format(topScore)} nnapi=$usingNnapi",
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
        input: FloatArray,
        tileSize: Int,
        scoreThreshold: Float,
        blocking: Set<String>,
    ): ClassificationResult {
        val start = System.nanoTime()
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
                val blockingScore = NudeNetDecoder.blockingPeak(channelCount, anchorCount, valueAt, blocking)
                val boxes = NudeNetDecoder.decode(
                    channelCount = channelCount,
                    anchorCount = anchorCount,
                    valueAt = valueAt,
                    imageWidth = tileSize,
                    imageHeight = tileSize,
                    scoreThreshold = scoreThreshold,
                    blocking = blocking,
                )
                val best = boxes.maxOfOrNull { it.score } ?: 0f
                val ms = (System.nanoTime() - start) / 1_000_000
                if (boxes.isNotEmpty() || blockingScore >= scoreThreshold) {
                    Log.i(
                        TAG,
                        "top=$topLabel ${"%.3f".format(topScore)} block=${"%.3f".format(blockingScore)} " +
                            "boxes=${boxes.size} ${ms}ms nnapi=$usingNnapi",
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
        tileSessions.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val TAG = "CleanerFilter"
        const val MODEL_ASSET = "models/320n.onnx"
    }
}
