package com.cleaner.filter.capture

import android.os.SystemClock

/**
 * Shared presentation clock for A/V sync. Frames captured at [captureNanos] are shown/played at
 * captureNanos + delayNs.
 */
class PresentationClock(initialDelayMs: Long = 50L) {
    @Volatile
    var delayMs: Long = initialDelayMs
        private set

    fun setDelayMs(ms: Long) {
        delayMs = ms.coerceIn(33L, 200L)
    }

    fun captureTimestampNanos(): Long = SystemClock.elapsedRealtimeNanos()

    fun presentationTimeFor(captureNanos: Long): Long =
        captureNanos + delayMs * 1_000_000L

    fun shouldPresentNow(captureNanos: Long, nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Boolean =
        nowNanos >= presentationTimeFor(captureNanos)
}
