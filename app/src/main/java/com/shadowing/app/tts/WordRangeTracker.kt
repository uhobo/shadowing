package com.shadowing.app.tts

data class HighlightRange(val start: Int, val end: Int)

object WordRangeTracker {
    fun clampRange(text: String, rawStart: Int, rawEnd: Int): HighlightRange? {
        if (text.isEmpty() || rawStart > rawEnd) return null
        val start = rawStart.coerceIn(0, text.length)
        val end = rawEnd.coerceIn(0, text.length)
        if (start >= end) return null
        return HighlightRange(start, end)
    }

    fun extractWordRanges(text: String): List<HighlightRange> {
        val regex = Regex("\\S+")
        return regex.findAll(text).map { match ->
            HighlightRange(match.range.first, match.range.last + 1)
        }.toList()
    }
}
