package com.inspectionbrowser.app.browser

/** The user agents the browser can pretend to be. */
object UserAgents {
    data class Preset(val id: String, val label: String, val desktop: Boolean = false)

    const val CHROME_ANDROID = "chrome"
    const val WEBVIEW = "webview"
    const val DESKTOP = "desktop"
    const val CUSTOM = "custom"

    val presets = listOf(
        Preset(CHROME_ANDROID, "Chrome on Android (this phone)"),
        Preset(WEBVIEW, "Android WebView (unchanged)"),
        Preset(DESKTOP, "Chrome on Linux (desktop)", desktop = true),
        Preset("windows", "Chrome on Windows", desktop = true),
        Preset("mac", "Safari on Mac", desktop = true),
        Preset("iphone", "Safari on iPhone"),
        Preset("ipad", "Safari on iPad", desktop = true),
        Preset("firefox", "Firefox on Android"),
        Preset(CUSTOM, "Custom"),
    )

    fun preset(id: String) = presets.firstOrNull { it.id == id } ?: presets.first()

    /** Chrome's major version in a user agent, e.g. "130" (what reduced desktop agents carry). */
    fun chromeMajor(ua: String): String = Regex("""Chrome/(\d+)""").find(ua)?.groupValues?.get(1) ?: "130"

    /** The user agent for preset [id], given WebView's own [webViewUa]. */
    fun resolve(id: String, webViewUa: String, custom: String = ""): String {
        val major = chromeMajor(webViewUa)
        return when (id) {
            WEBVIEW -> webViewUa
            // WebView marks itself with "; wv" and "Version/4.0"; some sites (Google sign-in) refuse those.
            CHROME_ANDROID -> webViewUa.replace("; wv)", ")").replace(Regex("""Version/[\d.]+ """), "")
            DESKTOP -> "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
            "windows" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
            "mac" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
            "iphone" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            "ipad" -> "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            "firefox" -> "Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0"
            CUSTOM -> custom.trim().ifEmpty { webViewUa }
            else -> resolve(CHROME_ANDROID, webViewUa)
        }
    }
}
