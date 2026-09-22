package com.cleaner.filter.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.cleaner.filter.FilterEngine

class FilterAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        FilterEngine.initialize(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            -> {
                rootInActiveWindow?.let { root ->
                    FilterEngine.handleAccessibilityText(root)
                    root.recycle()
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        FilterEngine.release()
        super.onDestroy()
    }
}
