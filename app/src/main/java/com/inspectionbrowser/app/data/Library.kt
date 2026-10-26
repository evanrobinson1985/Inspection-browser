package com.inspectionbrowser.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class Site(val url: String, val title: String = "", val time: Long = 0L)

/** Bookmarks and history. */
class Library(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Site.serializer())

    private fun load(key: String): List<Site> =
        runCatching { json.decodeFromString(serializer, prefs.getString(key, null) ?: "[]") }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Site>) = prefs.edit().putString(key, json.encodeToString(serializer, list)).apply()

    private val _bookmarks = MutableStateFlow(load("bookmarks"))
    val bookmarks: StateFlow<List<Site>> = _bookmarks.asStateFlow()
    private val _history = MutableStateFlow(load("history"))
    /** Newest first. */
    val history: StateFlow<List<Site>> = _history.asStateFlow()

    fun isBookmarked(url: String) = _bookmarks.value.any { it.url == url }

    fun toggleBookmark(url: String, title: String) {
        val list = _bookmarks.value
        _bookmarks.value = if (list.any { it.url == url }) list.filterNot { it.url == url }
        else list + Site(url, title, System.currentTimeMillis())
        save("bookmarks", _bookmarks.value)
    }

    fun removeBookmark(url: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.url == url }
        save("bookmarks", _bookmarks.value)
    }

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("javascript:")) return
        val entry = Site(url, title, System.currentTimeMillis())
        val rest = _history.value.let { if (it.firstOrNull()?.url == url) it.drop(1) else it }
        _history.value = (listOf(entry) + rest).take(MAX_HISTORY)
        save("history", _history.value)
    }

    fun removeHistory(site: Site) {
        _history.value = _history.value.filterNot { it == site }
        save("history", _history.value)
    }

    fun clearHistory() { _history.value = emptyList(); save("history", emptyList()) }

    /** Bookmarks then history matching what's typed in the address bar, without repeats. */
    fun suggestions(query: String, limit: Int = 6): List<Site> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        fun matches(s: Site) = s.url.lowercase().contains(q) || s.title.lowercase().contains(q)
        return (_bookmarks.value.filter(::matches) + _history.value.filter(::matches)).distinctBy { it.url }.take(limit)
    }

    private companion object { const val MAX_HISTORY = 500 }
}
