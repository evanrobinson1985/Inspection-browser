package com.inspectionbrowser.app.devtools

import com.inspectionbrowser.app.net.LoggedRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A fetch / XHR request as Eruda's Network tab recorded it, with headers and bodies. */
data class NetEntry(
    val method: String,
    val url: String,
    val status: String,
    val type: String,
    val size: Long?,
    val millis: Long?,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: String?,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: String?,
)

/** A script or stylesheet: inline code, or a file with its contents (or why they couldn't be fetched). */
data class SourceFile(val url: String?, val content: String?, val note: String? = null)

/** What the page itself reported (see assets/devtools/report.js). */
data class PageDump(
    val url: String,
    val title: String,
    val time: String,
    val devtools: Boolean,
    val info: List<Pair<String, String>>,
    val console: String?,
    val dom: String?,
    val network: List<NetEntry>,
    val localStorage: List<Pair<String, String>>,
    val sessionStorage: List<Pair<String, String>>,
    val scriptCookies: String,
    val scripts: List<SourceFile>,
    val styles: List<SourceFile>,
    val iframes: List<String>,
    val images: List<String>,
)

/** What the app adds: what page scripts can't see. */
data class NativeDump(
    val userAgent: String,
    val webView: String?,
    val certificate: String?,
    /** All cookies for the page, HttpOnly ones included. */
    val cookies: List<Pair<String, String>>,
    val requests: List<LoggedRequest>,
    /** The page's HTML as the server sent it, with its status line. */
    val serverSource: SourceFile?,
    val serverStatus: String?,
)

/** One report with everything the DevTools tabs show, as plain text. */
object DevtoolsReport {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.bool(k: String) = str(k) == "true"
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()

    private fun pairs(list: List<JsonElement>): List<Pair<String, String>> = list.mapNotNull { e ->
        val a = e as? JsonArray ?: return@mapNotNull null
        val k = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
        k to ((a.getOrNull(1) as? JsonPrimitive)?.content ?: "")
    }

    private fun source(e: JsonElement): SourceFile? {
        val o = e as? JsonObject ?: return null
        val url = o.str("url")
        val inline = o.str("inline")
        return if (url != null) SourceFile(url, null) else SourceFile(null, inline ?: "")
    }

    /** Reads what report.js returned (a JSON string, itself JSON-encoded by evaluateJavascript). */
    fun parsePage(result: String?): PageDump? {
        val outer = runCatching { json.parseToJsonElement(result ?: return null) }.getOrNull() ?: return null
        val text = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content
        val o = (if (text != null) runCatching { json.parseToJsonElement(text) }.getOrNull() else outer) as? JsonObject ?: return null
        return PageDump(
            url = o.str("url").orEmpty(),
            title = o.str("title").orEmpty(),
            time = o.str("time").orEmpty(),
            devtools = o.bool("devtools"),
            info = pairs(o.arr("info")),
            console = o.str("console"),
            dom = o.str("dom"),
            network = o.arr("network").mapNotNull { e ->
                val q = e as? JsonObject ?: return@mapNotNull null
                NetEntry(
                    method = q.str("method") ?: "GET", url = q.str("url").orEmpty(), status = q.str("status") ?: "",
                    type = q.str("type").orEmpty(), size = (q["size"] as? JsonPrimitive)?.longOrNull,
                    millis = (q["time"] as? JsonPrimitive)?.longOrNull,
                    requestHeaders = pairs(q.arr("reqHeaders")), requestBody = q.str("reqBody"),
                    responseHeaders = pairs(q.arr("resHeaders")), responseBody = q.str("resBody"),
                )
            },
            localStorage = pairs(o.arr("localStorage")),
            sessionStorage = pairs(o.arr("sessionStorage")),
            scriptCookies = o.str("cookies").orEmpty(),
            scripts = o.arr("scripts").mapNotNull(::source),
            styles = o.arr("styles").mapNotNull(::source),
            iframes = o.arr("iframes").mapNotNull { (it as? JsonPrimitive)?.content },
            images = o.arr("images").mapNotNull { (it as? JsonPrimitive)?.content },
        )
    }

    private fun StringBuilder.section(title: String) {
        if (isNotEmpty()) append('\n')
        append("==================== ").append(title).append(" ====================\n")
    }

    private fun StringBuilder.sub(title: String) { append("\n--- ").append(title).append(" ---\n") }

    private fun StringBuilder.pairsBlock(list: List<Pair<String, String>>, empty: String = "(none)") {
        if (list.isEmpty()) append(empty).append('\n')
        list.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
    }

