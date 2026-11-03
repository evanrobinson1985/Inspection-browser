package com.inspectionbrowser.app.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

/** The open tabs as cards with a picture of each page: tap one to switch to it, ✕ to close it. */
@Composable
fun TabsScreen(browser: BrowserController, onBack: () -> Unit) {
    val grid = rememberLazyGridState()
    LaunchedEffect(Unit) { browser.tabs.indexOf(browser.current).takeIf { it >= 0 }?.let { grid.scrollToItem(it) } }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Tabs", onBack, subtitle = "${browser.tabs.size} open" + browser.tabs.count { it.incognito }.let { if (it > 0) " · $it private" else "" }) {
            IconButton(onClick = { browser.closeAll(); onBack() }) { Icon(Icons.Default.DeleteSweep, "Close all tabs") }
            IconButton(onClick = { browser.newTab(null, incognito = true); onBack() }) { Icon(Icons.Default.VisibilityOff, "New private tab") }
            IconButton(onClick = { browser.newTab(null); onBack() }) { Icon(Icons.Default.Add, "New tab") }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = grid,
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(browser.tabs, key = { it.id }) { tab -> TabCard(browser, tab, onOpen = { browser.select(tab); onBack() }) }
        }
    }
}

@Composable
private fun TabCard(browser: BrowserController, tab: BrowserController.Tab, onOpen: () -> Unit) {
    val selected = tab === browser.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            tab.incognito -> MaterialTheme.colorScheme.tertiaryContainer
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.padding(4.dp).fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = tab.favicon
                when {
                    tab.incognito -> Icon(Icons.Default.VisibilityOff, "Private", Modifier.size(16.dp))
                    icon != null -> Image(icon.asImageBitmap(), null, Modifier.size(16.dp))
                    else -> Icon(Icons.Default.Public, null, Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    tab.title.ifBlank { UrlInput.display(tab.url).ifBlank { "New tab" } },
                    style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (tab.errorCount > 0) Badge { Text(tab.errorCount.toString()) }
                IconButton(onClick = { browser.close(tab) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Close tab", Modifier.size(18.dp)) }
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                val picture = tab.thumbnail
                if (picture != null) {
                    Image(picture.asImageBitmap(), null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                } else {
                    // Restored tabs that haven't been opened yet have no picture.
                    Text(
                        UrlInput.display(tab.url).ifBlank { "New tab" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp),
                    )
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
                    if (!browser.current.incognito) MenuRow("Open in private tab") { act { browser.newTab(link, fromCurrent = true, incognito = true) } }
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
