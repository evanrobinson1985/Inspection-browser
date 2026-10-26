package com.inspectionbrowser.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestsTest {
    @Test fun classifiesByFetchDestThenAcceptThenExtension() {
        assertEquals(ResourceType.Document, Requests.classify("https://a.com/", emptyMap(), mainFrame = true))
        assertEquals(ResourceType.Script, Requests.classify("https://a.com/x", mapOf("Sec-Fetch-Dest" to "script"), false))
        assertEquals(ResourceType.Image, Requests.classify("https://a.com/pic", mapOf("Accept" to "image/avif,image/webp,*/*"), false))
        assertEquals(ResourceType.Style, Requests.classify("https://a.com/s", mapOf("Accept" to "text/css,*/*;q=0.1"), false))
        assertEquals(ResourceType.Document, Requests.classify("https://a.com/frame", mapOf("Accept" to "text/html,application/xhtml+xml"), false))
        assertEquals(ResourceType.Script, Requests.classify("https://cdn.com/app.min.js?v=3", mapOf("Accept" to "*/*"), false))
        assertEquals(ResourceType.Font, Requests.classify("https://cdn.com/f.woff2#x", emptyMap(), false))
        assertEquals(ResourceType.Fetch, Requests.classify("https://api.com/v1/items.json", emptyMap(), false))
        assertEquals(ResourceType.Fetch, Requests.classify("https://api.com/v1/items", mapOf("X-Requested-With" to "XMLHttpRequest"), false))
        assertEquals(ResourceType.Media, Requests.classify("https://v.com/seg.ts", mapOf("Accept" to "application/vnd.apple.mpegurl"), false))
        assertEquals(ResourceType.Other, Requests.classify("https://a.com/beacon", mapOf("Accept" to "*/*"), false))
    }

    @Test fun extensionIgnoresQueryAndHost() {
        assertEquals("png", Requests.extension("https://a.com/img/x.PNG?w=2"))
        assertEquals("", Requests.extension("https://a.com.au/"))
        assertEquals("", Requests.extension("https://a.com/path/noext"))
    }

    @Test fun buildsCurlCommands() {
        val r = LoggedRequest(1, 0, "POST", "https://a.com/it's", false, false, false, mapOf("Accept" to "*/*"), ResourceType.Fetch)
        assertEquals(
            "curl 'https://a.com/it'\\''s' -X POST \\\n  -H 'Accept: */*' \\\n  -H 'User-Agent: UA' \\\n  -H 'Cookie: a=1' \\\n  --compressed",
            Requests.curl(r, cookies = "a=1", userAgent = "UA"),
        )
    }

    @Test fun parsesCookieHeaders() {
        assertEquals(listOf("a" to "1", "b" to "x=y", "" to "flag"), Requests.parseCookies("a=1; b=x=y;flag"))
        assertEquals(emptyList<Pair<String, String>>(), Requests.parseCookies(null))
    }

    @Test fun hostsAndPaths() {
        assertEquals("a.com:8080", Requests.host("https://user@a.com:8080/x?y"))
        assertEquals("/x?y=1", Requests.pathOf("https://a.com/x?y=1"))
        assertEquals("/", Requests.pathOf("https://a.com"))
        assertEquals("?q", Requests.pathOf("https://a.com?q"))
    }
}
