package com.inspectionbrowser.app.net

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A response fetched again outside WebView, to see what the server really sends. */
data class FetchResult(
    val url: String,
    val finalUrl: String,
    val code: Int,
    val message: String,
    val protocol: String,
    val requestHeaders: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val contentType: String?,
    /** Text bodies, up to [Fetcher.MAX_TEXT]; null for images, video and other binary. */
    val body: String?,
    val bytes: Long,
    val truncated: Boolean,
    val millis: Long,
    val redirects: List<String>,
)

/** Fetches URLs with the page's cookies and user agent, as WebView would. */
object Fetcher {
    const val MAX_TEXT = 4 * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

    private fun isText(ct: String?): Boolean {
        val c = ct.orEmpty().lowercase()
        if (c.isEmpty()) return true
        return c.startsWith("text/") || listOf("json", "javascript", "xml", "html", "css", "svg", "x-www-form-urlencoded", "graphql", "csv", "yaml")
            .any { c.contains(it) }
    }

    suspend fun fetch(
        url: String,
        userAgent: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
    ): FetchResult = withContext(Dispatchers.IO) {
        val sent = LinkedHashMap<String, String>()
        // OkHttp unzips responses itself only when it asked for the compression.
        headers.forEach { (k, v) -> if (!k.equals("Cookie", true) && !k.equals("Accept-Encoding", true)) sent[k] = v }
        if (sent.keys.none { it.equals("User-Agent", true) }) sent["User-Agent"] = userAgent
        if (sent.keys.none { it.equals("Accept", true) }) sent["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { sent["Cookie"] = it }

        // A body can't be replayed (WebView doesn't hand it over), so others go as GET/HEAD.
        val verb = if (method.equals("HEAD", true)) "HEAD" else "GET"
        val request = Request.Builder().url(url).headers(sent.toHeaders()).method(verb, null).build()
        val started = System.currentTimeMillis()
        http.newCall(request).execute().use { r ->
            val redirects = generateSequence(r.priorResponse) { it.priorResponse }.map { "${it.code} ${it.request.url}" }.toList().reversed()
            val ct = r.header("Content-Type")
            val body = r.body
            var bytes = 0L
            var truncated = false
            val text = if (body != null && isText(ct)) {
                val source = body.source()
                val buffer = okio.Buffer()
                while (buffer.size < MAX_TEXT) { if (source.read(buffer, 64 * 1024L) == -1L) break }
                truncated = !source.exhausted()
                bytes = buffer.size
                val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                buffer.readString(charset)
            } else {
                body?.source()?.let { s ->
                    val b = okio.Buffer()
                    while (true) { val n = s.read(b, 64 * 1024L); if (n == -1L) break; bytes += n; b.clear() }
                }
                null
            }
            FetchResult(
                url = url, finalUrl = r.request.url.toString(), code = r.code, message = r.message,
                protocol = r.protocol.toString(), requestHeaders = sent.toList(),
                headers = r.headers.map { it.first to it.second }, contentType = ct, body = text, bytes = bytes,
                truncated = truncated, millis = System.currentTimeMillis() - started, redirects = redirects,
            )
        }
    }
}
