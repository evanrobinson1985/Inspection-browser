package com.inspectionbrowser.app.net

/** What kind of resource a request is for, as the request log groups them. */
enum class ResourceType(val label: String) {
    Document("Doc"), Script("JS"), Style("CSS"), Image("Img"), Font("Font"), Media("Media"), Fetch("Fetch/XHR"), Other("Other")
}

/** One request the page made, as WebView reported it. */
data class LoggedRequest(
    val id: Long,
    val time: Long,
    val method: String,
    val url: String,
    val mainFrame: Boolean,
    val hasGesture: Boolean,
    val redirect: Boolean,
    val headers: Map<String, String>,
    val type: ResourceType,
    /** Only known for failures: WebView reports the status of responses of 400 and up. */
    val status: Int? = null,
    val reason: String? = null,
    /** A network-level failure (DNS, TLS, blocked…). */
    val error: String? = null,
)

object Requests {
    private val byExtension = mapOf(
        ResourceType.Script to setOf("js", "mjs", "cjs", "jsx", "ts", "wasm"),
        ResourceType.Style to setOf("css"),
        ResourceType.Image to setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp", "apng"),
        ResourceType.Font to setOf("woff", "woff2", "ttf", "otf", "eot"),
        ResourceType.Media to setOf("mp4", "webm", "mp3", "m4a", "ogg", "wav", "m3u8", "mpd", "mov", "aac", "flac"),
        ResourceType.Fetch to setOf("json", "xml"),
        ResourceType.Document to setOf("html", "htm", "php", "asp", "aspx"),
    )

    fun extension(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        val name = path.substringAfterLast('/')
        return if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
    }

    /** Guesses the resource type from the headers WebView passes on and the URL. */
    fun classify(url: String, headers: Map<String, String>, mainFrame: Boolean): ResourceType {
        if (mainFrame) return ResourceType.Document
        val h = headers.mapKeys { it.key.lowercase() }
        when (h["sec-fetch-dest"]) {
            "document", "iframe", "frame" -> return ResourceType.Document
            "script", "worker", "sharedworker", "serviceworker" -> return ResourceType.Script
            "style" -> return ResourceType.Style
            "image" -> return ResourceType.Image
            "font" -> return ResourceType.Font
            "audio", "video", "track" -> return ResourceType.Media
            "empty" -> return ResourceType.Fetch
        }
        if (h["x-requested-with"].equals("XMLHttpRequest", ignoreCase = true)) return ResourceType.Fetch
        val accept = h["accept"].orEmpty()
        when {
            accept.startsWith("text/html") -> return ResourceType.Document
            accept.startsWith("text/css") -> return ResourceType.Style
            accept.startsWith("image/") -> return ResourceType.Image
            accept.startsWith("video/") || accept.startsWith("audio/") -> return ResourceType.Media
            accept.startsWith("application/json") -> return ResourceType.Fetch
        }
        val ext = extension(url)
        if (ext == "ts") return if (accept.contains("mpegurl")) ResourceType.Media else ResourceType.Script
        return byExtension.entries.firstOrNull { ext in it.value }?.key ?: ResourceType.Other
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** The request as a cURL command, to replay it from a computer. */
    fun curl(r: LoggedRequest, cookies: String? = null, userAgent: String? = null): String = buildString {
        append("curl ").append(quote(r.url))
        if (r.method != "GET") append(" -X ").append(r.method)
        val headers = r.headers.toMutableMap()
        if (userAgent != null && headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = userAgent
        if (!cookies.isNullOrBlank() && headers.keys.none { it.equals("Cookie", true) }) headers["Cookie"] = cookies
        headers.forEach { (k, v) -> append(" \\\n  -H ").append(quote("$k: $v")) }
        append(" \\\n  --compressed")
    }

    /** The cookies in a Cookie header ("a=1; b=2"), in order. */
    fun parseCookies(header: String?): List<Pair<String, String>> =
        header.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.map {
            val eq = it.indexOf('=')
            if (eq < 0) "" to it else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
        }

    fun host(url: String) = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringAfterLast('@')

    /** The URL without its scheme and host, for compact rows. */
    fun pathOf(url: String): String {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return url
        val i = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (i < 0) "/" else rest.substring(i)
    }
}
