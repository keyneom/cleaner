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
 */
class TestImageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        val pageHeight = metrics.heightPixels * 2
        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF111111.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                pageHeight,
            )
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF111111.toInt())
            addView(view)
        }
        setContentView(scroll)
        val bitmap = assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
        view.setImageBitmap(bitmap)
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
