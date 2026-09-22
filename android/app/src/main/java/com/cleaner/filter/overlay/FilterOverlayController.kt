package com.cleaner.filter.overlay

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
import android.view.Gravity
import android.view.WindowManager
import com.cleaner.filter.ml.DetectionBox
import com.cleaner.filter.text.TextHit

data class OverlayFrame(
    val bitmap: Bitmap?,
    val blurRegions: List<RectF> = emptyList(),
    val coverAll: Boolean = true,
    val statsFps: Float = 0f,
    val statsInferenceMs: Long = 0,
)

class FilterOverlayView(context: Context) : android.view.View(context) {
    private var frame: OverlayFrame? = null
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 30, 30, 30)
        style = Paint.Style.FILL
    }
    private val coverPaint = Paint().apply {
        color = Color.argb(255, 20, 20, 28)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val xferPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
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
        val f = frame ?: run {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), coverPaint)
            return
        }

        if (f.coverAll && f.bitmap == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), coverPaint)
            return
        }

        f.bitmap?.let { bmp ->
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }

        for (region in f.blurRegions) {
            canvas.drawRect(region, blurPaint)
        }
    }
}

class FilterOverlayController(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var overlayView: FilterOverlayView? = null
    private var shown = false

    fun show() {
        if (shown) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = FilterOverlayView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(view, params)
        windowManager = wm
        overlayView = view
        shown = true
        view.updateFrame(OverlayFrame(bitmap = null, coverAll = true))
    }

    fun hide() {
        if (!shown) return
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        windowManager = null
        shown = false
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
            addAll(textHits.mapNotNull { hit ->
                hit.bounds?.let { RectF(it) }
            })
        }
        overlayView?.updateFrame(
            OverlayFrame(
                bitmap = bitmap,
                blurRegions = regions,
                coverAll = coverAll,
                statsFps = statsFps,
                statsInferenceMs = statsInferenceMs,
            ),
        )
    }

    fun isShowing(): Boolean = shown
}
