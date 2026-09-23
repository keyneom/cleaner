package com.cleaner.filter.capture

import android.graphics.Bitmap

object FrameHasher {
    private const val HASH_SIZE = 8

    internal fun averageHash(bitmap: Bitmap, ignore: List<CoverRect> = emptyList()): Long {
        val argb = IntArray(HASH_SIZE * HASH_SIZE)
        val ignored = BooleanArray(HASH_SIZE * HASH_SIZE)
        for (gy in 0 until HASH_SIZE) {
            for (gx in 0 until HASH_SIZE) {
                val x = ((gx + 0.5f) * bitmap.width / HASH_SIZE).toInt()
                    .coerceIn(0, bitmap.width - 1)
                val y = ((gy + 0.5f) * bitmap.height / HASH_SIZE).toInt()
                    .coerceIn(0, bitmap.height - 1)
                val index = gy * HASH_SIZE + gx
                if (ignore.any { contains(it, x.toFloat(), y.toFloat()) }) {
                    ignored[index] = true
                    continue
                }
                argb[index] = bitmap.getPixel(x, y)
            }
        }
        return hashSamples(argb, ignored)
    }

    internal fun hashSamples(argb: IntArray, ignored: BooleanArray): Long {
        var sum = 0L
        var count = 0
        for (i in argb.indices) {
            if (ignored[i]) continue
            val pixel = argb[i]
            sum += ((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)
            count++
        }
        val avg = if (count == 0) 0L else sum / (count * 3)
        var hash = 0L
        for (i in argb.indices) {
            if (ignored[i]) continue
            val pixel = argb[i]
            val luminance = (((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)) / 3
            if (luminance >= avg) {
                hash = hash or (1L shl i)
            }
        }
        return hash
    }

    private fun contains(rect: CoverRect, x: Float, y: Float): Boolean =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

    fun hammingDistance(a: Long, b: Long): Int {
        var x = a xor b
        var count = 0
        while (x != 0L) {
            count += (x and 1L).toInt()
            x = x ushr 1
        }
        return count
    }

    fun isSimilar(a: Long, b: Long, threshold: Int = 4): Boolean =
        hammingDistance(a, b) <= threshold
}
