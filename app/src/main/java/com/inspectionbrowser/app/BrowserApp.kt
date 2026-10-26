package com.inspectionbrowser.app

import android.app.Application
import com.inspectionbrowser.app.data.Library
import com.inspectionbrowser.app.data.Settings
import com.inspectionbrowser.app.update.AppUpdater

class BrowserApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var library: Library
        private set
    lateinit var updater: AppUpdater
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        library = Library(this)
        updater = AppUpdater(this, asset = "Inspection-Browser.apk")
    }
}
