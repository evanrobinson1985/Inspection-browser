package com.inspectionbrowser.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.net.FetchResult
import com.inspectionbrowser.app.net.Fetcher
import com.inspectionbrowser.app.net.LoggedRequest
import com.inspectionbrowser.app.net.Requests
import com.inspectionbrowser.app.net.ResourceType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Every request the page made (documents, scripts, styles, images, fonts, fetch/XHR …). */
@Composable
fun RequestsScreen(browser: BrowserController, onBack: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var types by remember { mutableStateOf(emptySet<ResourceType>()) }
    var failedOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LoggedRequest?>(null) }
    val preserve by browser.settings.preserveLog.flow.collectAsState()
    val all = browser.requests.items
    val shown by remember {
        derivedStateOf {
            all.filter { r ->
                (types.isEmpty() || r.type in types) &&
                    (!failedOnly || r.status != null || r.error != null) &&
                    (filter.isBlank() || r.url.contains(filter, ignoreCase = true))
            }
        }
    }

    selected?.let { r ->
        BackHandler { selected = null }
        RequestDetail(browser, r, onBack = { selected = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Requests", onBack, subtitle = "${shown.size} of ${all.size} · ${Requests.host(browser.url)}") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { browser.settings.preserveLog.value = !preserve }) {
                Checkbox(checked = preserve, onCheckedChange = { browser.settings.preserveLog.value = it })
                Text("Preserve", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { browser.requests.clear() }) { Icon(Icons.Default.DeleteSweep, "Clear") }
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Filter URLs") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = types.isEmpty() && !failedOnly, onClick = { types = emptySet(); failedOnly = false }, label = { Text("All") })
            ResourceType.entries.forEach { t ->
                val n = all.count { it.type == t }
                FilterChip(selected = t in types, onClick = { types = if (t in types) types - t else types + t }, label = { Text("${t.label} $n") })
            }
            FilterChip(selected = failedOnly, onClick = { failedOnly = !failedOnly }, label = { Text("Failed") })
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
        if (all.isEmpty()) {
            Text("No requests yet. Reload the page to see everything it loads.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val state = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(shown, key = { it.id }) { r -> RequestRow(r) { selected = r } }
        }
    }
}

@Composable
private fun RequestRow(r: LoggedRequest, onClick: () -> Unit) {
    val failed = r.error != null || (r.status ?: 0) >= 400
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(58.dp)) {
            Text(r.method, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text(
                when { r.error != null -> "ERR"; r.status != null -> r.status.toString(); else -> "" },
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                color = if (failed) CodeColors.error() else CodeColors.ok(),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                Requests.pathOf(r.url).let { if (it.length > 300) it.take(300) + "…" else it },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (failed) CodeColors.error() else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                Requests.host(r.url) + (if (r.mainFrame) " · page" else "") + (if (r.redirect) " · redirect" else "") + (r.error?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(r.type.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider()
}

private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun RequestDetail(browser: BrowserController, r: LoggedRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fetched by remember { mutableStateOf<FetchResult?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(Requests.pathOf(r.url).substringAfterLast('/').ifEmpty { Requests.host(r.url) }, onBack, subtitle = "${r.method} · ${r.type.label}")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { copy(context, r.url, "URL") }, label = { Text("Copy URL") })
                AssistChip(onClick = {
                    copy(context, Requests.curl(r, runCatching { android.webkit.CookieManager.getInstance().getCookie(r.url) }.getOrNull(), browser.userAgent), "cURL")
                }, label = { Text("Copy as cURL") })
                AssistChip(onClick = {
                    fetching = true; fetchError = null
                    scope.launch {
                        runCatching { Fetcher.fetch(r.url, browser.userAgent, r.method, r.headers) }
                            .onSuccess { fetched = it }.onFailure { fetchError = it.message ?: it.toString() }
                        fetching = false
                    }
                }, label = { Text(if (fetched == null) "Fetch response" else "Fetch again") })
                AssistChip(onClick = { browser.loadUrl(r.url); onBack() }, label = { Text("Open") })
                AssistChip(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
                }, label = { Text("Open in other app") })
            }
            SectionTitle("General")
            KeyValue("URL", r.url)
            KeyValue("Method", r.method)
            KeyValue("Status", when {
                r.error != null -> "Failed: ${r.error}"
                r.status != null -> "${r.status} ${r.reason.orEmpty()}"
                else -> "Loaded (WebView only reports the status of errors; use Fetch response to see it)"
            }, mono = false)
            KeyValue("Type", r.type.label + (if (r.mainFrame) " (main frame)" else ""), mono = false)
            KeyValue("Started", clock.format(Date(r.time)))
            KeyValue("User gesture", if (r.hasGesture) "Yes" else "No", mono = false)
            KeyValue("Redirect", if (r.redirect) "Yes" else "No", mono = false)
            if (r.url.contains('?')) {
                SectionTitle("Query string")
                Uri.parse(r.url).let { u -> u.queryParameterNames.forEach { n -> KeyValue(n, u.getQueryParameters(n).joinToString(", ")) } }
            }
            SectionTitle("Request headers")
            if (r.headers.isEmpty()) Text("None reported", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            r.headers.forEach { (k, v) -> KeyValue(k, v) }

            if (fetching) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text("  Fetching…")
            }
            fetchError?.let { Text("Fetch failed: $it", Modifier.padding(16.dp), color = CodeColors.error()) }
            fetched?.let { ResponseSection(it) }
        }
    }
}

@Composable
fun ResponseSection(f: FetchResult) {
    val context = LocalContext.current
    SectionTitle("Response (fetched again)")
    KeyValue("Status", "${f.code} ${f.message} · ${f.protocol} · ${f.millis} ms")
    if (f.finalUrl != f.url) KeyValue("Final URL", f.finalUrl)
    if (f.redirects.isNotEmpty()) KeyValue("Redirects", f.redirects.joinToString("\n"))
    KeyValue("Size", formatBytes(f.bytes) + (if (f.truncated) " (body cut at ${formatBytes(Fetcher.MAX_TEXT.toLong())})" else ""))
    SectionTitle("Response headers")
    f.headers.forEach { (k, v) -> KeyValue(k, v) }
    SectionTitle("Sent headers")
    f.requestHeaders.forEach { (k, v) -> KeyValue(k, v) }
    f.body?.let { body ->
        SectionTitle("Body")
        Row(Modifier.padding(horizontal = 12.dp)) {
            AssistChip(onClick = { copy(context, body, "Body") }, label = { Text("Copy body") })
        }
        SelectionContainer {
            Text(
                if (body.length > 20_000) body.take(20_000) + "\n… (${formatBytes(body.length.toLong())}; copy for the rest)" else body,
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
