package com.shadowing.app.content

import java.text.BreakIterator
import java.util.Locale

object SentenceSegmenter {
    private const val DOT_TOKEN = "__DOT__"

    private val ABBREVIATIONS = listOf(
        "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.",
        "vs.", "etc.", "e.g.", "i.e.", "U.S.", "U.K.", "St.", "Co.", "Inc."
    )

    private val DECIMAL_REGEX = Regex("(\\d)\\.(\\d)")

    fun segment(rawText: String, maxWordsPerChunk: Int = 28): List<String> {
        if (rawText.isBlank()) return emptyList()

        val normalized = rawText
            .replace("\r\n", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")

        var masked = normalized
        for (abbr in ABBREVIATIONS) {
            val stem = Regex.escape(abbr.removeSuffix("."))
            val abbrRegex = Regex("\\b($stem)\\.", RegexOption.IGNORE_CASE)
            masked = abbrRegex.replace(masked) { match ->
                "${match.groupValues[1]}$DOT_TOKEN"
            }
        }
        masked = DECIMAL_REGEX.replace(masked) { match ->
            "${match.groupValues[1]}$DOT_TOKEN${match.groupValues[2]}"
        }

        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(masked)

        val rawSentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val piece = masked.substring(start, end)
                .replace(DOT_TOKEN, ".")
                .trim()
            if (isSpeakableSentence(piece)) {
                rawSentences.addAll(splitLongSentenceIfNeeded(piece, maxWordsPerChunk))
            }
            start = end
            end = iterator.next()
        }

        return rawSentences
    }

    private fun isSpeakableSentence(candidate: String): Boolean {
        val alphaWords = candidate.split(Regex("\\s+")).count { token ->
            token.any { it.isLetter() }
        }
        return alphaWords >= 2
    }

    private fun splitLongSentenceIfNeeded(sentence: String, maxWords: Int): List<String> {
        val words = sentence.split(Regex("\\s+"))
        if (words.size <= maxWords) return listOf(sentence)

        val clauses = sentence.split(Regex("(?<=[;—])\\s+"))
            .map { it.trim() }
            .filter { isSpeakableSentence(it) }
        if (clauses.size > 1) return clauses

        return words.chunked(maxWords).map { it.joinToString(" ") }
    }
}
