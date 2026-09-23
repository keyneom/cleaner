package com.cleaner.filter.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.cleaner.filter.FilterEngine
import java.util.concurrent.Executors

/**
 * Follows whichever window is in front. Each shot uses takeScreenshotOfWindow, which
 * excludes this overlay, so the mirror cannot film itself. Switching apps retargets
 * the next shot. Android rate-limits this API to about 3 frames per second.
 */
class FilterAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var filtering = false
    private var captureInFlight = false
    private var gotFrame = false
    private var consecutiveFailures = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FilterEngine.onAccessibilityServiceConnected(this)
    }

    fun startFilter() {
        Log.i(TAG, "screenshot loop disabled; frame stream is MediaProjection")
    }

    fun stopFilter() {
        filtering = false
        FilterEngine.wantsRunning = false
        captureInFlight = false
        mainHandler.removeCallbacksAndMessages(null)
        // Capture/overlay lifecycle is owned by CaptureForegroundService + FilterEngine.startCapture.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        updateOwnUiPause(event)
        FilterEngine.setUnfilterable(foregroundIsIncognito())
    }

    private fun updateOwnUiPause(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) {
            // Another app in front — filtering on.
            FilterEngine.setOverlayPausedForOwnUi(false)
            return
        }
        val className = event.className?.toString().orEmpty()
        when {
            className.contains("TestImageActivity") -> FilterEngine.setOverlayPausedForOwnUi(false)
            className.contains("MainActivity") -> FilterEngine.setOverlayPausedForOwnUi(true)
            // Ignore other Cleaner window events (overlay, services) — do not flip pause.
        }
    }

    private fun foregroundIsIncognito(): Boolean {
        val open = windows ?: return false
        for (window in open) {
            if (!window.isFocused && !window.isActive) continue
            val title = window.title?.toString().orEmpty()
            if (title.contains("Incognito", ignoreCase = true)) return true
            val root = window.root
            val pkg = root?.packageName?.toString().orEmpty()
            root?.recycle()
            if (pkg == "org.mozilla.firefox" && title.contains("Private", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        stopFilter()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        worker.shutdownNow()
        // Do not tear down overlay while MediaProjection is still presenting frames.
        if (!FilterEngine.isRunning()) {
            FilterEngine.detachAccessibility()
        } else {
            Log.w(TAG, "a11y onDestroy while capture running")
        }
        super.onDestroy()
    }

    private fun scheduleCapture(delayMs: Long) {
        mainHandler.postDelayed({ captureActiveWindow() }, delayMs)
    }

    private fun captureActiveWindow() {
        if (!filtering || captureInFlight) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            scheduleCapture(1000)
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "no active window")
            scheduleCapture(CAPTURE_INTERVAL_MS)
            return
        }
        val windowId = root.windowId
        val screenBounds = Rect()
        root.getBoundsInScreen(screenBounds)
        root.recycle()
        captureInFlight = true
        Log.i(TAG, "screenshot window=$windowId")
        try {
            takeScreenshotOfWindow(windowId, worker, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                worker.execute {
                    try {
                        publishDetections(screenshot, screenBounds)
                    } finally {
                        captureInFlight = false
                        if (filtering) scheduleCapture(CAPTURE_INTERVAL_MS)
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed code=$errorCode")
                captureInFlight = false
                noteFailure()
            }
            })
        } catch (error: RuntimeException) {
            Log.e(TAG, "screenshot threw", error)
            captureInFlight = false
            noteFailure()
        }
    }

    private fun noteFailure() {
        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_FAILURES) {
            mainHandler.post { stopFilter() }
            return
        }
        if (filtering) scheduleCapture(CAPTURE_INTERVAL_MS)
    }

    private fun publishDetections(screenshot: ScreenshotResult, screenBounds: Rect) {
        val hardwareBuffer = screenshot.hardwareBuffer
        val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
        if (hardwareBitmap == null) {
            hardwareBuffer.close()
            noteFailure()
            return
        }
        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        hardwareBitmap.recycle()
        hardwareBuffer.close()
        if (bitmap == null) {
            noteFailure()
            return
        }
        val classified = FilterEngine.classify(bitmap)
        val regions = classified.boxes.map { it.bounds }
        mainHandler.post {
            if (!filtering) {
                bitmap.recycle()
                return@post
            }
            gotFrame = true
            consecutiveFailures = 0
            Log.i(TAG, "mirror ${bitmap.width}x${bitmap.height} regions=${regions.size}")
            FilterEngine.overlayController()?.presentMirror(bitmap, regions, screenBounds)
        }
    }

    companion object {
        private const val TAG = "CleanerFilter"
        private const val CAPTURE_INTERVAL_MS = 400L
        private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
        private const val MAX_FAILURES = 12

        @Volatile
        var instance: FilterAccessibilityService? = null
            private set
    }
}
