package com.inspectionbrowser.app.source

/** What a stretch of source is, for colouring. */
enum class Kind { Tag, AttrName, AttrValue, Comment, Doctype, Keyword, Str, Number, Punct }

data class Span(val start: Int, val end: Int, val kind: Kind)

/** A line of source with its coloured stretches (offsets within the line). */
data class Line(val number: Int, val text: String, val spans: List<Span>, val continued: Boolean = false)

/** A small, forgiving highlighter for page source: HTML markup, and JS / CSS / JSON. */
object Highlighter {
    private val rawTextTags = setOf("script", "style", "textarea", "title", "xmp")

    fun html(src: String): List<Span> {
        val out = ArrayList<Span>()
        val n = src.length
        var i = 0
        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) break
            if (src.startsWith("<!--", lt)) {
                val end = src.indexOf("-->", lt + 4).let { if (it < 0) n else it + 3 }
                out += Span(lt, end, Kind.Comment); i = end; continue
            }
            if (src.startsWith("<!", lt) || src.startsWith("<?", lt)) {
                val end = src.indexOf('>', lt).let { if (it < 0) n else it + 1 }
                out += Span(lt, end, Kind.Doctype); i = end; continue
            }
            val closing = lt + 1 < n && src[lt + 1] == '/'
            var j = if (closing) lt + 2 else lt + 1
            val nameStart = j
            while (j < n && (src[j].isLetterOrDigit() || src[j] == '-' || src[j] == ':' || src[j] == '_')) j++
            if (j == nameStart || !src[nameStart].isLetter()) { i = lt + 1; continue }
            val name = src.substring(nameStart, j).lowercase()
            out += Span(lt, j, Kind.Tag)
            var selfClosed = false
            while (j < n) {
                val c = src[j]
                when {
                    c == '>' -> { out += Span(j, j + 1, Kind.Tag); j++; break }
                    c == '/' && j + 1 < n && src[j + 1] == '>' -> { out += Span(j, j + 2, Kind.Tag); j += 2; selfClosed = true; break }
                    c.isWhitespace() || c == '/' -> j++
                    c == '=' -> {
                        j++
                        while (j < n && src[j].isWhitespace()) j++
                        if (j >= n) break
                        val vs = j
                        if (src[j] == '"' || src[j] == '\'') {
                            val close = src.indexOf(src[j], j + 1)
                            j = if (close < 0) n else close + 1
                        } else {
                            while (j < n && !src[j].isWhitespace() && src[j] != '>') j++
                        }
                        out += Span(vs, j, Kind.AttrValue)
                    }
                    else -> {
                        val s = j
                        while (j < n && !src[j].isWhitespace() && src[j] != '=' && src[j] != '>' && src[j] != '/') j++
                        out += Span(s, j, Kind.AttrName)
                    }
                }
            }
            i = j
            if (!closing && !selfClosed && name in rawTextTags) {
                // Script and style bodies aren't markup; colour them as code up to their closing tag.
                val close = indexOfIgnoreCase(src, "</$name", i).let { if (it < 0) n else it }
                if (name == "script" || name == "style") code(src, i, close).let(out::addAll)
                i = close
            }
        }
        return out
    }

    private val keywords = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "new", "delete", "typeof", "instanceof", "in", "of", "this", "class", "extends", "super",
        "import", "export", "from", "default", "try", "catch", "finally", "throw", "async", "await", "yield",
        "true", "false", "null", "undefined", "void", "static", "get", "set", "important",
    )

    /** JS, CSS and JSON share enough lexically (strings, comments, numbers, words) for one pass. */
    fun code(src: String, from: Int = 0, to: Int = src.length): List<Span> {
        val out = ArrayList<Span>()
        var i = from
        while (i < to) {
            val c = src[i]
            when {
                c == '/' && i + 1 < to && src[i + 1] == '/' -> {
                    val end = src.indexOf('\n', i).let { if (it < 0 || it > to) to else it }
                    out += Span(i, end, Kind.Comment); i = end
                }
                c == '/' && i + 1 < to && src[i + 1] == '*' -> {
                    val end = src.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > to) to else it + 2 }
                    out += Span(i, end, Kind.Comment); i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < to && src[j] != c) { if (src[j] == '\\') j++; if (c != '`' && j < to && src[j] == '\n') break; j++ }
                    val end = minOf(j + 1, to)
                    out += Span(i, end, Kind.Str); i = end
                }
                c.isDigit() && (i == from || !(src[i - 1].isLetterOrDigit() || src[i - 1] == '_' || src[i - 1] == '$')) -> {
                    var j = i
                    while (j < to && (src[j].isLetterOrDigit() || src[j] == '.')) j++
                    out += Span(i, j, Kind.Number); i = j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i
                    while (j < to && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '$')) j++
                    if (src.substring(i, j) in keywords) out += Span(i, j, Kind.Keyword)
                    i = j
                }
                c in "{}()[];,:=<>+-*/!&|?" -> { out += Span(i, i + 1, Kind.Punct); i++ }
                else -> i++
            }
        }
        return out
    }

    private fun indexOfIgnoreCase(s: String, needle: String, from: Int): Int {
        var i = s.indexOf('<', from)
        while (i >= 0) {
            if (s.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i
            i = s.indexOf('<', i + 1)
        }
        return -1
    }

    /**
     * Splits [src] into numbered lines with their spans. Very long lines (minified code) are
     * cut into [chunk]-sized pieces so each row stays quick to draw.
     */
    fun lines(src: String, spans: List<Span>, chunk: Int = 2000): List<Line> {
        val out = ArrayList<Line>()
        var si = 0
        var start = 0
        var number = 1
        val sorted = spans.sortedBy { it.start }
        while (start <= src.length) {
            val nl = src.indexOf('\n', start).let { if (it < 0) src.length else it }
            var pieceStart = start
            var first = true
            do {
                val pieceEnd = minOf(nl, pieceStart + chunk)
                // Spans can cross lines (comments, strings); start from the first that reaches this piece.
                while (si < sorted.size && sorted[si].end <= pieceStart) si++
                val local = ArrayList<Span>()
                var k = si
                while (k < sorted.size && sorted[k].start < pieceEnd) {
                    val s = sorted[k]
                    val a = maxOf(s.start, pieceStart); val b = minOf(s.end, pieceEnd)
                    if (b > a) local += Span(a - pieceStart, b - pieceStart, s.kind)
                    k++
                }
                out += Line(number, src.substring(pieceStart, pieceEnd).trimEnd('\r'), local, continued = !first)
                first = false
                pieceStart = pieceEnd
            } while (pieceStart < nl)
            number++
            if (nl >= src.length) break
            start = nl + 1
        }
        return out
    }

    /** Which highlighter suits a response with this content type (or URL). */
    fun spansFor(src: String, contentType: String?, url: String): List<Span> {
        val ct = contentType.orEmpty().lowercase()
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            ct.contains("html") || ct.contains("xml") || ct.contains("svg") -> html(src)
            ct.contains("javascript") || ct.contains("json") || ct.contains("css") -> code(src)
            ext in setOf("js", "mjs", "css", "json", "map") -> code(src)
            ct.startsWith("text/plain") -> emptyList()
            else -> if (src.trimStart().startsWith("<")) html(src) else emptyList()
        }
    }
}
