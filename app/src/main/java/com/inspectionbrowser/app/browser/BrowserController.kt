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
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.PixelCopy
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.inspectionbrowser.app.data.Library
import com.inspectionbrowser.app.data.Settings
import com.inspectionbrowser.app.data.TextFiles
import com.inspectionbrowser.app.devtools.DevtoolsReport
import com.inspectionbrowser.app.devtools.DevtoolsScript
import com.inspectionbrowser.app.devtools.NativeDump
import com.inspectionbrowser.app.devtools.SourceFile
import com.inspectionbrowser.app.net.Fetcher
import com.inspectionbrowser.app.net.LoggedRequest
import com.inspectionbrowser.app.net.Requests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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

/** A long-pressed link and/or image, for its menu (open in new tab, copy …). */
data class LongPressTarget(val link: String?, val image: String?)

/** The open tabs, saved so they come back after the app is closed. */
@Serializable
private data class SavedTabs(val tabs: List<SavedTab>, val current: Int)

@Serializable
private data class SavedTab(val url: String, val title: String = "")

/** Private tabs keep cookies and storage in their own WebView profile, wiped when the last one closes. */
private const val PRIVATE_PROFILE = "private"

/**
 * Tells the app whether a touch started on something already scrolled (an inner scroller,
 * or the DevTools panel), so pulling down there scrolls it instead of refreshing the page.
 */
private const val PULL_GUARD = """(function(){if(window.top!==window||window.__ibPull||typeof __ibGesture==='undefined')return;window.__ibPull=1;
function blocked(e){var p=e.composedPath?e.composedPath():[];for(var i=0;i<p.length;i++){var el=p[i];if(el===document||el===window)break;
if(el.id==='eruda')return true;if(el.nodeType===1&&el.scrollTop>0)return true;}var s=document.scrollingElement;return !!(s&&s.scrollTop>0);}
addEventListener('touchstart',function(e){try{__ibGesture.postMessage(blocked(e)?'1':'0');}catch(_){}},{capture:true,passive:true});})();"""

