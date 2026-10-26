package com.inspectionbrowser.app.data

import android.content.Context
import android.content.SharedPreferences
import com.inspectionbrowser.app.browser.UserAgents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One saved setting, readable as a flow so the browser and screens follow changes. */
class Pref<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    default: T,
    read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit,
) {
    private val _flow = MutableStateFlow(prefs.read(key, default))
    val flow: StateFlow<T> = _flow.asStateFlow()
    var value: T
        get() = _flow.value
        set(v) { prefs.edit().apply { write(key, v) }.apply(); _flow.value = v }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = Pref(prefs, key, default, { k, d -> getBoolean(k, d) }, { k, v -> putBoolean(k, v) })
    private fun string(key: String, default: String) = Pref(prefs, key, default, { k, d -> getString(k, d) ?: d }, { k, v -> putString(k, v) })
    private fun int(key: String, default: Int) = Pref(prefs, key, default, { k, d -> getInt(k, d) }, { k, v -> putInt(k, v) })

    val homePage = string("homePage", "https://www.google.com/")
    val searchEngine = string("searchEngine", "google")

    val userAgent = string("userAgent", UserAgents.CHROME_ANDROID)
    val customUserAgent = string("customUserAgent", "")
    /** "Desktop site": a desktop agent whatever [userAgent] is. */
    val desktopMode = bool("desktopMode", false)

    val javaScript = bool("javaScript", true)
    val disableCache = bool("disableCache", false)
    val thirdPartyCookies = bool("thirdPartyCookies", true)
    val darkPages = bool("darkPages", false)
    val textZoom = int("textZoom", 100)

    /** Eruda loaded into every page before its scripts. */
    val devtools = bool("devtools", true)
    /** Eruda's own floating gear button, besides the toolbar button. */
    val devtoolsButton = bool("devtoolsButton", false)
    /** How much of the screen the DevTools panel takes, in percent. */
    val devtoolsSize = int("devtoolsSize", 55)
    /** chrome://inspect from a computer over USB. */
    val remoteDebugging = bool("remoteDebugging", true)
    /** Keep the request log across page loads (Chrome's "Preserve log"). */
    val preserveLog = bool("preserveLog", false)
}
