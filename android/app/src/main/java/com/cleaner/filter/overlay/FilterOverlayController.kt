package com.cleaner.filter.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.cleaner.filter.ml.DetectionBox
import com.cleaner.filter.service.FilterAccessibilityService
import com.cleaner.filter.text.TextHit

data class OverlayFrame(
    val bitmap: Bitmap?,
    val dest: RectF? = null,
    val blurRegions: List<RectF> = emptyList(),
    val waitingCover: Boolean = false,
    val blockUnfilterable: Boolean = false,
    val coverAll: Boolean = false,
)

class FilterOverlayView(context: Context) : android.view.View(context) {
    private var frame: OverlayFrame? = null
    private val blurPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        isAntiAlias = false
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val noticeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
    }

    fun updateFrame(newFrame: OverlayFrame) {
        val previous = frame?.bitmap
        frame = newFrame
        if (previous != null && previous !== newFrame.bitmap && !previous.isRecycled) {
            previous.recycle()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val current = frame ?: return
        if (current.blockUnfilterable) {
            canvas.drawColor(Color.BLACK)
            canvas.drawText("Incognito can't be filtered.", 48f, height * 0.45f, noticeText)
            canvas.drawText("Close incognito to continue.", 48f, height * 0.45f + 72f, noticeText)
            return
        }
        if (current.coverAll) {
            canvas.drawColor(Color.BLACK)
            return
        }
        if (current.waitingCover) {
            // Leave status / nav band clear; black only the content area until first frame.
            val top = height * 0.045f
            val bottom = height * (1f - 0.055f)
            canvas.drawRect(0f, top, width.toFloat(), bottom, blurPaint)
            return
        }
        val bmp = current.bitmap
        if (bmp != null && !bmp.isRecycled) {
            val dest = current.dest ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, dest, bitmapPaint)
        }
        for (region in current.blurRegions) {
            canvas.drawRect(region, blurPaint)
        }
    }
}

/**
 * NudeNet + SurfaceControlViewHost mirror path (the one that worked on Pixel):
 * excluded from capture, shows only processed frames / black covers.
 */
