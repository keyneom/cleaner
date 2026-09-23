package com.cleaner.filter

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.cleaner.filter.audio.FilteredAudioPipeline
import com.cleaner.filter.capture.PresentationClock
import com.cleaner.filter.capture.ScreenCapturePipeline
import com.cleaner.filter.dns.DnsVpnController
import com.cleaner.filter.ml.NsfwClassifier
import com.cleaner.filter.overlay.FilterOverlayController
import com.cleaner.filter.settings.FilterSettings
import com.cleaner.filter.text.TextFilterEngine

/**
 * Central coordinator for capture, overlay, audio, and DNS layers.
 */
object FilterEngine {
    private var classifier: NsfwClassifier? = null
    private var textFilter: TextFilterEngine? = null
    private var overlay: FilterOverlayController? = null
    private var pipeline: ScreenCapturePipeline? = null
    private var audioPipeline: FilteredAudioPipeline? = null
    private val clock = PresentationClock()
    private var settings: FilterSettings = FilterSettings()
    private var running = false
    var wantsRunning = false

    @Volatile
    var mirrorProcessedFrames = false
        private set

    /** True while Cleaner settings (not the test image) is in front — hide covers so sliders work. */
    @Volatile
    var overlayPausedForOwnUi = false
        private set

    fun onCaptureExclusionReady() {
        mirrorProcessedFrames = true
        android.util.Log.i("CleanerFilter", "showing processed frames only")
    }

    fun setUnfilterable(blocked: Boolean) {
        overlay?.setUnfilterable(blocked)
    }

    /**
     * Pause the filter overlay while the user is in Cleaner's own settings UI so controls
     * (threshold slider, etc.) stay visible and usable. Keep filtering on TestImageActivity.
     */
    fun setOverlayPausedForOwnUi(paused: Boolean) {
        if (overlayPausedForOwnUi == paused) return
        overlayPausedForOwnUi = paused
        android.util.Log.i("CleanerFilter", "overlay paused for own UI=$paused")
        if (paused) {
            overlay?.setContentVisible(false)
        } else if (running) {
            overlay?.setContentVisible(true)
        }
    }
    @Volatile
    var probe: android.graphics.RectF? = null

    var accessibilityTextScanner: ((android.view.accessibility.AccessibilityNodeInfo?) -> Unit)? = null

    fun initialize(context: Context) {
        attachAccessibility(context)
    }

    /** Overlay windows have to be created from the accessibility service. */
    fun attachAccessibility(context: Context) {
        ensureRuntime(context)
        if (overlay == null) {
            overlay = FilterOverlayController(context)
        } else {
            overlay?.rebind(context)
        }
        if (running) {
            overlay?.ensureShowing()
        }
    }

    fun onAccessibilityServiceConnected(service: android.accessibilityservice.AccessibilityService) {
        attachAccessibility(service)
    }

    fun detachAccessibility() {
        // While capture is live, keep the controller; the service may restart and remount.
        if (running) {
            android.util.Log.w("CleanerFilter", "a11y detach while capturing — keeping overlay controller")
            return
        }
        try {
            overlay?.hide()
        } catch (_: Exception) {
        }
        overlay = null
    }

    private fun ensureRuntime(context: Context) {
        if (classifier == null) {
            classifier = NsfwClassifier(context.applicationContext)
            textFilter = TextFilterEngine()
        }
        if (audioPipeline == null) {
            audioPipeline = FilteredAudioPipeline(context.applicationContext, clock)
        }
    }

    fun updateSettings(context: Context, newSettings: FilterSettings) {
        settings = newSettings
        clock.setDelayMs(newSettings.presentationDelayMs)
        pipeline?.updateSettings(newSettings)
        audioPipeline?.updateSettings(newSettings)
        DnsVpnController.sync(context, newSettings)
    }

    fun startCapture(
        context: Context,
        projection: MediaProjection,
        metrics: android.util.DisplayMetrics,
    ) {
        ensureRuntime(context)
        pipeline?.stop()
        running = true
        if (overlayPausedForOwnUi) {
            overlay?.hide()
        } else {
            overlay?.show()
        }

        pipeline = ScreenCapturePipeline(
            classifier = classifier!!,
            clock = clock,
            onFrame = { frame ->
                if (overlayPausedForOwnUi) {
                    frame.bitmap?.recycle()
                } else {
                    val mirror = mirrorProcessedFrames
                    val boxes = frame.classification?.boxes.orEmpty().toMutableList()
                    probe?.let { boxes.add(com.cleaner.filter.ml.DetectionBox(it, 1f, "probe")) }
                    val shownBitmap = if (mirror) frame.bitmap else null
                    if (!mirror) frame.bitmap?.recycle()
                    overlay?.present(
                        bitmap = shownBitmap,
                        boxes = boxes,
                        textHits = emptyList(),
                        coverAll = frame.coverAll,
                    )
                }
            },
        ).also {
            it.updateSettings(settings)
            it.start(projection, metrics)
        }

        audioPipeline?.updateSettings(settings)
        audioPipeline?.start(projection)
    }

    fun stopCapture(context: Context) {
        running = false
        pipeline?.stop()
        pipeline = null
        audioPipeline?.stop()
        overlay?.hide()
        DnsVpnController.stop(context)
    }

    fun isRunning(): Boolean = running

    fun release() {
        stopCapture(CleanerApp.instance)
        textFilter?.close()
        textFilter = null
        classifier?.close()
        classifier = null
        overlay = null
        audioPipeline = null
    }

    fun classify(bitmap: android.graphics.Bitmap): com.cleaner.filter.ml.ClassificationResult {
        val active = classifier ?: return com.cleaner.filter.ml.ClassificationResult(false, 0f)
        if (!settings.visualNudityEnabled) {
            return com.cleaner.filter.ml.ClassificationResult(false, 0f)
        }
        return active.classify(bitmap, settings.visualSensitivity)
    }

    fun setRunning(value: Boolean) {
        running = value
    }

    fun overlayController(): FilterOverlayController? = overlay
}
