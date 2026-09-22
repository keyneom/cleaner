package com.cleaner.filter.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NudeNetDecoderTest {
    @Test
    fun keepsExposedBoxAboveThreshold() {
        val channels = 4 + NudeNetDecoder.labels.size
        val anchors = 1
        val values = FloatArray(channels)
        values[0] = 160f
        values[1] = 160f
        values[2] = 40f
        values[3] = 40f
        val breast = NudeNetDecoder.labels.indexOf("FEMALE_BREAST_EXPOSED")
        values[4 + breast] = 0.8f

        val boxes = NudeNetDecoder.decode(
            channelCount = channels,
            anchorCount = anchors,
            valueAt = { channel, _ -> values[channel] },
            imageWidth = 320,
            imageHeight = 320,
            scoreThreshold = 0.25f,
        )
        assertEquals(1, boxes.size)
        assertEquals("FEMALE_BREAST_EXPOSED", boxes[0].label)
        assertTrue(boxes[0].score > 0.7f)
    }

    @Test
    fun ignoresFaces() {
        val channels = 4 + NudeNetDecoder.labels.size
        val values = FloatArray(channels)
        values[0] = 50f
        values[1] = 50f
        values[2] = 20f
        values[3] = 20f
        values[4 + NudeNetDecoder.labels.indexOf("FACE_FEMALE")] = 0.99f

        val boxes = NudeNetDecoder.decode(
            channelCount = channels,
            anchorCount = 1,
            valueAt = { channel, _ -> values[channel] },
            imageWidth = 320,
            imageHeight = 320,
            scoreThreshold = 0.25f,
        )
        assertTrue(boxes.isEmpty())
    }

}
