package com.inspectionbrowser.app.devtools

import com.inspectionbrowser.app.net.LoggedRequest
import com.inspectionbrowser.app.net.ResourceType
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevtoolsReportTest {
    // What report.js returns for a small page (trimmed from a real run).
    private val page = """{"url":"http://localhost/","title":"Cave map","time":"Thu Nov 12 2026","devtools":true,
        "info":[["User Agent","Mozilla/5.0 test"],["Device","screen\n412 * 915"]],
        "console":"Console: http://localhost/\nThu\n2 messages\n\nhello {a: 1}\n[warn] careful\n",
        "dom":"<!DOCTYPE html>\n<html><head><title>Cave map</title></head><body><h1>Caves</h1></body></html>",
        "network":[{"method":"POST","url":"http://localhost/api/caves","status":200,"type":"application/json","size":19,"time":23,
          "reqHeaders":[["Content-Type","application/json"]],"reqBody":"{\"county\":\"Citrus\"}",
          "resHeaders":[["x-total","2"]],"resBody":"[{\"id\":1}]"}],
        "localStorage":[["token","abc"]],"sessionStorage":[],"cookies":"visible=1",
        "scripts":[{"url":"http://localhost/app.js"},{"inline":"console.log(1)"}],
        "styles":[{"url":"http://localhost/site.css"}],"iframes":["http://localhost/frame"],"images":["http://localhost/logo.png"]}"""

    private val native = NativeDump(
        userAgent = "UA/1", webView = "com.google.android.webview 130", certificate = null,
        cookies = listOf("session" to "secret", "visible" to "1"),
        requests = listOf(LoggedRequest(1, 0, "GET", "http://localhost/", true, false, false, emptyMap(), ResourceType.Document)),
        serverSource = SourceFile("http://localhost/", "<html>as sent</html>"), serverStatus = "200 OK, text/html",
    )

    @Test fun readsWhatThePageReports() {
        val p = DevtoolsReport.parsePage(JsonPrimitive(page).toString())!!
        assertEquals("Cave map", p.title)
        assertEquals("POST", p.network.single().method)
        assertEquals("{\"county\":\"Citrus\"}", p.network.single().requestBody)
        assertEquals(listOf("x-total" to "2"), p.network.single().responseHeaders)
        assertEquals(23L, p.network.single().millis)
        assertEquals(listOf("token" to "abc"), p.localStorage)
        assertEquals(listOf(SourceFile("http://localhost/app.js", null), SourceFile(null, "console.log(1)")), p.scripts)
        assertNull(DevtoolsReport.parsePage("null"))
        assertNull(DevtoolsReport.parsePage(null))
    }

    @Test fun putsEveryTabInTheReport() {
        val p = DevtoolsReport.parsePage(page)!!
        val filled = p.copy(scripts = listOf(SourceFile("http://localhost/app.js", "window.APP=1", "200 OK, 12 bytes"), SourceFile(null, "console.log(1)")))
        val text = DevtoolsReport.format(filled, native, "http://localhost/", "now")
        val order = listOf("INFO", "CONSOLE", "ELEMENTS", "NETWORK", "RESOURCES", "SOURCES").map { text.indexOf("==================== $it") }
        assertTrue("every section, in order: $order", order.all { it >= 0 } && order == order.sorted())
        listOf(
            "User agent sent: UA/1", "Device: screen · 412 * 915", "[warn] careful", "<h1>Caves</h1>",
            "+0 ms  GET http://localhost/  [Doc] ok", "#1 POST http://localhost/api/caves", "{\"county\":\"Citrus\"}", "  x-total: 2",
            "session: secret", "token: abc", "<html>as sent</html>", "--- Script 1: http://localhost/app.js ---",
            "(200 OK, 12 bytes)", "window.APP=1", "--- Script 2: inline ---", "console.log(1)",
            "--- Stylesheet 1: http://localhost/site.css ---",
        ).forEach { assertTrue("missing: $it", text.contains(it)) }
    }

    @Test fun stillReportsWhenThePageDoesNotAnswer() {
        val text = DevtoolsReport.format(null, native, "http://localhost/", "now")
        assertTrue(text.contains("The page didn't answer"))
        assertTrue(text.contains("session: secret"))
        assertTrue(text.contains("<html>as sent</html>"))
    }
}