    private fun StringBuilder.files(kind: String, list: List<SourceFile>) {
        if (list.isEmpty()) { append("(no ").append(kind).append(")\n"); return }
        list.forEachIndexed { i, f ->
            sub("$kind ${i + 1}: " + (f.url ?: "inline"))
            f.note?.let { append("(").append(it).append(")\n") }
            f.content?.let { append(it).append('\n') }
        }
    }

    /** The whole report: Info, Console, Elements, Network, Resources, Sources, in that order. */
    fun format(page: PageDump?, native: NativeDump, pageUrl: String, time: String): String = buildString {
        append("Inspection Browser: everything for ").append(page?.url?.ifEmpty { null } ?: pageUrl).append('\n')
        append(page?.time?.ifEmpty { null } ?: time).append('\n')
        if (page == null) append("(The page didn't answer, so only what the app itself saw is included. Is JavaScript on?)\n")

        section("INFO")
        page?.title?.let { append("Title: ").append(it).append('\n') }
        append("User agent sent: ").append(native.userAgent).append('\n')
        native.webView?.let { append("WebView: ").append(it).append('\n') }
        append("Connection: ").append(native.certificate ?: if ((page?.url ?: pageUrl).startsWith("https")) "HTTPS" else "not encrypted").append('\n')
        page?.info?.forEach { (k, v) -> append(k).append(": ").append(v.replace("\n", " · ")).append('\n') }

        section("CONSOLE")
        append(page?.console ?: if (page?.devtools == false) "(DevTools aren't on for this page, so the console wasn't recorded)\n" else "(no console)\n")

        section("ELEMENTS (live DOM)")
        append(page?.dom ?: "(not available)").append('\n')

        section("NETWORK")
        sub("Every request the page made (${native.requests.size})")
        if (native.requests.isEmpty()) append("(none recorded; reload the page to record them)\n")
        native.requests.forEach { r ->
            val status = when { r.error != null -> "FAILED ${r.error}"; r.status != null -> "${r.status} ${r.reason.orEmpty()}".trim(); else -> "ok" }
            append(r.method).append(' ').append(r.url).append("  [").append(r.type.label).append("] ").append(status).append('\n')
        }
        sub("Fetch / XHR details (${page?.network?.size ?: 0})")
        val net = page?.network.orEmpty()
        if (net.isEmpty()) append("(none)\n")
        net.forEachIndexed { i, q ->
            append('\n').append("#").append(i + 1).append(' ').append(q.method).append(' ').append(q.url).append('\n')
            append("Status: ").append(q.status).append("   Type: ").append(q.type)
            q.size?.let { append("   Size: ").append(it).append(" B") }
            q.millis?.let { append("   Time: ").append(it).append(" ms") }
            append('\n')
            append("Request headers:\n"); q.requestHeaders.forEach { (k, v) -> append("  ").append(k).append(": ").append(v).append('\n') }
            q.requestBody?.let { append("Request body:\n").append(it).append('\n') }
            append("Response headers:\n"); q.responseHeaders.forEach { (k, v) -> append("  ").append(k).append(": ").append(v).append('\n') }
            q.responseBody?.let { append("Response body:\n").append(it).append('\n') }
        }

        section("RESOURCES")
        sub("Cookies (all, HttpOnly included)")
        pairsBlock(native.cookies)
        sub("Cookies scripts can read (document.cookie)")
        append(page?.scriptCookies?.ifEmpty { null } ?: "(none)").append('\n')
        sub("Local storage")
        pairsBlock(page?.localStorage.orEmpty())
        sub("Session storage")
        pairsBlock(page?.sessionStorage.orEmpty())
        sub("Scripts")
        page?.scripts.orEmpty().forEach { append(it.url ?: "(inline)").append('\n') }
        sub("Stylesheets")
        page?.styles.orEmpty().forEach { append(it.url ?: "(inline)").append('\n') }
        sub("Iframes")
        append(page?.iframes?.joinToString("\n")?.ifEmpty { null } ?: "(none)").append('\n')
        sub("Images")
        append(page?.images?.distinct()?.joinToString("\n")?.ifEmpty { null } ?: "(none)").append('\n')

        section("SOURCES")
        sub("Page HTML as the server sent it" + (native.serverStatus?.let { " ($it)" } ?: ""))
        native.serverSource?.let { s -> s.note?.let { append("(").append(it).append(")\n") }; s.content?.let { append(it).append('\n') } }
            ?: append("(not fetched)\n")
        files("Script", page?.scripts.orEmpty())
        files("Stylesheet", page?.styles.orEmpty())
    }
}
