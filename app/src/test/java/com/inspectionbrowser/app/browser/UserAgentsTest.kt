package com.inspectionbrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {
    private val webView = "Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007; wv) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36"

    @Test fun chromeOnAndroidDropsTheWebViewMarkers() {
        val ua = UserAgents.resolve(UserAgents.CHROME_ANDROID, webView)
        assertEquals("Mozilla/5.0 (Linux; Android 14; SM-S918B Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36", ua)
        assertFalse(ua.contains("wv"))
    }

    @Test fun desktopAgentsCarryThisChromesVersion() {
        val ua = UserAgents.resolve(UserAgents.DESKTOP, webView)
        assertTrue(ua.contains("X11; Linux x86_64"))
        assertTrue(ua.contains("Chrome/130.0.0.0"))
        assertFalse(ua.contains("Mobile"))
        assertTrue(UserAgents.preset("windows").desktop)
        assertFalse(UserAgents.preset("iphone").desktop)
    }

    @Test fun customAndUnknown() {
        assertEquals("MyBot/1.0", UserAgents.resolve(UserAgents.CUSTOM, webView, "  MyBot/1.0 "))
        assertEquals(webView, UserAgents.resolve(UserAgents.CUSTOM, webView, ""))
        assertEquals(webView, UserAgents.resolve(UserAgents.WEBVIEW, webView))
        assertEquals(UserAgents.resolve(UserAgents.CHROME_ANDROID, webView), UserAgents.resolve("gone", webView))
        assertEquals("130", UserAgents.chromeMajor(webView))
    }
}
