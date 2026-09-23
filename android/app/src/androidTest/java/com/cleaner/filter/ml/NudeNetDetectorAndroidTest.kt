package com.cleaner.filter.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NudeNetDetectorAndroidTest {
    @Test
    fun tallBlankFrameDoesNotCover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(144, 320, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        NudeNetDetector(context).use { detector ->
            val result = detector.detect(bitmap, 0.25f)
            assertFalse(result.isUnsafe)
            assertTrue(result.boxes.isEmpty())
            assertTrue(result.inferenceMs in 1..20_000)
        }
        bitmap.recycle()
    }

    @Test
    fun coveredBodyPhotoTriggersAtLowThreshold() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("trigger_covered.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        require(bitmap != null)
        NudeNetDetector(instrumentation.targetContext).use { detector ->
            val result = detector.detect(bitmap, 0.03f)
            assertTrue(
                "expected blocking boxes, got score=${result.score} top=${result.topScore} ms=${result.inferenceMs}",
                result.boxes.isNotEmpty(),
            )
            assertTrue(result.isUnsafe)
            assertTrue(
                "classify should be well under a second on device, was ${result.inferenceMs}ms",
                result.inferenceMs < 1_500,
            )
        }
        bitmap.recycle()
    }
}
