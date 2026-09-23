package com.cleaner.filter.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowManager
import android.accessibilityservice.AccessibilityService
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Keeps this overlay visible on the phone but out of screen capture, so the
 * frame stream still sees the app underneath the filtered picture.
 */
internal object CaptureExclusion {
    fun exclude(view: View): Boolean {
        if (excludeViaTransaction(view)) return true
        if (excludeViaPrivateFlag(view)) return true
        return false
    }

    fun excludeSurface(surface: SurfaceControl): Boolean {
        return try {
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getDeclaredConstructor().newInstance()
            HiddenApiBypass.invoke(
                transactionClass,
                transaction,
                "setSkipScreenshot",
                surface,
                true,
            )
            transactionClass.getMethod("apply").invoke(transaction)
            Log.i(TAG, "overlay excluded from screen capture")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "surface exclusion failed: ${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    /**
     * AccessibilityService is not a display-associated Context on recent Pixels —
     * [AccessibilityService.getDisplay] throws. Use createDisplayContext, then force
     * the attached surface visible (alpha/layer) so it is not an invisible attach.
     */
    fun attachExcludedOverlay(
        service: AccessibilityService,
        view: View,
        width: Int,
        height: Int,
    ): AttachedOverlay? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            val displayManager = service.getSystemService(DisplayManager::class.java)
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val visualContext = service.createDisplayContext(display)
            val host = SurfaceControlViewHost(visualContext, display, Binder())
            host.setView(view, width, height)
            val surfacePackage = host.surfacePackage ?: return null
            val surface = surfacePackage.surfaceControl
            // Make sure the surface is actually shown before skip-screenshot.
            try {
                SurfaceControl.Transaction()
                    .setVisibility(surface, true)
                    .setAlpha(surface, 1f)
                    .apply()
            } catch (error: Throwable) {
                Log.w(TAG, "surface show failed: ${error.message}")
            }
            service.attachAccessibilityOverlayToDisplay(display.displayId, surface)
            val excluded = excludeSurface(surface)
            if (!excluded) {
                SurfaceControl.Transaction().reparent(surface, null).apply()
                host.release()
                return null
            }
            AttachedOverlay(host, surface)
        } catch (error: Throwable) {
            Log.w(TAG, "attachExcludedOverlay failed: ${error.javaClass.simpleName} ${error.message}")
            null
        }
    }

    private fun excludeViaTransaction(view: View): Boolean {
        return try {
            if (!view.isAttachedToWindow) return false
            val root = HiddenApiBypass.invoke(View::class.java, view, "getViewRootImpl")
                ?: return false
            val surface = surfaceControlOf(root) ?: return false
            excludeSurface(surface as SurfaceControl)
        } catch (error: Throwable) {
            Log.w(TAG, "overlay capture exclusion failed: ${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    private fun surfaceControlOf(root: Any): Any? {
        try {
            val surface = HiddenApiBypass.invoke(root.javaClass, root, "getSurfaceControl")
            if (surface != null) return surface
        } catch (_: Throwable) {
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val fields = HiddenApiBypass.getInstanceFields(root.javaClass) as List<java.lang.reflect.Field>
            val field = fields.firstOrNull { it.name == "mSurfaceControl" } ?: return null
            field.isAccessible = true
            field.get(root)
        } catch (_: Throwable) {
            null
        }
    }

    private fun excludeViaPrivateFlag(view: View): Boolean {
        return try {
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return false
            val lpClass = WindowManager.LayoutParams::class.java
            @Suppress("UNCHECKED_CAST")
            val staticFields = HiddenApiBypass.getStaticFields(lpClass) as List<java.lang.reflect.Field>
            val flagField = staticFields.firstOrNull { it.name == "PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY" }
                ?: return false
            flagField.isAccessible = true
            val flag = flagField.getInt(null)
            @Suppress("UNCHECKED_CAST")
            val instanceFields = HiddenApiBypass.getInstanceFields(lpClass) as List<java.lang.reflect.Field>
            val privateFlagsField = instanceFields.firstOrNull { it.name == "privateFlags" }
                ?: return false
            privateFlagsField.isAccessible = true
            val current = privateFlagsField.getInt(params)
            privateFlagsField.setInt(params, current or flag)
            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.updateViewLayout(view, params)
            val viaSurface = excludeViaTransaction(view)
            if (viaSurface) {
                Log.i(TAG, "overlay excluded via privateFlags+transaction")
                return true
            }
            Log.w(TAG, "privateFlags set but surface exclusion still failed")
            false
        } catch (error: Throwable) {
            Log.w(TAG, "privateFlags exclusion failed: ${error.javaClass.simpleName} ${error.message}")
            false
        }
    }

    data class AttachedOverlay(
        val host: SurfaceControlViewHost,
        val surface: SurfaceControl,
    ) {
        fun release() {
            try {
                SurfaceControl.Transaction().reparent(surface, null).apply()
            } catch (_: Throwable) {
            }
            try {
                host.release()
            } catch (_: Throwable) {
            }
        }
    }

    private const val TAG = "CleanerFilter"
}
