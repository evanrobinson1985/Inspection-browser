package com.inspectionbrowser.app.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.net.URLConnection

/**
 * The test website bundled in the app (assets/testsite): a page with something to try for
 * every DevTools tab and browser feature, served from https://appassets.androidplatform.net so
 * it behaves like a real site, works offline and needs no hosting.
 */
object TestSite {
    const val URL = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/testsite/index.html"

    fun loader(context: Context): WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    /** The asset, or a real 404 for a missing one (so the site can test failed requests). */
    fun serve(loader: WebViewAssetLoader, url: Uri): WebResourceResponse =
        loader.shouldInterceptRequest(url) ?: WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("Not found: ${url.path}".toByteArray()),
        )

    /** Reads a test-site file for re-fetches (View source, Share everything), as the WebView would get it. */
    fun read(context: Context, url: String): Pair<String?, ByteArray>? {
        val uri = Uri.parse(url)
        if (uri.host != WebViewAssetLoader.DEFAULT_DOMAIN) return null
        val path = uri.path?.removePrefix("/assets/") ?: return null
        val bytes = runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull() ?: return null
        return URLConnection.guessContentTypeFromName(path) to bytes
    }
}
