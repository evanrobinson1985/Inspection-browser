package com.inspectionbrowser.app.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.inspectionbrowser.app.data.Library
import com.inspectionbrowser.app.data.Settings
import com.inspectionbrowser.app.devtools.DevtoolsScript
import com.inspectionbrowser.app.net.LoggedRequest
import com.inspectionbrowser.app.net.Requests
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/** The requests WebView reported, newest last. Only touched on the main thread. */
class RequestLog {
    val items = mutableStateListOf<LoggedRequest>()
    private val ids = AtomicLong()

    fun nextId() = ids.incrementAndGet()

    fun add(r: LoggedRequest) {
        items += r
        if (items.size > MAX) items.removeRange(0, items.size - MAX)
    }

    fun clear() = items.clear()

    private fun update(url: String, change: (LoggedRequest) -> LoggedRequest) {
        val i = items.indexOfLast { it.url == url && it.status == null && it.error == null }
        if (i >= 0) items[i] = change(items[i])
    }

    fun markStatus(url: String, status: Int, reason: String?) = update(url) { it.copy(status = status, reason = reason) }
    fun markError(url: String, error: String) = update(url) { it.copy(error = error) }

    private companion object { const val MAX = 3000 }
}

/** Something a page asked for that needs a yes or no. */
sealed interface PageAsk {
    data class Ssl(val error: SslError, val handler: SslErrorHandler) : PageAsk
    data class Media(val request: PermissionRequest, val camera: Boolean, val microphone: Boolean) : PageAsk
    data class Location(val origin: String, val callback: GeolocationPermissions.Callback) : PageAsk
}

