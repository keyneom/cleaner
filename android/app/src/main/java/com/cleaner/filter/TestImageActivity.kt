package com.cleaner.filter

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity

/**
 * Scrollable local test image (bundled asset). No network / no Google Search.
 * Use with the filter running to verify covers without opening Chrome.
 *
 * Stacks several natural-aspect copies so the page is tall enough to scroll
 * (for SafeFrame shift tests) without stretching the bitmap (which drops NudeNet hits).
 */
class TestImageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        val bitmap = assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
        val imageHeight = (metrics.widthPixels.toLong() * bitmap.height / bitmap.width).toInt()
            .coerceAtLeast(1)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // Enough stacked copies to exceed ~2.5 screens so swipes move content.
        val copies = ((metrics.heightPixels * 3) / imageHeight).coerceAtLeast(3)
        repeat(copies) {
            column.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageBitmap(bitmap)
                    adjustViewBounds = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        imageHeight,
                    )
                },
            )
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF111111.toInt())
            addView(column)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // This screen is meant to be filtered — turn covers back on.
        FilterEngine.setOverlayPausedForOwnUi(false)
    }

    companion object {
        const val ASSET = "test/trigger_covered.jpg"
    }
}
