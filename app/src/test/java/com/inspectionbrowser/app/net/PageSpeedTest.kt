package com.inspectionbrowser.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageSpeedTest {
    private val page = """{"ttfb":420,"dcl":1500,"load":3500,"docTransfer":20000,"docDecoded":80000,"fcp":1200,"lcp":2900,"cls":0.31,
        "res":[{"u":"https://a.com/app.js","t":"script","x":300000,"d":900000,"ms":800},
               {"u":"https://a.com/hero.jpg","t":"img","x":500000,"d":500000,"ms":1200},
               {"u":"https://cdn.other.com/font.woff2","t":"css","x":0,"d":0,"ms":90},
               {"u":"https://a.com/s.css","t":"link","x":10000,"d":40000,"ms":60}],
        "dom":812,"heap":12345678}"""

    @Test fun ratesAgainstWebVitalsThresholds() {
        val r = PageSpeed.build(Json.parseToJsonElement(page) as kotlinx.serialization.json.JsonObject)
        val m = r.metrics.associateBy { it.name }
        assertEquals(Rating.Good, m["Server response (TTFB)"]!!.rating)
        assertEquals(Rating.Good, m["First contentful paint"]!!.rating)
        assertEquals(Rating.NeedsWork, m["Largest contentful paint"]!!.rating)
        assertEquals(Rating.Poor, m["Layout shift (CLS)"]!!.rating)
        assertEquals(Rating.NeedsWork, m["Fully loaded"]!!.rating)
        assertEquals(Rating.Info, m["DOM ready"]!!.rating)
    }

    @Test fun totalsSizesAndFindsTheHeaviestFiles() {
        val r = PageSpeed.build(Json.parseToJsonElement(page) as kotlinx.serialization.json.JsonObject)
        assertEquals(830_000L, r.totalTransfer)
        assertEquals(5, r.requestCount)
        assertEquals(listOf("img", "script", "link", "css"), r.byType.map { it.type })
        assertEquals("https://a.com/app.js", r.largest.first().url)
        assertEquals("https://a.com/hero.jpg", r.slowest.first().url)
        assertEquals(1, r.unknownSizes)
        assertEquals(812, r.domElements)
    }

    @Test fun readsWhatEvaluateJavascriptHandsBack() {
        // evaluateJavascript wraps the page's JSON string in another layer of JSON.
        val wrapped = JsonPrimitive(page).toString()
        assertEquals(830_000L, PageSpeed.parse(wrapped)!!.totalTransfer)
        assertNull(PageSpeed.parse("null"))
        assertNull(PageSpeed.parse(null))
        assertNull(PageSpeed.parse("not json"))
    }

    @Test fun missingTimingsAreJustInformation() {
        val r = PageSpeed.build(Json.parseToJsonElement("""{"res":[]}""") as kotlinx.serialization.json.JsonObject)
        assertEquals(Rating.Info, r.metrics.first { it.name.startsWith("Largest") }.rating)
        assertEquals(1, r.requestCount)
    }
}
