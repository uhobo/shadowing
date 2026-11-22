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

    const val DEFAULT_MAX_WORDS_PER_SENTENCE = 28

    fun segment(rawText: String, maxWordsPerChunk: Int = DEFAULT_MAX_WORDS_PER_SENTENCE): List<String> {
        if (rawText.isBlank()) return emptyList()
        val safeMax = maxWordsPerChunk.coerceIn(4, 50)

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
                rawSentences.addAll(splitLongSentenceIfNeeded(piece, safeMax))
            }
            start = end
            end = iterator.next()
        }

        return rawSentences
    }

    fun enforceMaxWords(
        sentences: List<String>,
        maxWords: Int,
        useDefaultFullSentences: Boolean = false
    ): List<String> {
        if (useDefaultFullSentences) {
            return sentences.flatMap { splitDefaultFullSentence(it, DEFAULT_MAX_WORDS_PER_SENTENCE) }
        }
        val safeMax = maxWords.coerceIn(4, 50)
        return sentences.flatMap { splitLongSentenceIfNeeded(it, safeMax) }
    }

    private fun splitDefaultFullSentence(sentence: String, maxWords: Int): List<String> {
        val words = sentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= maxWords) return listOf(sentence.trim())
        val clauses = sentence.split(Regex("(?<=[;—])\\s+"))
            .map { it.trim() }
            .filter { isSpeakableSentence(it) }
        if (clauses.size > 1) return clauses
        return words.chunked(maxWords).map { it.joinToString(" ") }
    }

    private fun isSpeakableSentence(candidate: String): Boolean {
        val alphaWords = candidate.split(Regex("\\s+")).count { token ->
            token.any { it.isLetter() }
        }
        return alphaWords >= 2
    }

    private fun splitLongSentenceIfNeeded(sentence: String, maxWords: Int): List<String> {
        val words = sentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size <= maxWords) return listOf(sentence.trim())

        // First try splitting at clause punctuation (; — ,) and then pack/chunk to <= maxWords
        val rawClauses = sentence.split(Regex("(?<=[;—,])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (rawClauses.size > 1) {
            val packed = mutableListOf<String>()
            var currentWords = mutableListOf<String>()

            for (clause in rawClauses) {
                val clauseWords = clause.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (currentWords.isNotEmpty() && currentWords.size + clauseWords.size <= maxWords) {
                    currentWords.addAll(clauseWords)
                } else {
                    if (currentWords.isNotEmpty()) {
                        packed.add(currentWords.joinToString(" "))
                        currentWords = mutableListOf()
                    }
                    if (clauseWords.size <= maxWords) {
                        currentWords.addAll(clauseWords)
                    } else {
                        clauseWords.chunked(maxWords).forEach { chunk ->
                            packed.add(chunk.joinToString(" "))
                        }
                    }
                }
            }
            if (currentWords.isNotEmpty()) {
                packed.add(currentWords.joinToString(" "))
            }
            return packed.filter { it.isNotBlank() }
        }

        return words.chunked(maxWords).map { it.joinToString(" ") }
    }
}