class FilterOverlayController(private var context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var windowManager: WindowManager? = null
    private var overlayView: FilterOverlayView? = null
    private var shown = false
    private var attachedOverlay: CaptureExclusion.AttachedOverlay? = null
    private var usingWindowManager = false
    private var unfilterable = false
    private var ownerService: AccessibilityService? = null

    fun rebind(serviceContext: Context) {
        context = serviceContext
    }

    fun forceReshow() {
        mainHandler.post {
            synchronized(lock) {
                teardownLocked()
                showLocked()
            }
        }
    }

    /**
     * Remount if the surface is gone, or a new AccessibilityService instance took over
     * (old SCVH is orphaned and invisible even when isAttachedToWindow stays true).
     */
    fun ensureShowing() {
        mainHandler.post {
            synchronized(lock) {
                val service = context as? AccessibilityService
                    ?: FilterAccessibilityService.instance
                val sameOwner = service != null && ownerService === service
                if (shown && overlayView?.isAttachedToWindow == true && sameOwner) {
                    return@synchronized
                }
                if (shown) teardownLocked()
                if (service != null) context = service
                showLocked()
            }
        }
    }

    fun show() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized(lock) { showLocked() }
        } else {
            mainHandler.post { synchronized(lock) { showLocked() } }
        }
    }

    private fun showLocked() {
        val service = context as? AccessibilityService
            ?: FilterAccessibilityService.instance
        if (shown && overlayView?.isAttachedToWindow == true && ownerService === service) {
            return
        }
        if (shown) teardownLocked()

        if (service == null) {
            Log.w(TAG, "cannot show overlay: accessibility service null")
            return
        }
        context = service
        ownerService = service

        val view = FilterOverlayView(service)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isClickable = false
        view.isFocusable = false
        view.updateFrame(OverlayFrame(bitmap = null, waitingCover = true))

        // WindowManager first: visible TYPE_ACCESSIBILITY_OVERLAY + setSkipScreenshot.
        // SCVH with createDisplayContext historically attached while staying invisible on Pixel.
        if (attachViaWindowManager(service, view)) {
            Log.i(TAG, "overlay attached via WindowManager")
            return
        }
        val metrics = service.resources.displayMetrics
        val attached = CaptureExclusion.attachExcludedOverlay(
            service,
            view,
            metrics.widthPixels,
            metrics.heightPixels,
        )
        if (attached != null) {
            attachedOverlay = attached
            overlayView = view
            shown = true
            usingWindowManager = false
            view.post {
                try {
                    view.rootSurfaceControl?.setTouchableRegion(android.graphics.Region())
                } catch (_: Throwable) {
                }
            }
            com.cleaner.filter.FilterEngine.onCaptureExclusionReady()
            view.updateFrame(OverlayFrame(bitmap = null, waitingCover = true))
            Log.i(TAG, "overlay attached via SurfaceControlViewHost")
            return
        }
        Log.e(TAG, "overlay attach failed on both paths")
    }

    private fun attachViaWindowManager(service: AccessibilityService, view: FilterOverlayView): Boolean {
        return try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = service.resources.displayMetrics
            // Explicit px size — MATCH_PARENT under TYPE_ACCESSIBILITY_OVERLAY was
            // creating a ready window with surface=[0,0][0,0] (invisible covers).
            val params = WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(view, params)
            windowManager = wm
            overlayView = view
            shown = true
            usingWindowManager = true
            view.post {
                Log.i(
                    TAG,
                    "WM overlay laid out ${view.width}x${view.height} " +
                        "attached=${view.isAttachedToWindow}",
                )
                if (CaptureExclusion.exclude(view)) {
                    com.cleaner.filter.FilterEngine.onCaptureExclusionReady()
                    view.updateFrame(OverlayFrame(bitmap = null, waitingCover = true))
                    Log.i(TAG, "WM overlay excluded from capture")
                } else {
                    Log.w(TAG, "WM overlay visible but NOT excluded — leaving mirror off")
                }
            }
            true
        } catch (error: Throwable) {
            Log.w(TAG, "WM overlay failed: ${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    fun presentMirror(bitmap: Bitmap, regionsInBitmap: List<RectF>, destOnScreen: android.graphics.Rect) {
        mainHandler.post {
            synchronized(lock) {
                if (!shown) showLocked()
                overlayView?.updateFrame(
                    OverlayFrame(
                        bitmap = bitmap,
                        dest = RectF(destOnScreen),
                        blurRegions = regionsInBitmap,
                        waitingCover = false,
                    ),
                )
            }
        }
    }

    fun hide() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            synchronized(lock) { teardownLocked() }
        } else {
            mainHandler.post { synchronized(lock) { teardownLocked() } }
        }
    }

    /** Soft hide for Cleaner settings — keep the window attached so exclusion survives. */
    fun setContentVisible(visible: Boolean) {
        mainHandler.post {
            synchronized(lock) {
                if (visible) {
                    if (!shown || overlayView == null) showLocked()
                    overlayView?.visibility = android.view.View.VISIBLE
                } else {
                    overlayView?.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun teardownLocked() {
        if (!shown && overlayView == null) return
        Log.i(TAG, "overlay teardown wm=$usingWindowManager")
        attachedOverlay?.release()
        attachedOverlay = null
        if (usingWindowManager) {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            }
        }
        overlayView = null
        windowManager = null
        shown = false
        usingWindowManager = false
        ownerService = null
    }

    fun present(
        bitmap: Bitmap?,
        boxes: List<DetectionBox>,
        textHits: List<TextHit>,
        coverAll: Boolean,
        statsFps: Float = 0f,
        statsInferenceMs: Long = 0,
    ) {
        val regions = buildList {
            addAll(boxes.map { it.bounds })
            addAll(textHits.mapNotNull { hit -> hit.bounds?.let { RectF(it) } })
        }
        mainHandler.post {
            synchronized(lock) {
                if (!shown || overlayView == null) {
                    showLocked()
                }
                val view = overlayView ?: run {
                    bitmap?.recycle()
                    return@synchronized
                }
                view.updateFrame(
                    OverlayFrame(
                        bitmap = bitmap,
                        blurRegions = regions,
                        waitingCover = false,
                        blockUnfilterable = unfilterable,
                        coverAll = coverAll,
                    ),
                )
            }
        }
    }

    fun setUnfilterable(blocked: Boolean) {
        unfilterable = blocked
        mainHandler.post {
            synchronized(lock) {
                val view = overlayView ?: return@synchronized
                if (usingWindowManager) {
                    val params = view.layoutParams as? WindowManager.LayoutParams ?: return@synchronized
                    val touchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    params.flags = if (blocked) {
                        params.flags and touchable.inv()
                    } else {
                        params.flags or touchable
                    }
                    windowManager?.updateViewLayout(view, params)
                }
                if (blocked) {
                    view.updateFrame(OverlayFrame(bitmap = null, blockUnfilterable = true))
                }
            }
        }
    }

    fun isShowing(): Boolean = shown

    companion object {
        private const val TAG = "CleanerFilter"
    }
}
