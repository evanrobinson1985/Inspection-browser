package com.inspectionbrowser.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlInputTest {
    private fun go(s: String) = UrlInput.resolve(s)

    @Test fun keepsFullUrlsAndOtherSchemes() {
        assertEquals("https://example.com/a?b=1", go("https://example.com/a?b=1"))
        assertEquals("http://10.0.0.5:8080/", go("  http://10.0.0.5:8080/ "))
        assertEquals("about:blank", go("about:blank"))
        assertEquals("javascript:alert(1)", go("javascript:alert(1)"))
        assertEquals("data:text/html,<b>hi</b>", go("data:text/html,<b>hi</b>"))
    }

    @Test fun addsHttpsToDomains() {
        assertEquals("https://example.com", go("example.com"))
        assertEquals("https://sub.example.co.uk/path?q=1#x", go("sub.example.co.uk/path?q=1#x"))
        assertEquals("https://example.com:8443/x", go("example.com:8443/x"))
    }

    @Test fun localDevServersGetPlainHttp() {
        assertEquals("http://localhost:3000", go("localhost:3000"))
        assertEquals("http://localhost", go("localhost"))
        assertEquals("http://192.168.1.20/admin", go("192.168.1.20/admin"))
        assertEquals("http://printer.local", go("printer.local"))
        assertEquals("http://[::1]:8080/", go("[::1]:8080/"))
    }

    @Test fun searchesEverythingElse() {
        assertEquals("https://www.google.com/search?q=how+to+center+a+div", go("how to center a div"))
        assertEquals("https://www.google.com/search?q=kotlin", go("kotlin"))
        assertEquals("https://www.google.com/search?q=1.5", go("1.5"))
        assertEquals("https://www.google.com/search?q=a%26b%3Dc", go("a&b=c"))
        assertEquals("https://duckduckgo.com/?q=web+view", UrlInput.resolve("web view", UrlInput.engine("duckduckgo")))
        assertEquals("", go("   "))
    }

    @Test fun showsAddressesCompactly() {
        assertEquals("example.com/page", UrlInput.display("https://www.example.com/page"))
        assertEquals("localhost:3000", UrlInput.display("http://localhost:3000/"))
    }
}
