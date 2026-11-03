package com.inspectionbrowser.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

enum class Rating { Good, NeedsWork, Poor, Info }

/** One timing or size, with how it rates against the usual web-vitals thresholds. */
data class Metric(val name: String, val value: Double?, val unit: String, val rating: Rating, val hint: String)

/** One file the page loaded, from the Resource Timing API. */
data class LoadedFile(val url: String, val type: String, val transfer: Long, val decoded: Long, val millis: Double)

/** Files of one kind (script, img, css …): how many and how much was downloaded. */
data class TypeTotal(val type: String, val count: Int, val transfer: Long)

data class SpeedReport(
    val metrics: List<Metric>,
    val totalTransfer: Long,
    val requestCount: Int,
    val byType: List<TypeTotal>,
    val largest: List<LoadedFile>,
    val slowest: List<LoadedFile>,
    /** Cross-origin files whose size the server doesn't share (no Timing-Allow-Origin). */
    val unknownSizes: Int,
    val domElements: Int?,
    val jsHeap: Long?,
)

/** A Lighthouse-style speed summary from the page's own performance entries. */
object PageSpeed {
    /** Starts LCP and layout-shift observers; [COLLECT] reads them a moment later. */
    const val OBSERVE = """(function(){try{window.__ibPerf={lcp:null,cls:0};
new PerformanceObserver(function(l){var e=l.getEntries();if(e.length)__ibPerf.lcp=e[e.length-1].startTime;}).observe({type:'largest-contentful-paint',buffered:true});
new PerformanceObserver(function(l){l.getEntries().forEach(function(x){if(!x.hadRecentInput)__ibPerf.cls+=x.value;});}).observe({type:'layout-shift',buffered:true});
}catch(e){}return 1;})()"""

    const val COLLECT = """(function(){var p=performance,n=(p.getEntriesByType('navigation')[0]||{}),o={};
p.getEntriesByType('paint').forEach(function(e){o[e.name]=e.startTime;});
var r=p.getEntriesByType('resource').slice(0,2000).map(function(e){return{u:e.name,t:e.initiatorType,x:e.transferSize||0,d:e.decodedBodySize||0,ms:e.duration};});
return JSON.stringify({ttfb:n.responseStart,dcl:n.domContentLoadedEventEnd,load:n.loadEventEnd,docTransfer:n.transferSize||0,docDecoded:n.decodedBodySize||0,
fcp:o['first-contentful-paint'],lcp:window.__ibPerf?__ibPerf.lcp:null,cls:window.__ibPerf?__ibPerf.cls:null,res:r,
dom:document.getElementsByTagName('*').length,heap:p.memory?p.memory.usedJSHeapSize:null});})()"""

    fun rate(value: Double?, good: Double, poor: Double): Rating = when {
        value == null -> Rating.Info
        value <= good -> Rating.Good
        value <= poor -> Rating.NeedsWork
        else -> Rating.Poor
    }

    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 || k == "cls" }

    /** Reads what [COLLECT] returned (a JSON string, itself JSON-encoded by evaluateJavascript). */
    fun parse(result: String?): SpeedReport? {
        val outer = runCatching { Json.parseToJsonElement(result ?: return null) }.getOrNull() ?: return null
        val text = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content
        val o = (if (text != null) runCatching { Json.parseToJsonElement(text) }.getOrNull() else outer) as? JsonObject ?: return null
        return build(o)
    }

    fun build(o: JsonObject): SpeedReport {
        val files = (o["res"] as? JsonArray).orEmpty().mapNotNull { it.toFile() }
        val docTransfer = o.num("docTransfer")?.toLong() ?: 0L
        val total = docTransfer + files.sumOf { it.transfer }
        val count = files.size + 1

        val metrics = listOf(
            Metric("Server response (TTFB)", o.num("ttfb"), "ms", rate(o.num("ttfb"), 800.0, 1800.0), "Time until the first byte of the page arrived"),
            Metric("First contentful paint", o.num("fcp"), "ms", rate(o.num("fcp"), 1800.0, 3000.0), "When the first text or image showed"),
            Metric("Largest contentful paint", o.num("lcp"), "ms", rate(o.num("lcp"), 2500.0, 4000.0), "When the biggest text or image showed"),
            Metric("Layout shift (CLS)", o.num("cls"), "", rate(o.num("cls"), 0.1, 0.25), "How much content jumped around while loading"),
            Metric("DOM ready", o.num("dcl"), "ms", Rating.Info, "DOMContentLoaded finished"),
            Metric("Fully loaded", o.num("load"), "ms", rate(o.num("load"), 3000.0, 6000.0), "The load event finished"),
            Metric("Page weight", total.toDouble(), "bytes", rate(total.toDouble(), 1_600_000.0, 4_000_000.0), "Downloaded, as far as the browser can see"),
            Metric("Requests", count.toDouble(), "", rate(count.toDouble(), 50.0, 120.0), "The page plus every file it loaded"),
        )
        val byType = files.groupBy { it.type }.map { (t, list) -> TypeTotal(t, list.size, list.sumOf { it.transfer }) }
            .sortedWith(compareByDescending<TypeTotal> { it.transfer }.thenByDescending { it.count })
        return SpeedReport(
            metrics = metrics,
            totalTransfer = total,
            requestCount = count,
            byType = byType,
            largest = files.filter { it.transfer > 0 || it.decoded > 0 }.sortedByDescending { maxOf(it.transfer, it.decoded) }.take(10),
            slowest = files.sortedByDescending { it.millis }.take(10),
            unknownSizes = files.count { it.transfer == 0L && it.decoded == 0L },
            domElements = o.num("dom")?.toInt(),
            jsHeap = o.num("heap")?.toLong(),
        )
    }

    private fun JsonElement.toFile(): LoadedFile? {
        val f = this as? JsonObject ?: return null
        val url = (f["u"] as? JsonPrimitive)?.content ?: return null
        return LoadedFile(
            url = url,
            type = (f["t"] as? JsonPrimitive)?.content?.ifBlank { null } ?: "other",
            transfer = (f["x"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L,
            decoded = (f["d"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0L,
            millis = (f["ms"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        )
    }
}
