package com.cleaner.filter

import android.app.Application
import com.cleaner.filter.settings.FilterPreferences
import org.lsposed.hiddenapibypass.HiddenApiBypass

class CleanerApp : Application() {
    lateinit var preferences: FilterPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = FilterPreferences(this)
        try {
            // Need ViewRootImpl.getSurfaceControl + Transaction.setSkipScreenshot.
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/View;",
                "Landroid/view/ViewRootImpl;",
                "Landroid/view/SurfaceControl;",
                "Landroid/view/SurfaceControl\$Transaction;",
                "Landroid/view/SurfaceControl\$Builder;",
                "Landroid/view/WindowManager\$LayoutParams;",
            )
        } catch (_: Throwable) {
        }
    }

    companion object {
        lateinit var instance: CleanerApp
            private set
    }
}
