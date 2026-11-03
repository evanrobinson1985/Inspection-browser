package com.inspectionbrowser.app.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.browser.LongPressTarget
import com.inspectionbrowser.app.browser.UrlInput

/** The bottom bar's tab button: the number of open tabs in a box, like Chrome's. */
@Composable
fun TabCount(count: Int) {
    Surface(
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.size(22.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                // Chrome's way of saying "a lot" when the number won't fit.
                if (count > 99) ":D" else count.toString(),
                fontSize = if (count > 9) 10.sp else 12.sp, fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** The open tabs: tap one to switch to it, ✕ to close it. */
@Composable
fun TabsScreen(browser: BrowserController, onBack: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { browser.tabs.indexOf(browser.current).takeIf { it >= 0 }?.let { list.scrollToItem(it) } }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Tabs", onBack, subtitle = "${browser.tabs.size} open") {
            IconButton(onClick = { browser.closeAll(); onBack() }) { Icon(Icons.Default.DeleteSweep, "Close all tabs") }
            IconButton(onClick = { browser.newTab(null); onBack() }) { Icon(Icons.Default.Add, "New tab") }
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(browser.tabs, key = { it.id }) { tab ->
                val selected = tab === browser.current
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { browser.select(tab); onBack() },
                ) {
                    Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val icon = tab.favicon
                        if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(20.dp))
                        else Icon(Icons.Default.Public, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.title.ifBlank { UrlInput.display(tab.url).ifBlank { "New tab" } },
                                style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else null,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                UrlInput.display(tab.url), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (tab.errorCount > 0) Badge(Modifier.padding(start = 6.dp)) { Text(tab.errorCount.toString()) }
                        IconButton(onClick = { browser.close(tab) }) { Icon(Icons.Default.Close, "Close tab") }
                    }
                }
            }
        }
    }
}

/** What to do with a long-pressed link or image. */
@Composable
fun LongPressMenu(browser: BrowserController, target: LongPressTarget) {
    val context = LocalContext.current
    val dismiss = { browser.longPressed = null }
    fun act(action: () -> Unit) { dismiss(); action() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(target.link ?: target.image ?: "", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        },
        text = {
            Column {
                target.link?.let { link ->
                    MenuRow("Open in new tab") { act { browser.newTab(link, fromCurrent = true) } }
                    MenuRow("Open in background tab") { act { browser.newTab(link, background = true, fromCurrent = true) } }
                    MenuRow("Copy link") { act { copy(context, link, "Link") } }
                    MenuRow("Share link") {
                        act { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null)) }
                    }
                }
                target.image?.let { image ->
                    MenuRow("Open image in new tab") { act { browser.newTab(image, fromCurrent = true) } }
                    MenuRow("Copy image address") { act { copy(context, image, "Image address") } }
                    if (image.startsWith("http")) MenuRow("Download image") { act { browser.downloadImage(image) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}
