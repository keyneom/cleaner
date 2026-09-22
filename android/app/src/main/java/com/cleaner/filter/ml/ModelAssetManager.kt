package com.cleaner.filter.ml

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object ModelAssetManager {
    private const val CACHE_NAME = "nsfw_gate.tflite"

    /**
     * Returns path to TFLite model if present in assets or cache.
     * Place `nsfw_gate.tflite` in assets/models/ for production (MobileNet NSFW gate).
     */
    fun ensureModel(context: Context): File? {
        val cacheDir = File(context.filesDir, "models")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cached = File(cacheDir, CACHE_NAME)
        if (cached.exists() && cached.length() > 0) return cached

        return try {
            context.assets.open(NsfwClassifier.MODEL_ASSET).use { input ->
                FileOutputStream(cached).use { output ->
                    input.copyTo(output)
                }
            }
            cached
        } catch (_: Exception) {
            null
        }
    }
}
