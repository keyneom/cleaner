package com.cleaner.filter.text

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class TextHit(
    val text: String,
    val bounds: Rect?,
)

class TextFilterEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun scanAccessibilityTree(root: AccessibilityNodeInfo?): List<TextHit> {
        if (root == null) return emptyList()
        val hits = mutableListOf<TextHit>()
        collectNodes(root, hits)
        return hits.filter { ProfanityWordLists.containsBlocked(it.text) }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<TextHit>) {
        val text = buildString {
            node.text?.let { append(it) }
            node.contentDescription?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }.trim()
        if (text.isNotEmpty()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            out.add(TextHit(text, bounds))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out) }
        }
    }

    suspend fun scanBitmapOcr(bitmap: Bitmap): List<TextHit> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            result.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    TextHit(line.text, line.boundingBox)
                }
            }.filter { ProfanityWordLists.containsBlocked(it.text) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun close() {
        recognizer.close()
    }
}
