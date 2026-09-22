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

    var accessibilityTextScanner: ((android.view.accessibility.AccessibilityNodeInfo?) -> Unit)? = null

    fun initialize(context: Context) {
        if (classifier == null) {
            classifier = NsfwClassifier(context)
            textFilter = TextFilterEngine()
            overlay = FilterOverlayController(context)
            audioPipeline = FilteredAudioPipeline(context, clock)
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
        initialize(context)
        running = true
        overlay?.show()

        pipeline = ScreenCapturePipeline(
            classifier = classifier!!,
            clock = clock,
            onFrame = { frame ->
                val boxes = frame.classification?.boxes.orEmpty()
                overlay?.present(
                    bitmap = if (frame.coverAll) null else frame.bitmap,
                    boxes = frame.classification?.boxes.orEmpty(),
                    textHits = frame.textHits,
                    coverAll = frame.coverAll,
                    statsFps = frame.metrics.fps,
                    statsInferenceMs = frame.metrics.inferenceMs,
                )
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

    fun handleAccessibilityText(root: android.view.accessibility.AccessibilityNodeInfo?) {
        if (!running || !settings.textFilterEnabled) return
        val hits = textFilter?.scanAccessibilityTree(root).orEmpty()
        if (hits.isNotEmpty()) {
            overlay?.present(
                bitmap = null,
                boxes = emptyList(),
                textHits = hits,
                coverAll = true,
            )
        }
    }
}
