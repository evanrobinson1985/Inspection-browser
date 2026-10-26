package com.inspectionbrowser.app.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterTest {
    private fun text(src: String, spans: List<Span>, kind: Kind) = spans.filter { it.kind == kind }.map { src.substring(it.start, it.end) }

    @Test fun marksTagsAttributesAndComments() {
        val src = """<!doctype html><a href="/x" data-n=3 hidden>hi</a><!-- note --><br/>"""
        val spans = Highlighter.html(src)
        assertEquals(listOf("<!doctype html>"), text(src, spans, Kind.Doctype))
        assertEquals(listOf("<a", ">", "</a", ">", "<br", "/>"), text(src, spans, Kind.Tag))
        assertEquals(listOf("href", "data-n", "hidden"), text(src, spans, Kind.AttrName))
        assertEquals(listOf("\"/x\"", "3"), text(src, spans, Kind.AttrValue))
        assertEquals(listOf("<!-- note -->"), text(src, spans, Kind.Comment))
    }

    @Test fun scriptBodiesAreCodeNotMarkup() {
        val src = "<script>if (a < b) { s = \"<p>\"; }</script><p>x</p>"
        val spans = Highlighter.html(src)
        assertEquals(listOf("<script", ">", "</script", ">", "<p", ">", "</p", ">"), text(src, spans, Kind.Tag))
        assertEquals(listOf("if"), text(src, spans, Kind.Keyword))
        assertEquals(listOf("\"<p>\""), text(src, spans, Kind.Str))
    }

    @Test fun codeTokens() {
        val src = "const x = 42; // hi\n/* block */ let s = 'a\\'b';"
        val spans = Highlighter.code(src)
        assertEquals(listOf("const", "let"), text(src, spans, Kind.Keyword))
        assertEquals(listOf("42"), text(src, spans, Kind.Number))
        assertEquals(listOf("// hi", "/* block */"), text(src, spans, Kind.Comment))
        assertEquals(listOf("'a\\'b'"), text(src, spans, Kind.Str))
    }

    @Test fun splitsLinesAndCarriesSpansAcross() {
        val src = "<a>\n<!-- one\ntwo -->\r\nend"
        val lines = Highlighter.lines(src, Highlighter.html(src))
        assertEquals(listOf("<a>", "<!-- one", "two -->", "end"), lines.map { it.text })
        assertEquals(listOf(1, 2, 3, 4), lines.map { it.number })
        assertEquals(Span(0, 8, Kind.Comment), lines[1].spans.single())
        assertEquals(Span(0, 7, Kind.Comment), lines[2].spans.single())
        assertTrue(lines[3].spans.isEmpty())
    }

    @Test fun cutsVeryLongLines() {
        val src = "x".repeat(25) + "\nshort"
        val lines = Highlighter.lines(src, emptyList(), chunk = 10)
        assertEquals(listOf(10, 10, 5, 5), lines.map { it.text.length })
        assertEquals(listOf(false, true, true, false), lines.map { it.continued })
        assertEquals(listOf(1, 1, 1, 2), lines.map { it.number })
        assertEquals(listOf(""), Highlighter.lines("", emptyList()).map { it.text })
    }
}
