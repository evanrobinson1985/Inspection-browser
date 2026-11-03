package com.inspectionbrowser.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.net.LoadedFile
import com.inspectionbrowser.app.net.Metric
import com.inspectionbrowser.app.net.PageSpeed
import com.inspectionbrowser.app.net.Rating
import com.inspectionbrowser.app.net.Requests
import com.inspectionbrowser.app.net.SpeedReport
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** How fast the page loaded and what made it heavy, from the page's own performance timings. */
@Composable
fun SpeedScreen(browser: BrowserController, onBack: () -> Unit) {
    var report by remember { mutableStateOf<SpeedReport?>(null) }
    var failed by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }

    LaunchedEffect(run) {
        report = null
        failed = false
        suspendCoroutine { c -> browser.evaluate(PageSpeed.OBSERVE) { c.resume(Unit) } }
        // The observers report buffered entries asynchronously; give them a moment.
        delay(400)
        val result = suspendCoroutine { c -> browser.evaluate(PageSpeed.COLLECT) { c.resume(it) } }
        report = PageSpeed.parse(result)
        failed = report == null
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Page speed", onBack, subtitle = browser.url) {
            IconButton(onClick = { run++ }) { Icon(Icons.Default.Refresh, "Measure again") }
        }
        val r = report
        when {
            failed -> Text(
                if (browser.settings.javaScript.value) "Couldn't read this page's timings." else "Page speed needs JavaScript (Settings).",
                Modifier.padding(16.dp), color = CodeColors.error(),
            )
            r == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    "Measured from this page's last load. Reload the page, then ↻ here, for a fresh run.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SectionTitle("Timings and size")
                r.metrics.forEach { MetricRow(it) }

                SectionTitle("Downloaded by type")
                r.byType.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(t.type, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${t.count} · ${formatBytes(t.transfer)}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
                if (r.unknownSizes > 0) Text(
                    "${r.unknownSizes} files from other sites don't share their size (no Timing-Allow-Origin), so the real total is higher.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                SectionTitle("Largest files")
                r.largest.forEach { FileRow(it, "${formatBytes(it.transfer)} sent · ${formatBytes(it.decoded)} unpacked") }
                SectionTitle("Slowest files")
                r.slowest.forEach { FileRow(it, "${it.millis.toInt()} ms") }

                SectionTitle("Page")
                r.domElements?.let { KeyValue("DOM elements", it.toString()) }
                r.jsHeap?.let { KeyValue("JavaScript memory in use", formatBytes(it)) }
                Box(Modifier.padding(bottom = 24.dp))
            }
        }
    }
}

@Composable
private fun MetricRow(m: Metric) {
    val color = when (m.rating) {
        Rating.Good -> CodeColors.ok()
        Rating.NeedsWork -> CodeColors.warn()
        Rating.Poor -> CodeColors.error()
        Rating.Info -> Color.Transparent
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(m.name, style = MaterialTheme.typography.bodyMedium)
            Text(m.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            when {
                m.value == null -> "—"
                m.unit == "ms" -> if (m.value >= 1000) "%.2f s".format(m.value / 1000) else "${m.value.toInt()} ms"
                m.unit == "bytes" -> formatBytes(m.value.toLong())
                m.name.contains("CLS") -> "%.3f".format(m.value)
                else -> m.value.toInt().toString()
            },
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun FileRow(f: LoadedFile, detail: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { copy(context, f.url, "URL") }.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(Requests.pathOf(f.url).substringAfterLast('/').ifBlank { f.url }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${Requests.host(f.url)} · ${f.type} · $detail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
