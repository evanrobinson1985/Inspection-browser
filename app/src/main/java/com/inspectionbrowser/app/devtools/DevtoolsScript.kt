package com.inspectionbrowser.app.devtools

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The script that loads Eruda (console, elements, network, resources, sources, snippets …)
 * into pages, and the plugin tabs that are added when DevTools are first opened on a page.
 * Read from the app's assets once and kept.
 */
object DevtoolsScript {
    /** Extra tabs, loaded on demand: together they cost about as much to start as Eruda itself. */
    private val pluginBundles = listOf(
        "eruda-code", "eruda-timing", "eruda-monitor", "eruda-features", "eruda-touches",
        "eruda-orientation", "eruda-benchmark",
    )
    /** The globals the plugin bundles define, added as extra DevTools tabs. */
    private val plugins = listOf(
        "erudaCode", "erudaTiming", "erudaMonitor", "erudaFeatures", "erudaTouches",
        "erudaOrientation", "erudaBenchmark",
    )
    // Not eruda-geolocation: it loads its own Leaflet from unpkg into every page, which replaces a
    // page's own `L` (breaking map pages and their plugins) and draws its map over the page.

    /** Eruda's built-in tools, by the name it opens them with. */
    val tools = listOf(
        "console" to "Console", "elements" to "Elements", "network" to "Network",
        "resources" to "Resources (storage, cookies)", "sources" to "Sources", "info" to "Info", "snippets" to "Snippets",
    )

    private class Code(val boot: String, val eruda: String, val plugins: String)

    @Volatile private var code: Code? = null

    private fun code(context: Context): Code = code ?: synchronized(this) {
        code ?: run {
            val assets = context.applicationContext.assets
            fun read(name: String) = assets.open("devtools/$name").bufferedReader().use { it.readText() }
            Code(read("boot.js"), read("eruda.js"), pluginBundles.joinToString("\n;\n") { read("$it.js") }).also { code = it }
        }
    }

    /**
     * The script for every page. The plugin tabs come with it only when the floating button is
     * on (that button opens DevTools without the app knowing, so it can't add them later).
     */
    fun build(context: Context, entryButton: Boolean, displaySize: Int): String {
        val c = code(context)
        val config = buildJsonObject {
            put("entryButton", entryButton)
            put("displaySize", displaySize.coerceIn(30, 100))
            put("pluginsBundled", entryButton)
            put("plugins", kotlinx.serialization.json.JsonArray(plugins.map { JsonPrimitive(it) }))
        }
        val bundles = if (entryButton) c.eruda + "\n;\n" + c.plugins else c.eruda
        return c.boot.replace("/*@CONFIG@*/", config.toString()).replace("/*@BUNDLES@*/", bundles)
    }

    /** Adds the plugin tabs to a page's DevTools; does nothing if they're already there. */
    fun plugins(context: Context): String =
        "(function(){if(window.__ibPluginsLoaded||!window.__ibDevtools||!__ibDevtools.addPlugins)return;window.__ibPluginsLoaded=true;" +
            "var define,module,exports;\n" + code(context).plugins + "\n;__ibDevtools.addPlugins();})();"

    /** Whether the page still needs the plugin tabs. */
    const val NEEDS_PLUGINS = "(function(){return !!(window.__ibDevtools&&__ibDevtools.addPlugins&&!window.__ibPluginsLoaded);})()"

    /** Opens or closes the panel; the page answers "open", "closed", "error:…" or "missing". */
    const val TOGGLE = "(function(){if(window.__ibDevtools)return __ibDevtools.toggle()?'open':'closed';" +
        "if(window.__ibError)return 'error:'+window.__ibError;return 'missing';})()"

    fun show(tool: String) = "(function(){if(!window.__ibDevtools)return 'missing';__ibDevtools.show(${JsonPrimitive(tool)});return 'open';})()"

    /** Closes the panel if it's open; answers true when it was. */
    const val HIDE_IF_OPEN = "(function(){return !!(window.__ibDevtools&&__ibDevtools.hideIfOpen());})()"
}
