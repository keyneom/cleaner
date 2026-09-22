package com.cleaner.filter

import android.app.Application
import com.cleaner.filter.settings.FilterPreferences

class CleanerApp : Application() {
    lateinit var preferences: FilterPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = FilterPreferences(this)
    }

    companion object {
        lateinit var instance: CleanerApp
            private set
    }
}