/**
 * The browser itself: one WebView with DevTools injected into every page, a log of every
 * request, and the state the screen shows (address, progress, back/forward …).
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(
    private val activity: ComponentActivity,
    val settings: Settings,
    val library: Library,
    private val launchFileChooser: (Intent) -> Unit,
    private val askPermissions: (Array<String>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    var webView: WebView by mutableStateOf(createWebView())
        private set

    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var favicon by mutableStateOf<Bitmap?>(null)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set
    var certificate by mutableStateOf<SslCertificate?>(null)
        private set
    /** Set when the user chose to carry on past a certificate error on this page. */
    var sslOverridden by mutableStateOf(false)
        private set
    /** Console errors and uncaught exceptions since the page started loading. */
    var errorCount by mutableIntStateOf(0)
        private set
    var findResult by mutableStateOf(0 to 0)
        private set
    /** Fullscreen video and the like. */
    var customView by mutableStateOf<View?>(null)
        private set
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    var ask by mutableStateOf<PageAsk?>(null)
        private set
    private var pendingPermission: PageAsk? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    val requests = RequestLog()

    /** WebView's own user agent, before any override. */
    val webViewUserAgent: String = WebSettings.getDefaultUserAgent(activity)
    val userAgent: String get() = webView.settings.userAgentString

    val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var devtoolsScript: String? = null
    private var scriptHandler: ScriptHandler? = null
    private var applied: List<Any?> = emptyList()
    private var installed: List<Any?>? = null

    init {
        applySettings(reloadIfNeeded = false)
        activity.lifecycleScope.launch {
            merge(
                settings.userAgent.flow, settings.customUserAgent.flow, settings.desktopMode.flow, settings.javaScript.flow,
                settings.disableCache.flow, settings.thirdPartyCookies.flow, settings.darkPages.flow, settings.textZoom.flow,
                settings.devtools.flow, settings.devtoolsButton.flow, settings.devtoolsSize.flow, settings.remoteDebugging.flow,
            ).collect { applySettings(reloadIfNeeded = true) }
        }
    }

    private fun createWebView(): WebView = WebView(activity).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(settings) {
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        setFindListener { active, count, done -> if (done) findResult = (if (count == 0) 0 else active + 1) to count }
    }

    /** Puts the settings into WebView; reloads the page when that's needed for them to show. */
    fun applySettings(reloadIfNeeded: Boolean) {
        val s = settings
        val uaId = if (s.desktopMode.value) (s.userAgent.value.takeIf { UserAgents.preset(it).desktop } ?: UserAgents.DESKTOP) else s.userAgent.value
        val ua = UserAgents.resolve(uaId, webViewUserAgent, s.customUserAgent.value)
        val now = listOf(ua, s.javaScript.value, s.devtools.value, s.devtoolsButton.value, s.devtoolsSize.value)
        val needsReload = applied.isNotEmpty() && applied != now
        applied = now

        WebView.setWebContentsDebuggingEnabled(s.remoteDebugging.value)
        with(webView.settings) {
            userAgentString = ua
            javaScriptEnabled = s.javaScript.value
            cacheMode = if (s.disableCache.value) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            textZoom = s.textZoom.value
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, s.thirdPartyCookies.value)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, s.darkPages.value)
        }
        installDevtools()
        if (reloadIfNeeded && needsReload && url.isNotEmpty()) webView.reload()
    }

    private fun installDevtools() {
        val wanted = listOf(settings.devtools.value, settings.devtoolsButton.value, settings.devtoolsSize.value)
        if (wanted == installed) return
        installed = wanted
        scriptHandler?.remove()
        scriptHandler = null
        devtoolsScript = null
        if (!settings.devtools.value) return
        val script = DevtoolsScript.build(activity, settings.devtoolsButton.value, settings.devtoolsSize.value)
        devtoolsScript = script
        if (documentStartSupported) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
    }

    /** For WebViews too old for document-start scripts: load DevTools as soon as the page commits. */
    private fun injectLate(view: WebView) {
        if (documentStartSupported) return
        devtoolsScript?.let { view.evaluateJavascript(it, null) }
    }

    fun load(input: String) {
        val target = UrlInput.resolve(input, UrlInput.engine(settings.searchEngine.value))
        if (target.isEmpty()) return
        webView.loadUrl(target)
    }

    fun loadUrl(url: String) = webView.loadUrl(url)
    fun goHome() = load(settings.homePage.value)
    fun back() { if (webView.canGoBack()) webView.goBack() }
    fun forward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()

    /** Back: closes DevTools if it's open, then goes back a page; [exit] when there's nowhere to go. */
    fun handleBack(exit: () -> Unit) {
        if (customView != null) { exitFullscreen(); return }
        webView.evaluateJavascript(DevtoolsScript.HIDE_IF_OPEN) { hid ->
            if (hid == "true") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else exit()
        }
    }

    /** Opens or closes the DevTools panel (loading it into the page first if it isn't there). */
    fun toggleDevtools(tool: String? = null) {
        if (!settings.devtools.value) {
            toast("DevTools are switched off in Settings")
            return
        }
        val js = if (tool != null) DevtoolsScript.show(tool) else DevtoolsScript.TOGGLE
        webView.evaluateJavascript(js) { result ->
            val r = result?.trim('"').orEmpty()
            when {
                r == "missing" || r == "null" || r.isEmpty() -> {
                    // Not in this page yet (opened before DevTools were on, or a page JS can't run in).
                    val script = devtoolsScript ?: return@evaluateJavascript
                    webView.evaluateJavascript(script) {
                        webView.evaluateJavascript(DevtoolsScript.show(tool ?: "")) { again ->
                            if (again?.trim('"') != "open") toast(if (settings.javaScript.value) "DevTools can't run on this page" else "DevTools need JavaScript (Settings)")
                        }
                    }
                }
                r.startsWith("error:") -> toast("DevTools failed on this page: " + r.removePrefix("error:").substringBefore("\\n").take(120))
            }
        }
    }

    fun find(query: String) { if (query.isEmpty()) clearFind() else webView.findAllAsync(query) }
    fun findNext(forward: Boolean) = webView.findNext(forward)
    fun clearFind() { webView.clearMatches(); findResult = 0 to 0 }

    /** The live DOM (after scripts ran), as the page has it now. */
    fun renderedHtml(done: (String) -> Unit) {
        webView.evaluateJavascript("(function(){var d=document.doctype;return (d?new XMLSerializer().serializeToString(d)+'\\n':'')+document.documentElement.outerHTML;})()") { json ->
            done(runCatching { Json.decodeFromString(String.serializer(), json) }.getOrDefault(""))
        }
    }

    /** Runs [js] in the page and hands back its JSON result. */
    fun evaluate(js: String, done: (String?) -> Unit) = webView.evaluateJavascript(js, done)

    fun cookies(): String? = CookieManager.getInstance().getCookie(url)

    fun setCookie(cookie: String, done: () -> Unit = {}) {
        CookieManager.getInstance().setCookie(url, cookie) { CookieManager.getInstance().flush(); done() }
    }

    /** Best effort: expires cookies by name for this page's host and its parent domains and paths. */
    fun deleteCookies(names: List<String>, done: () -> Unit = {}) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val cm = CookieManager.getInstance()
        val domains = buildList {
            add(null)
            val parts = host.split('.')
            for (i in 0 until parts.size - 1) add("." + parts.drop(i).joinToString("."))
        }
        val segments = uri.pathSegments
        val paths = listOf("/") + segments.indices.map { "/" + segments.take(it + 1).joinToString("/") }
        for (name in names) for (d in domains) for (p in paths) {
            cm.setCookie(url, "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$p" + (d?.let { "; Domain=$it" } ?: ""))
        }
        cm.flush()
        main.postDelayed(done, 150)
    }

    fun clearCache() { webView.clearCache(true); toast("Cache cleared") }
    fun clearCookies() { CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }; toast("Cookies cleared") }
    fun clearStorage() { WebStorage.getInstance().deleteAllData(); toast("Site storage cleared") }
    fun clearSitePermissions() { GeolocationPermissions.getInstance().clearAll(); toast("Sites will ask for location again") }

    fun exitFullscreen() {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    // --- Page asks: certificate errors, camera/microphone, location ---

    /** The user's answer; with [remember], a site's location answer is kept so it isn't asked again. */
    fun answer(allow: Boolean, remember: Boolean = true) {
        val a = ask ?: return
        ask = null
        when (a) {
            is PageAsk.Ssl -> if (allow) { sslOverridden = true; a.handler.proceed() } else a.handler.cancel()
            is PageAsk.Media -> {
                if (!allow) { a.request.deny(); return }
                val needed = buildList {
                    if (a.camera) add(Manifest.permission.CAMERA)
                    if (a.microphone) { add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.MODIFY_AUDIO_SETTINGS) }
                }.filter { !granted(it) }
                if (needed.isEmpty()) grantMedia(a) else { pendingPermission = a; askPermissions(needed.toTypedArray()) }
            }
            is PageAsk.Location -> {
                if (!allow) { a.callback.invoke(a.origin, false, remember); return }
                val needed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (needed.any(::granted)) a.callback.invoke(a.origin, true, true)
                else { pendingPermission = a; askPermissions(needed.toTypedArray()) }
            }
        }
    }

    /** Android's answer to the runtime permissions a page needed. */
    fun onPermissionsResult(result: Map<String, Boolean>) {
        val p = pendingPermission ?: return
        pendingPermission = null
        when (p) {
            is PageAsk.Media -> if (result.values.any { it } || result.isEmpty()) grantMedia(p) else p.request.deny()
            // Only kept when Android allowed it too, so a refusal there can be retried.
            is PageAsk.Location -> result.values.any { it }.let { ok -> p.callback.invoke(p.origin, ok, ok) }
            is PageAsk.Ssl -> Unit
        }
    }

    private fun grantMedia(a: PageAsk.Media) {
        val resources = a.request.resources.filter {
            (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && granted(Manifest.permission.CAMERA)) ||
                (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && granted(Manifest.permission.RECORD_AUDIO)) ||
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (resources.isEmpty()) a.request.deny() else a.request.grant(resources.toTypedArray())
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED

    fun onFileChosen(resultCode: Int, data: Intent?) {
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
    }

    // --- Downloads ---

    private fun download(url: String, ua: String, disposition: String?, mime: String?) {
        if (!url.startsWith("http")) {
            toast("Can't download ${url.substringBefore(':')}: links; use DevTools ▸ Resources or Sources instead")
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .apply { CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) } }
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            activity.getSystemService(DownloadManager::class.java).enqueue(req)
        }.onSuccess { toast("Downloading $name") }.onFailure { toast("Couldn't download: ${it.message}") }
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    private fun syncNavigation(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    fun saveState(out: Bundle) { webView.saveState(out) }
    fun restoreState(state: Bundle): Boolean = webView.restoreState(state) != null

    fun onResume() { webView.onResume(); webView.resumeTimers() }
    fun onPause() { webView.onPause() }
    fun destroy() {
        scriptHandler?.remove()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val headers = request.requestHeaders.orEmpty()
            val entry = LoggedRequest(
                id = requests.nextId(), time = System.currentTimeMillis(), method = request.method ?: "GET", url = url,
                mainFrame = request.isForMainFrame, hasGesture = request.hasGesture(),
                redirect = runCatching { request.isRedirect }.getOrDefault(false),
                headers = headers, type = Requests.classify(url, headers, request.isForMainFrame),
            )
            main.post {
                if (entry.mainFrame && !entry.redirect && !settings.preserveLog.value) requests.clear()
                requests.add(entry)
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "http", "https", "about", "data", "javascript", "blob", "file", null -> false
                "intent" -> {
                    runCatching {
                        val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).addCategory(Intent.CATEGORY_BROWSABLE)
                        intent.component = null
                        intent.selector = null
                        try { activity.startActivity(intent) } catch (_: ActivityNotFoundException) {
                            intent.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                        }
                    }
                    true
                }
                else -> {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: ActivityNotFoundException) {
                        toast("No app opens ${uri.scheme}: links")
                    }
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            this@BrowserController.url = url
            this@BrowserController.favicon = favicon
            loading = true
            errorCount = 0
            certificate = null
            sslOverridden = false
            syncNavigation(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String) { injectLate(view) }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
            this@BrowserController.url = view.url ?: url
            certificate = view.certificate
            syncNavigation(view)
            injectLate(view)
            library.addHistory(this@BrowserController.url, view.title.orEmpty())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            this@BrowserController.url = url
            syncNavigation(view)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            val u = request.url.toString()
            main.post { requests.markStatus(u, response.statusCode, response.reasonPhrase) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val u = request.url.toString()
            val text = error.description?.toString() ?: "error ${error.errorCode}"
            main.post { requests.markError(u, text) }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            ask = PageAsk.Ssl(error, handler)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed: swap in a fresh WebView and load it again.
            val last = url
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            scriptHandler = null
            webView = createWebView()
            applied = emptyList()
            installed = null
            applySettings(reloadIfNeeded = false)
            toast(if (detail.didCrash()) "The page crashed; reloading" else "The page was closed to free memory; reloading")
            if (last.isNotEmpty()) webView.loadUrl(last)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        override fun onReceivedTitle(view: WebView, title: String?) { this@BrowserController.title = title.orEmpty() }
        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { favicon = icon }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) main.post { errorCount++ }
            return false
        }

        // window.open and target=_blank: no tabs, so they open in this page.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val probe = WebView(activity)
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    view.loadUrl(request.url.toString())
                    probe.destroy()
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
        }

        override fun onHideCustomView() {
            customView = null
            customViewCallback = null
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                launchFileChooser(params.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val camera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
            val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
            if (!camera && !mic) { request.grant(request.resources); return }
            main.post { ask = PageAsk.Media(request, camera, mic) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if ((ask as? PageAsk.Media)?.request == request) ask = null
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (settings.blockLocation.value) callback.invoke(origin, false, false)
            else ask = PageAsk.Location(origin, callback)
        }

        override fun onGeolocationPermissionsHidePrompt() {
            if (ask is PageAsk.Location) ask = null
        }
    }
}
