package com.inspectionbrowser.app

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.ui.BrowserScreen
import com.inspectionbrowser.app.ui.BrowserTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var browser: BrowserController

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        browser.onFileChosen(it.resultCode, it.data)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        browser.onPermissionsResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BrowserApp
        browser = BrowserController(
            this, app.settings, app.library,
            launchFileChooser = { fileChooser.launch(it) },
            askPermissions = { permissions.launch(it) },
        )
        val restored = savedInstanceState?.getBundle(WEB_STATE)?.let { browser.restoreState(it) } ?: false
        if (!restored) {
            val start = linkFrom(intent)
            if (start != null) browser.load(start) else browser.goHome()
        }
        setContent {
            BrowserTheme {
                BrowserScreen(browser, app.updater, onExit = { moveTaskToBack(true) })
            }
        }
        lifecycleScope.launch { app.updater.checkInBackground() }
    }

    /** A link or search text handed over by another app. */
    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
        else -> null
    }?.takeIf { it.isNotBlank() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let(browser::load)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(WEB_STATE, Bundle().also(browser::saveState))
    }

    override fun onResume() { super.onResume(); browser.onResume() }
    override fun onPause() { browser.onPause(); super.onPause() }
    override fun onDestroy() { browser.destroy(); super.onDestroy() }

    private companion object { const val WEB_STATE = "web" }
}
