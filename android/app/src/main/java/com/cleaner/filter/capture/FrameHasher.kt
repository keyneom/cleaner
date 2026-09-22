package com.cleaner.filter.capture

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

object FrameHasher {
    private const val HASH_SIZE = 8

    fun averageHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        var sum = 0L
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        scaled.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        if (scaled != bitmap) scaled.recycle()

        for (pixel in pixels) {
            sum += Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        }
        val avg = sum / (HASH_SIZE * HASH_SIZE * 3)

        var hash = 0L
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val luminance = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            if (luminance >= avg) {
                hash = hash or (1L shl i)
            }
        }
        return hash
    }

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
