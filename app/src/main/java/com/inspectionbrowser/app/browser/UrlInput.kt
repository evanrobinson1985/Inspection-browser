package com.inspectionbrowser.app.browser

import java.net.URLEncoder

/** A search engine: [template] has %s where the encoded query goes. */
data class SearchEngine(val id: String, val label: String, val template: String)

/** Turns what was typed in the address bar into a URL to load: an address, or a search. */
object UrlInput {
    val engines = listOf(
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s"),
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
    )

    fun engine(id: String) = engines.firstOrNull { it.id == id } ?: engines.first()

    private val schemes = setOf("http", "https", "about", "data", "javascript", "file", "blob", "view-source", "chrome", "content", "intent", "mailto", "tel", "sms", "geo", "market")
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val label = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?$""", RegexOption.IGNORE_CASE)
    // Names only ever found on a local network get plain http, as dev servers rarely have TLS.
    private val localSuffixes = listOf(".local", ".lan", ".home", ".internal", ".localhost", ".home.arpa", ".test")

    fun resolve(text: String, engine: SearchEngine = engines.first()): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val scheme = t.substringBefore(':', "").lowercase()
        if (scheme in schemes && t.length > scheme.length + 1) return t
        if (t.any { it.isWhitespace() }) return search(t, engine)

        val authority = t.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostOf(authority) ?: return search(t, engine)
        return (if (isLocal(host)) "http://" else "https://") + t
    }

    fun search(query: String, engine: SearchEngine = engines.first()): String =
        engine.template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))

    /** The host in "host", "host:port", "[v6]:port" or "user@host", if it really looks like one. */
    private fun hostOf(authority: String): String? {
        val a = authority.substringAfterLast('@')
        if (a.startsWith("[")) return a.substringBefore(']').takeIf { a.contains(']') }?.plus("]")
        val host = a.substringBefore(':').lowercase()
        val port = a.substringAfter(':', "")
        if (a.contains(':') && (port.isEmpty() || port.any { !it.isDigit() })) return null
        if (host == "localhost" || ipv4.matches(host)) return host
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { !label.matches(it) }) return null
        val tld = labels.last()
        if (tld.length < 2 || tld.all { it.isDigit() }) return null
        return host
    }

    private fun isLocal(host: String) = host == "localhost" || host.startsWith("[") || ipv4.matches(host) ||
        localSuffixes.any { host.endsWith(it) }

    /** What the address bar shows for [url] when it isn't being edited. */
    fun display(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")
}