/**
 * The browser itself: tabs, each a WebView with DevTools injected into every page and a log
 * of every request, and the state the screen shows for the current one (address, progress,
 * back/forward …).
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
    private val tabIds = AtomicLong()

    /** One page, with its own history, request log and DevTools. */
    inner class Tab internal constructor(
        /** Restored tabs load their page only when first shown. */
        private var pendingUrl: String? = null,
        savedTitle: String = "",
        /** The tab that opened this one (target=_blank, window.open), to go back to when it has no history. */
        val openerId: Long? = null,
        /** No history, and cookies/storage apart from normal tabs (when this WebView supports profiles). */
        val incognito: Boolean = false,
    ) {
        val id = tabIds.incrementAndGet()

        private var view: WebView? by mutableStateOf(null)
        /** Made on first use, so restored tabs cost nothing until they're opened. */
        val webView: WebView get() = view ?: createWebView().also { view = it; configure(reloadIfNeeded = false) }
        val created: Boolean get() = view != null

        var url by mutableStateOf(pendingUrl.orEmpty())
            internal set
        var title by mutableStateOf(savedTitle)
            internal set
        var favicon by mutableStateOf<Bitmap?>(null)
            internal set
        var progress by mutableIntStateOf(0)
            internal set
        var loading by mutableStateOf(false)
            internal set
        var canGoBack by mutableStateOf(false)
            internal set
        var canGoForward by mutableStateOf(false)
            internal set
        var certificate by mutableStateOf<SslCertificate?>(null)
            internal set
        var sslOverridden by mutableStateOf(false)
            internal set
        var errorCount by mutableIntStateOf(0)
            internal set
        val requests = RequestLog()
        /** A small picture of the page for the tab list, taken when leaving the tab. */
        var thumbnail by mutableStateOf<Bitmap?>(null)
            internal set
        /** The last touch started on something scrolled: pull-to-refresh leaves it alone. */
        @Volatile var pullBlocked = false
            private set

        /** This tab's cookie jar: the private profile's for private tabs. */
        val cookieManager: CookieManager
            get() = if (incognito && profilesSupported) ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager
            else CookieManager.getInstance()

        private var scriptHandler: ScriptHandler? = null
        private var guardHandler: ScriptHandler? = null
        private var applied: List<Any?> = emptyList()
        private var installedScript: String? = null

        /** Loads a restored tab's page the first time it's shown. */
        internal fun loadPending() {
            val u = pendingUrl ?: return
            pendingUrl = null
            webView.loadUrl(u)
        }

        /** Puts the settings into this tab's WebView (if it has one yet). */
        internal fun configure(reloadIfNeeded: Boolean) {
            val wv = view ?: return
            val s = settings
            val ua = userAgentFor(s)
            val now = listOf(ua, s.javaScript.value, devtoolsScript)
            val needsReload = applied.isNotEmpty() && applied != now
            applied = now
            with(wv.settings) {
                userAgentString = ua
                javaScriptEnabled = s.javaScript.value
                cacheMode = if (s.disableCache.value) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
                textZoom = s.textZoom.value
            }
            cookieManager.setAcceptThirdPartyCookies(wv, s.thirdPartyCookies.value)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, s.darkPages.value)
            }
            if (installedScript !== devtoolsScript) {
                scriptHandler?.remove()
                scriptHandler = null
                installedScript = devtoolsScript
                val script = devtoolsScript
                if (script != null && documentStartSupported) {
                    scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("*"))
                }
            }
            if (reloadIfNeeded && needsReload && url.isNotEmpty()) wv.reload()
        }

        /** For WebViews too old for document-start scripts: load DevTools as soon as the page commits. */
        private fun injectLate(v: WebView) {
            if (documentStartSupported) return
            devtoolsScript?.let { v.evaluateJavascript(it, null) }
            if (messagesSupported) v.evaluateJavascript(PULL_GUARD, null)
        }

        /**
         * Copies what the tab shows on screen now, small, for the tab list. PixelCopy reads the
         * window's finished pixels, so the WebView is never asked to draw itself off-screen.
         */
        internal fun captureThumbnail() {
            val v = view ?: return
            if (v.width <= 0 || v.height <= 0 || !v.isAttachedToWindow) return
            runCatching {
                val at = IntArray(2).also(v::getLocationInWindow)
                val area = android.graphics.Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
                val scale = 0.3f
                val bmp = Bitmap.createBitmap((v.width * scale).toInt().coerceAtLeast(1), (v.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, area, bmp, { result -> if (result == PixelCopy.SUCCESS) thumbnail = bmp }, main)
            }
        }

        internal fun destroy() {
            scriptHandler?.remove()
            guardHandler?.remove()
            view?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
            view = null
        }

        private fun syncNavigation(v: WebView) {
            canGoBack = v.canGoBack()
            canGoForward = v.canGoForward()
        }

        private fun createWebView(): WebView = WebView(activity).apply {
            // A private tab's profile has to be set before the WebView does anything else.
            if (incognito && profilesSupported) WebViewCompat.setProfile(this, PRIVATE_PROFILE)
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
            setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime, cookieManager.getCookie(url)) }
            if (messagesSupported) {
                WebViewCompat.addWebMessageListener(this, "__ibGesture", setOf("*")) { _, message, _, isMainFrame, _ ->
                    if (isMainFrame) pullBlocked = message.data == "1"
                }
                if (documentStartSupported) guardHandler = WebViewCompat.addDocumentStartJavaScript(this, PULL_GUARD, setOf("*"))
            }
            setFindListener { active, count, done ->
                if (done && current === this@Tab) findResult = (if (count == 0) 0 else active + 1) to count
            }
            setOnLongClickListener { longPress(this) }
        }

        private fun longPress(v: WebView): Boolean {
            val hit = v.hitTestResult
            return when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> { longPressed = LongPressTarget(hit.extra, null); true }
                WebView.HitTestResult.IMAGE_TYPE -> { longPressed = LongPressTarget(null, hit.extra); true }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // The hit only carries the image; ask WebView for the link around it.
                    val image = hit.extra
                    val reply = Handler(Looper.getMainLooper()) { m ->
                        longPressed = LongPressTarget(m.data.getString("url") ?: image, image)
                        true
                    }.obtainMessage()
                    v.requestFocusNodeHref(reply)
                    true
                }
                else -> false
            }
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
                return if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) TestSite.serve(assetLoader, request.url) else null
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
                this@Tab.url = url
                this@Tab.favicon = favicon
                loading = true
                errorCount = 0
                certificate = null
                sslOverridden = false
                syncNavigation(view)
            }

            override fun onPageCommitVisible(view: WebView, url: String) { injectLate(view) }

            override fun onPageFinished(view: WebView, url: String) {
                loading = false
                this@Tab.url = view.url ?: url
                certificate = view.certificate
                syncNavigation(view)
                injectLate(view)
                if (!incognito) library.addHistory(this@Tab.url, view.title.orEmpty())
                saveTabs()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                this@Tab.url = url
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
                raise(this@Tab, PageAsk.Ssl(error, handler))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The renderer crashed or was killed: give this tab a fresh WebView and load the page again.
                val last = url
                scriptHandler = null
                guardHandler = null
                installedScript = null
                applied = emptyList()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                this@Tab.view = null
                pendingUrl = last.ifEmpty { null }
                if (current === this@Tab) {
                    toast(if (detail.didCrash()) "The page crashed; reloading" else "The page was closed to free memory; reloading")
                    loadPending()
                }
                return true
            }
        }

        private inner class Chrome : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            override fun onReceivedTitle(view: WebView, title: String?) { this@Tab.title = title.orEmpty() }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) { favicon = icon }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) main.post { errorCount++ }
                return false
            }

            // target=_blank and window.open: a new tab, linked to this one (window.opener works).
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val tab = Tab(openerId = this@Tab.id, incognito = incognito)
                tabs.add((tabs.indexOf(this@Tab) + 1).coerceIn(0, tabs.size), tab)
                (resultMsg.obj as WebView.WebViewTransport).webView = tab.webView
                resultMsg.sendToTarget()
                select(tab)
                return true
            }

            override fun onCloseWindow(window: WebView) { close(this@Tab) }

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
                main.post { raise(this@Tab, PageAsk.Media(request, camera, mic)) }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if ((ask as? PageAsk.Media)?.request == request) ask = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (settings.blockLocation.value) callback.invoke(origin, false, false)
                else raise(this@Tab, PageAsk.Location(origin, callback))
            }

            override fun onGeolocationPermissionsHidePrompt() {
                if (ask is PageAsk.Location) ask = null
            }
        }
    }

    val tabs = mutableStateListOf<Tab>()
    /** Starts as a placeholder (no WebView yet) until [start] restores or opens the real tabs. */
    var current: Tab by mutableStateOf(Tab())
        private set

    // The current tab's state, which is what the screen shows.
    val webView: WebView get() = current.webView
    val url: String get() = current.url
    val title: String get() = current.title
    val favicon: Bitmap? get() = current.favicon
    val progress: Int get() = current.progress
    val loading: Boolean get() = current.loading
    /** Back also works from a tab a link opened: it closes and returns to that page. */
    val canGoBack: Boolean get() = current.canGoBack || current.openerId?.let { id -> tabs.any { it.id == id } } == true
    val canGoForward: Boolean get() = current.canGoForward
    val certificate: SslCertificate? get() = current.certificate
    /** Set when the user chose to carry on past a certificate error on this page. */
    val sslOverridden: Boolean get() = current.sslOverridden
    /** Console errors and uncaught exceptions since the page started loading. */
    val errorCount: Int get() = current.errorCount
    val requests: RequestLog get() = current.requests

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

    /** A link or image the user long-pressed, for its menu. */
    var longPressed by mutableStateOf<LongPressTarget?>(null)

    /** WebView's own user agent, before any override. */
    val webViewUserAgent: String = WebSettings.getDefaultUserAgent(activity)
    val userAgent: String get() = webView.settings.userAgentString

    val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    /** Serves the built-in test site (assets/testsite) at a real https address. */
    private val assetLoader = TestSite.loader(activity)
    private val messagesSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    /** Without profiles (old WebView), private tabs share cookies with the others but still keep no history. */
    val profilesSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private var devtoolsScript: String? = null
    private var devtoolsKey: List<Any?>? = null

    private val prefs = activity.getSharedPreferences("tabs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Anything a private tab left behind when the app was last closed goes now.
        wipePrivateData()
        applySettings(reloadIfNeeded = false)
        activity.lifecycleScope.launch {
            merge(
                settings.userAgent.flow, settings.customUserAgent.flow, settings.desktopMode.flow, settings.javaScript.flow,
                settings.disableCache.flow, settings.thirdPartyCookies.flow, settings.darkPages.flow, settings.textZoom.flow,
                settings.devtools.flow, settings.devtoolsButton.flow, settings.devtoolsSize.flow, settings.remoteDebugging.flow,
            ).collect { applySettings(reloadIfNeeded = true) }
        }
    }

    private fun userAgentFor(s: Settings): String {
        val id = if (s.desktopMode.value) (s.userAgent.value.takeIf { UserAgents.preset(it).desktop } ?: UserAgents.DESKTOP) else s.userAgent.value
        return UserAgents.resolve(id, webViewUserAgent, s.customUserAgent.value)
    }

    /** Puts the settings into every tab; reloads pages when that's needed for them to show. */
    fun applySettings(reloadIfNeeded: Boolean) {
        WebView.setWebContentsDebuggingEnabled(settings.remoteDebugging.value)
        val key = listOf(settings.devtools.value, settings.devtoolsButton.value, settings.devtoolsSize.value)
        if (key != devtoolsKey) {
            devtoolsKey = key
            devtoolsScript = if (settings.devtools.value) DevtoolsScript.build(activity, settings.devtoolsButton.value, settings.devtoolsSize.value) else null
        }
        tabs.forEach { it.configure(reloadIfNeeded) }
    }

    // --- Tabs ---

    /** Brings back the tabs from last time (or opens a home tab), then opens [link] in a new tab if given. */
    fun start(link: String?) {
        val saved = runCatching { json.decodeFromString(SavedTabs.serializer(), prefs.getString("tabs", null) ?: "") }.getOrNull()
        saved?.tabs?.forEach { tabs += Tab(pendingUrl = it.url.ifEmpty { null }, savedTitle = it.title) }
        when {
            link != null -> newTab(link)
            tabs.isEmpty() || saved == null -> newTab(null)
            else -> select(tabs[saved.current.coerceIn(0, tabs.size - 1)])
        }
    }

    /**
     * Opens a tab with [input] (an address or a search; the home page when null). [background]
     * keeps the current tab showing; [fromCurrent] puts it next to the current tab, which Back returns to.
     */
    fun newTab(input: String?, background: Boolean = false, fromCurrent: Boolean = false, incognito: Boolean = fromCurrent && current.incognito): Tab {
        val tab = Tab(openerId = if (fromCurrent) current.id else null, incognito = incognito)
        if (incognito && !profilesSupported) toast("This WebView can't keep private cookies apart; this tab only skips history")
        val at = if (fromCurrent) tabs.indexOf(current) + 1 else tabs.size
        tabs.add(at.coerceIn(0, tabs.size), tab)
        val target = UrlInput.resolve(input ?: settings.homePage.value, UrlInput.engine(settings.searchEngine.value))
        tab.url = target
        tab.webView.loadUrl(target)
        if (background) saveTabs() else select(tab)
        return tab
    }

    fun select(tab: Tab) {
        if (tab !in tabs) return
        val previous = current
        if (previous !== tab && previous.created) {
            previous.captureThumbnail()
            previous.webView.clearMatches()
            previous.webView.evaluateJavascript(DevtoolsScript.HIDE_IF_OPEN, null)
            previous.webView.onPause()
        }
        current = tab
        findResult = 0 to 0
        tab.webView.onResume()
        tab.loadPending()
        saveTabs()
    }

    /** Closes [tab]; the tab that opened it, or a neighbour, takes its place. Closing the last opens a home tab. */
    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasCurrent = tab === current
        tabs.removeAt(index)
        when {
            tabs.isEmpty() -> newTab(null)
            wasCurrent -> select(tabs.firstOrNull { it.id == tab.openerId } ?: tabs[(index - 1).coerceAtLeast(0)])
        }
        tab.destroy()
        if (tab.incognito && tabs.none { it.incognito }) wipePrivateData()
        saveTabs()
    }

    fun closeAll() {
        val old = tabs.toList()
        tabs.clear()
        newTab(null)
        old.forEach { it.destroy() }
        wipePrivateData()
    }

    /** Clears the private profile's cookies and storage. */
    private fun wipePrivateData() {
        if (!profilesSupported) return
        runCatching {
            val profile = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            profile.cookieManager.removeAllCookies(null)
            profile.webStorage.deleteAllData()
        }
    }

    /** Keeps the current tab's picture up to date, for the tab list. */
    fun captureCurrent() = current.captureThumbnail()

    /** Whether pull-to-refresh may start now: the page is at its top and the touch wasn't on a scroller. */
    fun canPullToRefresh(): Boolean = current.created && !webView.canScrollVertically(-1) && webView.scrollY == 0 && !current.pullBlocked

    /** Private tabs are never saved. */
    fun saveTabs() {
        val kept = tabs.filter { !it.incognito }
        val state = SavedTabs(kept.map { SavedTab(it.url, it.title) }, kept.indexOf(current).coerceAtLeast(0))
        prefs.edit().putString("tabs", json.encodeToString(SavedTabs.serializer(), state)).apply()
    }

    /** A page asked for something: show its tab with the question. */
    private fun raise(tab: Tab, question: PageAsk) {
        if (tab !== current) select(tab)
        ask = question
    }

    // --- The current tab ---

    fun load(input: String) {
        val target = UrlInput.resolve(input, UrlInput.engine(settings.searchEngine.value))
        if (target.isEmpty()) return
        webView.loadUrl(target)
    }

    fun loadUrl(url: String) = webView.loadUrl(url)
    fun goHome() = load(settings.homePage.value)
    fun back() {
        val tab = current
        when {
            tab.webView.canGoBack() -> tab.webView.goBack()
            tab.openerId != null && tabs.any { it.id == tab.openerId } -> close(tab)
        }
    }
    fun forward() { if (webView.canGoForward()) webView.goForward() }
    fun reload() = webView.reload()
    fun stop() = webView.stopLoading()

    /**
     * Back: closes DevTools if it's open, then goes back a page, then closes a tab that a link
     * opened (returning to that page); [exit] when there's nowhere to go.
     */
    fun handleBack(exit: () -> Unit) {
        if (customView != null) { exitFullscreen(); return }
        val tab = current
        tab.webView.evaluateJavascript(DevtoolsScript.HIDE_IF_OPEN) { hid ->
            when {
                hid == "true" -> Unit
                tab.webView.canGoBack() -> tab.webView.goBack()
                tab.openerId != null && tabs.any { it.id == tab.openerId } -> close(tab)
                else -> exit()
            }
        }
    }

    /** Opens or closes the DevTools panel (loading it into the page first if it isn't there). */
    fun toggleDevtools(tool: String? = null) {
        if (!settings.devtools.value) {
            toast("DevTools are switched off in Settings")
            return
        }
        val wv = webView
        val js = if (tool != null) DevtoolsScript.show(tool) else DevtoolsScript.TOGGLE
        wv.evaluateJavascript(js) { result ->
            val r = result?.trim('"').orEmpty()
            when {
                r == "missing" || r == "null" || r.isEmpty() -> {
                    // Not in this page yet (opened before DevTools were on, or a page JS can't run in).
                    val script = devtoolsScript ?: return@evaluateJavascript
                    wv.evaluateJavascript(script) {
                        wv.evaluateJavascript(DevtoolsScript.show(tool ?: "")) { again ->
                            if (again?.trim('"') == "open") addPlugins(wv)
                            else toast(if (settings.javaScript.value) "DevTools can't run on this page" else "DevTools need JavaScript (Settings)")
                        }
                    }
                }
                r == "open" -> addPlugins(wv)
                r.startsWith("error:") -> toast("DevTools failed on this page: " + r.removePrefix("error:").substringBefore("\\n").take(120))
            }
        }
    }

    /** The extra DevTools tabs (Code, Timing, Monitor …), added the first time DevTools open on a page. */
    private fun addPlugins(wv: WebView) {
        wv.evaluateJavascript(DevtoolsScript.NEEDS_PLUGINS) { needs ->
            if (needs == "true") wv.evaluateJavascript(DevtoolsScript.plugins(activity), null)
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

    /** Everything in the DevTools console as text; null when DevTools aren't in this page. */
    private fun consoleText(done: (String?) -> Unit) {
        webView.evaluateJavascript("(function(){return window.__ibDevtools&&__ibDevtools.consoleText?__ibDevtools.consoleText():null;})()") { json ->
            done(runCatching { Json.decodeFromString(String.serializer(), json) }.getOrNull())
        }
    }

    private fun noConsole() = toast(
        if (!settings.devtools.value) "Turn on DevTools in Settings to record the console"
        else "There's no console for this page yet; open DevTools (🐞) once, then try again",
    )

    private fun messageCount(text: String) = text.lineSequence().drop(2).firstOrNull()?.substringBefore(' ')?.toIntOrNull() ?: 0

    /** Copies every console message at once (a log too big for the clipboard says to save it instead). */
    fun copyConsole() = consoleText { text ->
        if (text == null) { noConsole(); return@consoleText }
        TextFiles.copy(activity, text, "Console log (${messageCount(text)} messages)")
    }

    /** Saves every console message as a .txt file in Downloads. */
    fun saveConsole() = consoleText { text ->
        if (text == null) { noConsole(); return@consoleText }
        TextFiles.save(activity, TextFiles.fileName("console", url), text)
    }

    /** Shares every console message as a text file (no size limit), e.g. to email or save it. */
    fun shareConsole() = consoleText { text ->
        if (text == null) { noConsole(); return@consoleText }
        shareTextFile("console", text, "Console log: $url", "Share console log")
    }

    /** Shares [text] as a .txt file named after the page, through the app's file provider. */
    private fun shareTextFile(kind: String, text: String, subject: String, chooserTitle: String) {
        runCatching {
            val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, TextFiles.fileName(kind, url)).apply { writeText(text) }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(send, chooserTitle))
        }.onFailure { toast("Couldn't share it: ${it.message}") }
    }

    // --- Everything the DevTools show, in one report ---

    private var collecting = false

    /** Shares the whole report as a text file, however big. */
    fun shareEverything() = collectEverything { text -> shareTextFile("devtools", text, "DevTools report: $url", "Share everything") }

    /**
     * Gathers Console, Elements, Network, Resources, Sources and Info for the current page: the
     * page reports what it can see, and the app adds every request, all cookies, the server's
     * HTML and the contents of each script and stylesheet file.
     */
    private fun collectEverything(done: (String) -> Unit) {
        if (collecting) { toast("Still collecting…"); return }
        collecting = true
        val tab = current
        val wv = tab.webView
        val pageUrl = tab.url
        val ua = wv.settings.userAgentString
        val requests = tab.requests.items.toList()
        val cert = tab.certificate?.let { "HTTPS; issued to ${it.issuedTo.dName}; by ${it.issuedBy.dName}; valid ${it.validNotBeforeDate} – ${it.validNotAfterDate}" }
        val jar = tab.cookieManager
        toast("Collecting everything from this page…")
        activity.lifecycleScope.launch {
            try {
                val raw = suspendCoroutine { c -> wv.evaluateJavascript(DevtoolsScript.report(activity)) { c.resume(it) } }
                val page = DevtoolsReport.parsePage(raw)
                val cookieFor = { u: String -> runCatching { jar.getCookie(u) }.getOrNull() }
                val server = runCatching { Fetcher.fetch(pageUrl, ua, cookies = cookieFor(pageUrl)) }
                val gate = Semaphore(6)
                suspend fun fill(files: List<SourceFile>): List<SourceFile> = coroutineScope {
                    files.mapIndexed { i, f ->
                        async {
                            val u = f.url ?: return@async f
                            if (!u.startsWith("http")) return@async f.copy(note = "not a web address")
                            if (i >= MAX_SOURCE_FILES) return@async f.copy(note = "not fetched: more than $MAX_SOURCE_FILES files")
                            gate.withPermit {
                                runCatching { Fetcher.fetch(u, ua, cookies = cookieFor(u)) }.fold(
                                    { r -> f.copy(content = r.body, note = "${r.code} ${r.message}, ${r.bytes} bytes" + if (r.truncated) ", cut" else "") },
                                    { e -> f.copy(note = "couldn't fetch: ${e.message}") },
                                )
                            }
                        }
                    }.awaitAll()
                }
                val fullPage = if (page == null) null else page.copy(scripts = fill(page.scripts), styles = fill(page.styles))
                val native = NativeDump(
                    userAgent = ua,
                    webView = runCatching { WebViewCompat.getCurrentWebViewPackage(activity)?.let { "${it.packageName} ${it.versionName}" } }.getOrNull(),
                    certificate = cert,
                    cookies = Requests.parseCookies(cookieFor(pageUrl)),
                    requests = requests,
                    serverSource = server.fold({ SourceFile(pageUrl, it.body) }, { SourceFile(pageUrl, null, "couldn't fetch: ${it.message}") }),
                    serverStatus = server.getOrNull()?.let { "${it.code} ${it.message}, ${it.contentType ?: "no content type"}" },
                )
                val text = withContext(Dispatchers.Default) { DevtoolsReport.format(fullPage, native, pageUrl, Date().toString()) }
                done(text)
            } catch (e: Exception) {
                toast("Couldn't collect everything: ${e.message}")
            } finally {
                collecting = false
            }
        }
    }

    /** Runs [js] in the page and hands back its JSON result. */
    fun evaluate(js: String, done: (String?) -> Unit) = webView.evaluateJavascript(js, done)

    fun cookies(): String? = current.cookieManager.getCookie(url)

    /** The cookies the current tab would send to [url] (its own jar for a private tab). */
    fun cookiesFor(url: String): String? = runCatching { current.cookieManager.getCookie(url) }.getOrNull()

    fun setCookie(cookie: String, done: () -> Unit = {}) {
        val cm = current.cookieManager
        cm.setCookie(url, cookie) { cm.flush(); done() }
    }

    /** Best effort: expires cookies by name for this page's host and its parent domains and paths. */
    fun deleteCookies(names: List<String>, done: () -> Unit = {}) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val cm = current.cookieManager
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
            is PageAsk.Ssl -> if (allow) { current.sslOverridden = true; a.handler.proceed() } else a.handler.cancel()
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

    private fun download(url: String, ua: String, disposition: String?, mime: String?, cookies: String?) {
        if (!url.startsWith("http")) {
            toast("Can't download ${url.substringBefore(':')}: links; use DevTools ▸ Resources or Sources instead")
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .apply { cookies?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) } }
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .apply {
                    // Android 8 and 9 need a storage permission for the public Downloads folder;
                    // without it, use the app's own Downloads folder.
                    if (android.os.Build.VERSION.SDK_INT >= 29 || granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    } else {
                        setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name)
                    }
                }
            activity.getSystemService(DownloadManager::class.java).enqueue(req)
        }.onSuccess { toast("Downloading $name") }.onFailure { toast("Couldn't download: ${it.message}") }
    }

    /** Saves a long-pressed image to Downloads. */
    fun downloadImage(url: String) = download(url, userAgent, null, null, cookiesFor(url))

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    fun onResume() { if (current.created) { webView.onResume(); webView.resumeTimers() } }
    fun onPause() { if (current.created) webView.onPause(); saveTabs() }
    fun destroy() = tabs.forEach { it.destroy() }

    private companion object {
        /** Script and stylesheet files fetched for the report, at most. */
        const val MAX_SOURCE_FILES = 80
    }
}
