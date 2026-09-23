package com.cleaner.filter.ml

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8-style decode for NudeNet 320n (18 classes, 320 input).
 * Coordinates match the upstream NudeNet postprocess: center boxes scaled by the
 * letterbox square back onto the original image.
 */
object NudeNetDecoder {
    const val MODEL_SIZE = 320
    const val NMS_IOU = 0.45f

    val labels = listOf(
        "FEMALE_GENITALIA_COVERED",
        "FACE_FEMALE",
        "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED",
        "MALE_BREAST_EXPOSED",
        "ANUS_EXPOSED",
        "FEET_EXPOSED",
        "BELLY_COVERED",
        "FEET_COVERED",
        "ARMPITS_COVERED",
        "ARMPITS_EXPOSED",
        "FACE_MALE",
        "BELLY_EXPOSED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED",
        "FEMALE_BREAST_COVERED",
        "BUTTOCKS_COVERED",
    )

    /** Body parts the filter covers. Faces and feet are detected but not blocked. */
    val blockingLabels = setOf(
        "BUTTOCKS_EXPOSED",
        "BUTTOCKS_COVERED",
        "FEMALE_BREAST_EXPOSED",
        "FEMALE_BREAST_COVERED",
        "FEMALE_GENITALIA_EXPOSED",
        "FEMALE_GENITALIA_COVERED",
        "MALE_GENITALIA_EXPOSED",
        "ANUS_EXPOSED",
        "ANUS_COVERED",
        "BELLY_EXPOSED",
        "MALE_BREAST_EXPOSED",
    )

    fun peak(
        channelCount: Int,
        anchorCount: Int,
        valueAt: (channel: Int, anchor: Int) -> Float,
    ): Pair<String, Float> {
        if (channelCount < 5 || anchorCount <= 0) return "" to 0f
        val classCount = minOf(channelCount - 4, labels.size)
        var best = 0f
        var label = ""
        for (anchor in 0 until anchorCount) {
            for (classId in 0 until classCount) {
                val score = valueAt(4 + classId, anchor)
                if (score > best) {
                    best = score
                    label = labels[classId]
                }
            }
        }
        return label to best
    }

    fun blockingPeak(
        channelCount: Int,
        anchorCount: Int,
        valueAt: (channel: Int, anchor: Int) -> Float,
    ): Float {
        if (channelCount < 5 || anchorCount <= 0) return 0f
        val classCount = minOf(channelCount - 4, labels.size)
        var best = 0f
        for (anchor in 0 until anchorCount) {
            for (classId in 0 until classCount) {
                if (labels[classId] !in blockingLabels) continue
                val score = valueAt(4 + classId, anchor)
                if (score > best) best = score
            }
        }
        return best
    }

    fun decode(
        channelCount: Int,
        anchorCount: Int,
        valueAt: (channel: Int, anchor: Int) -> Float,
        imageWidth: Int,
        imageHeight: Int,
        scoreThreshold: Float,
    ): List<DetectionBox> {
        if (channelCount < 5 || anchorCount <= 0 || imageWidth <= 0 || imageHeight <= 0) {
            return emptyList()
        }
        val classCount = channelCount - 4
        val maxSide = max(imageWidth, imageHeight).toFloat()
        val scale = maxSide / MODEL_SIZE
        val candidates = ArrayList<DetectionBox>()

        for (anchor in 0 until anchorCount) {
            var bestBlocking = 0f
            var bestBlockingClass = -1
            for (classId in 0 until classCount) {
                if (classId >= labels.size || labels[classId] !in blockingLabels) continue
                val score = valueAt(4 + classId, anchor)
                if (score > bestBlocking) {
                    bestBlocking = score
                    bestBlockingClass = classId
                }
            }
            if (bestBlockingClass < 0 || bestBlocking < scoreThreshold) continue
            val label = labels[bestBlockingClass]

            val cx = valueAt(0, anchor)
            val cy = valueAt(1, anchor)
            val bw = valueAt(2, anchor)
            val bh = valueAt(3, anchor)
            var left = (cx - bw / 2f) * scale
            var top = (cy - bh / 2f) * scale
            var right = (cx + bw / 2f) * scale
            var bottom = (cy + bh / 2f) * scale
            left = left.coerceIn(0f, imageWidth.toFloat())
            top = top.coerceIn(0f, imageHeight.toFloat())
            right = right.coerceIn(0f, imageWidth.toFloat())
            bottom = bottom.coerceIn(0f, imageHeight.toFloat())
            if (right - left < 2f || bottom - top < 2f) continue
            candidates.add(DetectionBox(RectF(left, top, right, bottom), bestBlocking, label))
        }
        return nms(candidates, NMS_IOU)
    }

    fun nms(boxes: List<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedByDescending { it.score }
        val suppressed = BooleanArray(sorted.size)
        val kept = ArrayList<DetectionBox>(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val current = sorted[i]
            kept.add(current)
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                if (iou(current.bounds, sorted[j].bounds) >= iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val w = max(0f, right - left)
        val h = max(0f, bottom - top)
        val intersection = w * h
        val union = (a.right - a.left) * (a.bottom - a.top) +
            (b.right - b.left) * (b.bottom - b.top) - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }
}
