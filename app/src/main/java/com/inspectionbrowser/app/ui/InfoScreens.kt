package com.inspectionbrowser.app.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.data.Site
import com.inspectionbrowser.app.net.Requests
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.DateFormat
import java.util.Date

/** All of the site's cookies, including HttpOnly ones that page scripts (and Eruda) can't see. */
@Composable
fun CookiesScreen(browser: BrowserController, onBack: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val cookies = remember(version) { Requests.parseCookies(browser.cookies()) }
    var adding by remember { mutableStateOf(false) }
    val refresh: () -> Unit = { version++ }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Cookies", onBack, subtitle = "${cookies.size} for ${Requests.host(browser.url)}") {
            IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add cookie") }
            IconButton(onClick = { browser.deleteCookies(cookies.map { it.first }, refresh) }) {
                Icon(Icons.Default.DeleteSweep, "Delete all")
            }
        }
        Text(
            "Includes HttpOnly cookies. Android doesn't say which domain, path or expiry a cookie has; " +
                "DevTools ▸ Resources shows the ones scripts can read.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        HorizontalDivider()
        if (cookies.isEmpty()) Text("No cookies for this page.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(cookies) { (name, value) ->
                Row(Modifier.fillMaxWidth().clickable { copy(context, "$name=$value", "Cookie") }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { browser.deleteCookies(listOf(name), refresh) }) { Icon(Icons.Default.Delete, "Delete") }
                }
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add cookie") },
            text = {
                Column {
                    Text("As a Set-Cookie value, e.g. name=value; Path=/; Max-Age=3600", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = false, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { adding = false; if (text.contains('=')) browser.setCookie(text.trim(), refresh) }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

private const val PAGE_FACTS = """(function(){var n=navigator,s=screen,c=n.connection||{};return JSON.stringify({
"Viewport":innerWidth+' × '+innerHeight+' CSS px',"Device pixel ratio":String(devicePixelRatio),
"Screen":s.width+' × '+s.height+' ('+(s.orientation?s.orientation.type:'')+')',"Visual viewport scale":String(window.visualViewport?visualViewport.scale:1),
"Color scheme":matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light',"Reduced motion":String(matchMedia('(prefers-reduced-motion: reduce)').matches),
"Touch points":String(n.maxTouchPoints),"Language":(n.languages||[n.language]).join(', '),"Online":String(n.onLine),
"Connection":c.effectiveType?(c.effectiveType+(c.downlink?' · '+c.downlink+' Mb/s':'')):'',"CPU cores":String(n.hardwareConcurrency||''),
"Memory":n.deviceMemory?n.deviceMemory+' GB':'',"Cookies enabled":String(n.cookieEnabled),"Service worker":String('serviceWorker' in n&&!!n.serviceWorker.controller),
"Secure context":String(window.isSecureContext),"Document mode":document.compatMode,"Charset":document.characterSet,"Content type":document.contentType,
"Referrer":document.referrer,"Last modified":document.lastModified,"Ready state":document.readyState,
"DOM elements":String(document.getElementsByTagName('*').length),"Scripts":String(document.scripts.length),"Stylesheets":String(document.styleSheets.length),
"Images":String(document.images.length),"Iframes":String(document.getElementsByTagName('iframe').length),
"Meta viewport":(document.querySelector('meta[name=viewport]')||{}).content||'(none)',"navigator.userAgent":n.userAgent});})()"""

/** The page, its certificate, the device as the page sees it, and the WebView. */
@Composable
fun PageInfoScreen(browser: BrowserController, onBack: () -> Unit) {
    val context = LocalContext.current
    var facts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        browser.evaluate(PAGE_FACTS) { result ->
            facts = runCatching {
                val inner = Json.decodeFromString(String.serializer(), result ?: "")
                (Json.parseToJsonElement(inner) as JsonObject).map { (k, v) -> k to v.jsonPrimitive.content }
            }.getOrDefault(emptyList())
        }
    }
    val cert = browser.certificate
    val dates = DateFormat.getDateTimeInstance()

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Page info", onBack, subtitle = browser.title)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Page")
            KeyValue("URL", browser.url)
            KeyValue("Title", browser.title, mono = false)
            Uri.parse(browser.url).let { u ->
                KeyValue("Origin", "${u.scheme}://${u.authority}")
            }
            SectionTitle("Connection")
            when {
                browser.sslOverridden -> KeyValue("Security", "Certificate error, proceeded anyway", mono = false)
                cert != null -> KeyValue("Security", "Encrypted (HTTPS)", mono = false)
                browser.url.startsWith("http://") -> KeyValue("Security", "Not encrypted (HTTP)", mono = false)
            }
            if (cert != null) {
                KeyValue("Issued to", cert.issuedTo.dName)
                KeyValue("Issued by", cert.issuedBy.dName)
                KeyValue("Valid", "${cert.validNotBeforeDate?.let(dates::format)} – ${cert.validNotAfterDate?.let(dates::format)}")
            }
            SectionTitle("As the page sees it")
            if (facts.isEmpty()) Text("Needs JavaScript to read.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            facts.forEach { (k, v) -> KeyValue(k, v) }
            SectionTitle("Browser")
            KeyValue("User agent sent", browser.userAgent)
            KeyValue("WebView's own user agent", browser.webViewUserAgent)
            val pkg = remember { WebViewCompat.getCurrentWebViewPackage(context) }
            KeyValue("WebView", pkg?.let { "${it.packageName} ${it.versionName}" } ?: "unknown")
            KeyValue("DevTools loading", if (browser.documentStartSupported) "Before the page's scripts (document start)" else "After the page commits (this WebView is too old for document-start scripts)", mono = false)
        }
    }
}

/** Bookmarks or history. */
@Composable
fun SitesScreen(browser: BrowserController, history: Boolean, onBack: () -> Unit) {
    val sites by (if (history) browser.library.history else browser.library.bookmarks).collectAsState()
    var filter by remember { mutableStateOf("") }
    val shown = remember(sites, filter) {
        val list = if (history) sites else sites.reversed()
        if (filter.isBlank()) list else list.filter { it.url.contains(filter, true) || it.title.contains(filter, true) }
    }
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize()) {
        PanelHeader(if (history) "History" else "Bookmarks", onBack, subtitle = "${sites.size}") {
            if (history) IconButton(onClick = { browser.library.clearHistory() }) { Icon(Icons.Default.DeleteSweep, "Clear history") }
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true, placeholder = { Text("Search") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (shown.isEmpty()) Text(if (history) "Nothing here yet." else "Tap ☆ in the address bar to bookmark a page.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { "${it.url}|${it.time}" }) { site: Site ->
                Row(Modifier.fillMaxWidth().clickable { browser.loadUrl(site.url); onBack() }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(site.title.ifBlank { site.url }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(site.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (history) Text(dates.format(Date(site.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (history) browser.library.removeHistory(site) else browser.library.removeBookmark(site.url) }) {
                        Icon(Icons.Default.Delete, "Remove")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
