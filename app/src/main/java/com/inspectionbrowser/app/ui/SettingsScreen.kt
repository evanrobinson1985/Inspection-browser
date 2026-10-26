package com.inspectionbrowser.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inspectionbrowser.app.browser.BrowserController
import com.inspectionbrowser.app.browser.UrlInput
import com.inspectionbrowser.app.browser.UserAgents
import com.inspectionbrowser.app.data.Pref
import com.inspectionbrowser.app.update.AppUpdater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(browser: BrowserController, updater: AppUpdater, onBack: () -> Unit) {
    val s = browser.settings
    var picking by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Developer tools")
            Toggle(s.devtools, "DevTools in every page", "Console, Elements, Network, Resources, Sources, Snippets and more (Eruda), loaded before the page's own scripts")
            Toggle(s.devtoolsButton, "Floating DevTools button", "Eruda's gear button on the page, as well as the toolbar button")
            val size by s.devtoolsSize.flow.collectAsState()
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("DevTools panel height: $size%", style = MaterialTheme.typography.bodyMedium)
                var dragging by remember { mutableStateOf(size.toFloat()) }
                Slider(value = dragging, onValueChange = { dragging = it }, onValueChangeFinished = { s.devtoolsSize.value = dragging.toInt() }, valueRange = 30f..100f)
            }
            Toggle(s.remoteDebugging, "Remote debugging (chrome://inspect)", "Full Chrome DevTools from a computer: plug in over USB with USB debugging on, then open chrome://inspect in Chrome")
            Toggle(s.disableCache, "Disable cache", "Always load from the network, like DevTools' \"Disable cache\"")
            Toggle(s.preserveLog, "Preserve request log", "Keep the request list across page loads")

            SectionTitle("Browsing")
            val ua by s.userAgent.flow.collectAsState()
            Choice("User agent", UserAgents.preset(ua).label) { picking = "ua" }
            if (ua == UserAgents.CUSTOM) TextSetting(s.customUserAgent, "Custom user agent")
            Toggle(s.desktopMode, "Desktop site", "Ask for desktop pages (a desktop user agent)")
            val engine by s.searchEngine.flow.collectAsState()
            Choice("Search engine", UrlInput.engine(engine).label) { picking = "search" }
            TextSetting(s.homePage, "Home page")
            Toggle(s.javaScript, "JavaScript", "DevTools need it on")
            Toggle(s.thirdPartyCookies, "Third-party cookies", null)
            Toggle(s.blockLocation, "Block location requests", "Sites can't ask for your location; otherwise each site asks once and your answer is remembered")
            Toggle(s.darkPages, "Darken pages in dark mode", "Pages without a dark theme of their own are darkened when the phone is in dark mode")
            val zoom by s.textZoom.flow.collectAsState()
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Text size: $zoom%", style = MaterialTheme.typography.bodyMedium)
                var dragging by remember { mutableStateOf(zoom.toFloat()) }
                Slider(value = dragging, onValueChange = { dragging = it }, onValueChangeFinished = { s.textZoom.value = (dragging / 10).toInt() * 10 }, valueRange = 50f..200f)
            }

            SectionTitle("Clear data")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = browser::clearCache) { Text("Cache") }
                OutlinedButton(onClick = browser::clearCookies) { Text("Cookies") }
                OutlinedButton(onClick = browser::clearStorage) { Text("Site storage") }
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { browser.library.clearHistory() }) { Text("History") }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = browser::clearSitePermissions) { Text("Location answers") }
            }

            SectionTitle("Updates")
            Updates(updater)
            Text(
                "DevTools by Eruda (MIT, github.com/liriliri/eruda) and its plugins.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    when (picking) {
        "ua" -> Picker("User agent", UserAgents.presets.map { it.id to it.label }, s.userAgent.value, onPick = { s.userAgent.value = it }, onDismiss = { picking = null })
        "search" -> Picker("Search engine", UrlInput.engines.map { it.id to it.label }, s.searchEngine.value, onPick = { s.searchEngine.value = it }, onDismiss = { picking = null })
    }
}

@Composable
private fun Toggle(pref: Pref<Boolean>, title: String, summary: String?) {
    val on by pref.flow.collectAsState()
    Row(Modifier.fillMaxWidth().clickable { pref.value = !on }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { pref.value = it })
    }
}

@Composable
private fun Choice(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TextSetting(pref: Pref<String>, label: String) {
    val saved by pref.flow.collectAsState()
    var text by remember(saved) { mutableStateOf(saved) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        if (text != saved) TextButton(onClick = { pref.value = text.trim() }) { Text("Save") }
    }
}

@Composable
private fun Picker(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(id); onDismiss() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = id == current, onClick = { onPick(id); onDismiss() })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Updates(updater: AppUpdater) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsState()
    val auto by updater.auto.collectAsState()
    val result by AppUpdater.lastResult.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Installed: build ${updater.installedBuild}", style = MaterialTheme.typography.bodyMedium)
        val st = state
        val busy = st is AppUpdater.State.Checking || st is AppUpdater.State.Downloading || st is AppUpdater.State.Installing
        Text(
            when (st) {
                is AppUpdater.State.Idle -> ""
                is AppUpdater.State.Checking -> "Checking GitHub…"
                is AppUpdater.State.UpToDate -> "Up to date (build ${st.build})."
                is AppUpdater.State.Available -> "Build ${st.release.build} is out."
                is AppUpdater.State.Downloading -> "Downloading build ${st.release.build}… ${(st.progress * 100).toInt()}%"
                is AppUpdater.State.Installing -> "Installing build ${st.release.build}… the app restarts when it's in."
                is AppUpdater.State.Failed -> st.message
            } + (result?.let { "\n$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Button(enabled = !busy, onClick = {
                if (!updater.canInstall()) context.startActivity(updater.allowInstallsIntent())
                else scope.launch { updater.check(install = true) }
            }) { Text(if (st is AppUpdater.State.Available) "Install update" else "Check for updates") }
        }
    }
    Row(Modifier.fillMaxWidth().clickable { updater.setAuto(!auto) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Update automatically", style = MaterialTheme.typography.bodyLarge)
            Text("Every few hours on Wi-Fi, from this app's GitHub releases", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = auto, onCheckedChange = { updater.setAuto(it) })
    }
}
