package com.inspectionbrowser.app.ui

import android.content.Intent
import android.net.http.SslError
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.browser.PageAsk
import com.inspectionbrowser.app.browser.UrlInput
import com.inspectionbrowser.app.devtools.DevtoolsScript
import com.inspectionbrowser.app.update.AppUpdater

/** The browser's own full-screen panels, over the page. */
enum class Panel { Requests, Source, Cookies, PageInfo, Settings, Bookmarks, History }

@Composable
fun BrowserScreen(browser: BrowserController, updater: AppUpdater, onExit: () -> Unit) {
    var panel by remember { mutableStateOf<Panel?>(null) }
    var finding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    BackHandler(enabled = panel == null) {
        when {
            editing -> { focus.clearFocus(); editing = false }
            finding -> { finding = false; browser.clearFind() }
            else -> browser.handleBack(onExit)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (finding) FindBar(browser, onClose = { finding = false; browser.clearFind() })
            else AddressBar(browser, editing, onEditing = { editing = it }, onTyped = { typed = it })
            if (browser.loading) LinearProgressIndicator(progress = { browser.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            else Spacer(Modifier.height(2.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(browser.webView) {
                    AndroidView(
                        factory = { browser.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (editing) Suggestions(browser, typed, onPick = { browser.loadUrl(it); focus.clearFocus(); editing = false })
            }
            BottomBar(browser, onPanel = { panel = it }, onFind = { finding = true })
        }

        browser.customView?.let { view ->
            BackHandler { browser.exitFullscreen() }
            key(view) {
                AndroidView(
                    factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                    modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                )
            }
        }

        panel?.let { p ->
            BackHandler { panel = null }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val close = { panel = null }
                    when (p) {
                        Panel.Requests -> RequestsScreen(browser, close)
                        Panel.Source -> SourceScreen(browser, close)
                        Panel.Cookies -> CookiesScreen(browser, close)
                        Panel.PageInfo -> PageInfoScreen(browser, close)
                        Panel.Settings -> SettingsScreen(browser, updater, close)
                        Panel.Bookmarks -> SitesScreen(browser, history = false, close)
                        Panel.History -> SitesScreen(browser, history = true, close)
                    }
                }
            }
        }

        browser.ask?.let { AskDialog(it) { allow, remember -> browser.answer(allow, remember) } }
    }
}

@Composable
private fun AddressBar(browser: BrowserController, editing: Boolean, onEditing: (Boolean) -> Unit, onTyped: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val secure = browser.url.startsWith("https://")

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = browser.favicon
                when {
                    editing -> Icon(Icons.Default.Public, null, Modifier.size(18.dp))
                    browser.sslOverridden -> Icon(Icons.Default.Warning, "Certificate error", Modifier.size(18.dp), tint = CodeColors.error())
                    icon != null -> Image(icon.asImageBitmap(), null, Modifier.size(18.dp))
                    secure -> Icon(Icons.Default.Lock, "Secure", Modifier.size(18.dp))
                    else -> Icon(Icons.Default.Public, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = if (editing) text else TextFieldValue(UrlInput.display(browser.url)),
                    onValueChange = { text = it; onTyped(it.text) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = {
                        browser.load(text.text)
                        focus.clearFocus()
                        onEditing(false)
                    }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester).onFocusChanged {
                        if (it.isFocused && !editing) {
                            text = TextFieldValue(browser.url, TextRange(0, browser.url.length))
                            onTyped("")
                            onEditing(true)
                        } else if (!it.isFocused && editing) onEditing(false)
                    },
                )
                if (editing && text.text.isNotEmpty()) {
                    IconButton(onClick = { text = TextFieldValue(""); onTyped("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Clear", Modifier.size(18.dp))
                    }
                }
            }
        }
        if (!editing) {
            val bookmarks by browser.library.bookmarks.collectAsState()
            val marked = bookmarks.any { it.url == browser.url }
            IconButton(onClick = { browser.library.toggleBookmark(browser.url, browser.title) }) {
                Icon(if (marked) Icons.Default.Star else Icons.Default.StarBorder, if (marked) "Remove bookmark" else "Bookmark")
            }
        }
    }
}

@Composable
private fun Suggestions(browser: BrowserController, query: String, onPick: (String) -> Unit) {
    // Matching bookmarks and history for what's typed; before typing, the most recent ones.
    val history by browser.library.history.collectAsState()
    val bookmarks by browser.library.bookmarks.collectAsState()
    val list = remember(query, history, bookmarks) {
        if (query.isBlank()) (bookmarks + history).distinctBy { it.url }.take(40) else browser.library.suggestions(query, limit = 12)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        LazyColumn {
            items(list, key = { it.url }) { site ->
                Column(Modifier.fillMaxWidth().clickable { onPick(site.url) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(site.title.ifBlank { site.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(site.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FindBar(browser: BrowserController, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.weight(1f).height(44.dp)) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it; browser.find(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { browser.findNext(true) }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Find in page", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    },
                )
                val (at, count) = browser.findResult
                if (query.isNotEmpty()) Text("$at/$count", style = MaterialTheme.typography.labelMedium)
            }
        }
        IconButton(onClick = { browser.findNext(false) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous") }
        IconButton(onClick = { browser.findNext(true) }) { Icon(Icons.Default.KeyboardArrowDown, "Next") }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
    }
}

@Composable
private fun BottomBar(browser: BrowserController, onPanel: (Panel) -> Unit, onFind: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var devMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val desktop by browser.settings.desktopMode.flow.collectAsState()

    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = browser::back, enabled = browser.canGoBack, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        IconButton(onClick = browser::forward, enabled = browser.canGoForward, modifier = Modifier.weight(1f)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward")
        }
        IconButton(onClick = { if (browser.loading) browser.stop() else browser.reload() }, modifier = Modifier.weight(1f)) {
            Icon(if (browser.loading) Icons.Default.Close else Icons.Default.Refresh, if (browser.loading) "Stop" else "Reload")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = { browser.toggleDevtools() }) {
                BadgedBox(badge = {
                    if (browser.errorCount > 0) Badge { Text(if (browser.errorCount > 99) "99+" else browser.errorCount.toString()) }
                }) { Icon(Icons.Default.BugReport, "DevTools") }
            }
        }
        IconButton(onClick = { onPanel(Panel.Requests) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.SwapVert, "Network requests")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun pick(action: () -> Unit) { menu = false; action() }
                DropdownMenuItem(text = { Text("Home") }, leadingIcon = { Icon(Icons.Default.Home, null) }, onClick = { pick(browser::goHome) })
                DropdownMenuItem(text = { Text("Find in page") }, leadingIcon = { Icon(Icons.Default.FindInPage, null) }, onClick = { pick(onFind) })
                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                    pick {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, browser.url)
                        context.startActivity(Intent.createChooser(send, null))
                    }
                })
                DropdownMenuItem(
                    text = { Text("Desktop site") },
                    leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                    trailingIcon = { Checkbox(checked = desktop, onCheckedChange = null) },
                    onClick = { pick { browser.settings.desktopMode.value = !desktop } },
                )
                HorizontalDivider()
                DropdownMenuItem(text = { Text("DevTools…") }, leadingIcon = { Icon(Icons.Default.Terminal, null) }, onClick = { pick { devMenu = true } })
                DropdownMenuItem(text = { Text("View page source") }, leadingIcon = { Icon(Icons.Default.Code, null) }, onClick = { pick { onPanel(Panel.Source) } })
                DropdownMenuItem(text = { Text("Network requests") }, leadingIcon = { Icon(Icons.Default.SwapVert, null) }, onClick = { pick { onPanel(Panel.Requests) } })
                DropdownMenuItem(text = { Text("Cookies") }, leadingIcon = { Icon(Icons.Default.Cookie, null) }, onClick = { pick { onPanel(Panel.Cookies) } })
                DropdownMenuItem(text = { Text("Page info") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { pick { onPanel(Panel.PageInfo) } })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Bookmarks") }, leadingIcon = { Icon(Icons.Default.Bookmarks, null) }, onClick = { pick { onPanel(Panel.Bookmarks) } })
                DropdownMenuItem(text = { Text("History") }, leadingIcon = { Icon(Icons.Default.History, null) }, onClick = { pick { onPanel(Panel.History) } })
                DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { pick { onPanel(Panel.Settings) } })
            }
            DropdownMenu(expanded = devMenu, onDismissRequest = { devMenu = false }) {
                DevtoolsScript.tools.forEach { (tool, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { devMenu = false; browser.toggleDevtools(tool) })
                }
            }
        }
    }
}

@Composable
private fun AskDialog(ask: PageAsk, answer: (allow: Boolean, remember: Boolean) -> Unit) {
    val (title, body, yes) = when (ask) {
        is PageAsk.Ssl -> Triple(
            "Certificate problem",
            "${sslProblem(ask.error)}\n\n${ask.error.url}\n\nIssued to: ${ask.error.certificate.issuedTo.dName}\n" +
                "Issued by: ${ask.error.certificate.issuedBy.dName}\n\nOnly carry on if you trust this server (a local dev server or a proxy you run).",
            "Proceed anyway",
        )
        is PageAsk.Media -> Triple(
            "Allow ${listOfNotNull("camera".takeIf { ask.camera }, "microphone".takeIf { ask.microphone }).joinToString(" and ")}?",
            "${ask.request.origin} wants to use your " +
                listOfNotNull("camera".takeIf { ask.camera }, "microphone".takeIf { ask.microphone }).joinToString(" and ") + ".",
            "Allow",
        )
        is PageAsk.Location -> Triple("Allow location?", "${ask.origin} wants to know your location.\n\nYour answer is remembered for this site (Settings ▸ Clear data ▸ Location answers to reset).", "Allow")
    }
    AlertDialog(
        // Tapping outside is "not now": nothing is remembered.
        onDismissRequest = { answer(false, false) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { answer(true, true) }) { Text(yes) } },
        dismissButton = { TextButton(onClick = { answer(false, true) }) { Text(if (ask is PageAsk.Ssl) "Go back" else "Block") } },
    )
}

private fun sslProblem(e: SslError): String = when (e.primaryError) {
    SslError.SSL_UNTRUSTED -> "The certificate isn't from an authority this phone trusts (self-signed?)."
    SslError.SSL_EXPIRED -> "The certificate has expired."
    SslError.SSL_IDMISMATCH -> "The certificate is for a different host name."
    SslError.SSL_NOTYETVALID -> "The certificate isn't valid yet (check the phone's clock)."
    SslError.SSL_DATE_INVALID -> "The certificate's dates are invalid."
    else -> "The certificate is invalid."
}
