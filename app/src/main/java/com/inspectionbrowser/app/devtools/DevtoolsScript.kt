package com.inspectionbrowser.app.devtools

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The script that loads Eruda (console, elements, network, resources, sources, snippets …)
 * and its plugins into pages. Read from the app's assets once and kept.
 */
object DevtoolsScript {
    private val bundles = listOf(
        "eruda", "eruda-code", "eruda-timing", "eruda-monitor", "eruda-features", "eruda-touches",
        "eruda-orientation", "eruda-benchmark", "eruda-geolocation",
    )
    /** The globals the plugin bundles define, added as extra DevTools tabs. */
    private val plugins = listOf(
        "erudaCode", "erudaTiming", "erudaMonitor", "erudaFeatures", "erudaTouches",
        "erudaOrientation", "erudaBenchmark", "erudaGeolocation",
    )

    /** Eruda's built-in tools, by the name it opens them with. */
    val tools = listOf(
        "console" to "Console", "elements" to "Elements", "network" to "Network",
        "resources" to "Resources (storage, cookies)", "sources" to "Sources", "info" to "Info", "snippets" to "Snippets",
    )

    @Volatile private var code: Pair<String, String>? = null

    private fun code(context: Context): Pair<String, String> = code ?: synchronized(this) {
        code ?: run {
            val assets = context.applicationContext.assets
            fun read(name: String) = assets.open("devtools/$name").bufferedReader().use { it.readText() }
            val all = bundles.joinToString("\n;\n") { read("$it.js") }
            (read("boot.js") to all).also { code = it }
        }
    }

    /** The whole script for a page, with these choices. */
    fun build(context: Context, entryButton: Boolean, displaySize: Int): String {
        val (boot, all) = code(context)
        val config = buildJsonObject {
            put("entryButton", entryButton)
            put("displaySize", displaySize.coerceIn(30, 100))
            put("plugins", kotlinx.serialization.json.JsonArray(plugins.map { JsonPrimitive(it) }))
        }
        return boot.replace("/*@CONFIG@*/", config.toString()).replace("/*@BUNDLES@*/", all)
    }

    /** Opens or closes the panel; the page answers "open", "closed", "error:…" or "missing". */
    const val TOGGLE = "(function(){if(window.__ibDevtools)return __ibDevtools.toggle()?'open':'closed';" +
        "if(window.__ibError)return 'error:'+window.__ibError;return 'missing';})()"

    fun show(tool: String) = "(function(){if(!window.__ibDevtools)return 'missing';__ibDevtools.show(${JsonPrimitive(tool)});return 'open';})()"

    /** Closes the panel if it's open; answers true when it was. */
    const val HIDE_IF_OPEN = "(function(){return !!(window.__ibDevtools&&__ibDevtools.hideIfOpen());})()"
}
