package com.inspectionbrowser.app

import android.app.Application
import com.inspectionbrowser.app.browser.TestSite
import com.inspectionbrowser.app.data.Library
import com.inspectionbrowser.app.data.Settings
import com.inspectionbrowser.app.devtools.DevtoolsScript
import com.inspectionbrowser.app.net.Fetcher
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
        Fetcher.local = { url -> TestSite.read(this, url) }
        // Reads the DevTools script (about 500 KB) while the app starts, not when the first page opens.
        Thread { runCatching { DevtoolsScript.warm(this) } }.start()
    }
}
