package com.inspectionbrowser.app.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.net.FetchResult
import com.inspectionbrowser.app.net.Fetcher
import com.inspectionbrowser.app.source.Highlighter
import com.inspectionbrowser.app.source.Kind
import com.inspectionbrowser.app.source.Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SourceMode(val label: String) { Raw("Server source"), Dom("Live DOM"), Headers("Headers") }

/** The page's source: as the server sent it, or the live DOM after scripts ran, highlighted. */
@Composable
fun SourceScreen(browser: BrowserController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageUrl = remember { browser.url }
    var mode by remember { mutableStateOf(SourceMode.Raw) }
    var raw by remember { mutableStateOf<FetchResult?>(null) }
    var rawError by remember { mutableStateOf<String?>(null) }
    var dom by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<Line>?>(null) }
    var wrap by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(pageUrl) {
        runCatching { Fetcher.fetch(pageUrl, browser.userAgent) }.onSuccess { raw = it }.onFailure { rawError = it.message ?: it.toString() }
    }
    LaunchedEffect(mode) {
        if (mode == SourceMode.Dom && dom == null) browser.renderedHtml { dom = it }
    }
    val text: String? = when (mode) {
        SourceMode.Raw -> raw?.body ?: raw?.let { "(${it.contentType ?: "binary"} response, ${formatBytes(it.bytes)}: nothing to show as text)" }
        SourceMode.Dom -> dom
        SourceMode.Headers -> null
    }
    LaunchedEffect(text) {
        lines = null
        val t = text ?: return@LaunchedEffect
        lines = withContext(Dispatchers.Default) {
            val ct = if (mode == SourceMode.Dom) "text/html" else raw?.contentType
            Highlighter.lines(t, Highlighter.spansFor(t, ct, pageUrl))
        }
    }
    val matches = remember(lines, query) {
        val l = lines
        if (query.length < 2 || l == null) emptyList() else l.indices.filter { l[it].text.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(matches, matchIndex) { matches.getOrNull(matchIndex)?.let { list.animateScrollToItem(it) } }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Source", onBack, subtitle = pageUrl) {
            IconButton(onClick = { searching = !searching }) { Icon(Icons.Default.Search, "Search") }
            IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) { Icon(Icons.AutoMirrored.Filled.WrapText, "Wrap lines") }
            IconButton(onClick = { text?.let { copy(context, it, "Source") } }) { Icon(Icons.Default.ContentCopy, "Copy") }
            IconButton(onClick = {
                text?.let {
                    // Very large pages are cut so the share sheet's limits aren't hit.
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it.take(500_000))
                    context.startActivity(Intent.createChooser(send, null))
                }
            }) { Icon(Icons.Default.Share, "Share") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) }) }
        }
        raw?.let { r ->
            Text(
                "${r.code} ${r.message} · ${r.contentType ?: "no content type"} · ${formatBytes(r.bytes)} · ${r.millis} ms" +
                    (lines?.let { " · ${it.lastOrNull()?.number ?: 0} lines" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (mode == SourceMode.Raw) Text(
            "Fetched again with this page's cookies and user agent; pages that came from a form POST may differ.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        if (searching) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it; matchIndex = 0 }, singleLine = true,
                placeholder = { Text("Find in source") }, modifier = Modifier.weight(1f),
            )
            Text(if (matches.isEmpty()) "0" else "${matchIndex + 1}/${matches.size}", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { if (matches.isNotEmpty()) matchIndex = (matchIndex - 1 + matches.size) % matches.size }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { if (matches.isNotEmpty()) matchIndex = (matchIndex + 1) % matches.size }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))

        val l = lines
        when {
            mode == SourceMode.Headers -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val r = raw
                when {
                    r != null -> ResponseSection(r.copy(body = null))
                    rawError != null -> Text("Couldn't fetch: $rawError", Modifier.padding(16.dp), color = CodeColors.error())
                    else -> Loading()
                }
            }
            mode == SourceMode.Raw && rawError != null ->
                Text("Couldn't fetch the source: $rawError\n\nTry Live DOM instead.", Modifier.padding(16.dp), color = CodeColors.error())
            l == null -> Loading()
            else -> SourceLines(l, wrap, query, matches.getOrNull(matchIndex), list)
        }
    }
}

@Composable
private fun SourceLines(lines: List<Line>, wrap: Boolean, query: String, current: Int?, list: androidx.compose.foundation.lazy.LazyListState) {
    val palette = Palette(
        CodeColors.tag(), CodeColors.attrName(), CodeColors.attrValue(), CodeColors.comment(),
        CodeColors.keyword(), CodeColors.number(), MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.tertiaryContainer,
    )
    val gutter = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val currentBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val hScroll = rememberScrollState()
    Box(if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(hScroll)) {
        LazyColumn(if (wrap) Modifier.fillMaxWidth() else Modifier, state = list) {
            itemsIndexed(lines) { index, line ->
                val styled = remember(line, query, palette) { annotate(line, palette, query) }
                val isCurrent = current == index
                Row(if (isCurrent) Modifier.background(currentBg) else Modifier) {
                    Text(
                        if (line.continued) "" else line.number.toString(),
                        modifier = Modifier.width(44.dp).padding(end = 6.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else gutter,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                    Text(
                        styled, softWrap = wrap,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                        modifier = if (wrap) Modifier.weight(1f) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private data class Palette(
    val tag: Color, val attrName: Color, val attrValue: Color, val comment: Color,
    val keyword: Color, val number: Color, val punct: Color, val match: Color,
)

private fun annotate(line: Line, p: Palette, query: String): AnnotatedString = buildAnnotatedString {
    append(line.text)
    for (s in line.spans) {
        if (s.end > line.text.length) continue
        val style = when (s.kind) {
            Kind.Tag -> SpanStyle(color = p.tag)
            Kind.AttrName -> SpanStyle(color = p.attrName)
            Kind.AttrValue, Kind.Str -> SpanStyle(color = p.attrValue)
            Kind.Comment -> SpanStyle(color = p.comment, fontStyle = FontStyle.Italic)
            Kind.Doctype -> SpanStyle(color = p.comment)
            Kind.Keyword -> SpanStyle(color = p.keyword)
            Kind.Number -> SpanStyle(color = p.number)
            Kind.Punct -> SpanStyle(color = p.punct)
        }
        addStyle(style, s.start, s.end)
    }
    if (query.length >= 2) {
        var i = line.text.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            addStyle(SpanStyle(background = p.match), i, i + query.length)
            i = line.text.indexOf(query, i + query.length, ignoreCase = true)
        }
    }
}
