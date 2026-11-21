package com.shadowing.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.shadowing.app.engine.ShadowingEngine
import com.shadowing.app.tts.HighlightRange
import com.shadowing.app.tts.WordRangeTracker

object SentenceHighlightFormatter {
    private val FailedWordColor = Color(0xFFFF6B6B)

    fun buildAnnotatedSentence(
        sentence: String,
        activeRange: HighlightRange?,
        failedWords: Set<String>
    ): AnnotatedString = buildAnnotatedString {
        append(sentence)

        if (failedWords.isNotEmpty()) {
            val wordRanges = WordRangeTracker.extractWordRanges(sentence)
            for (range in wordRanges) {
                val token = sentence.substring(range.start, range.end)
                val normalized = ShadowingEngine.normalizeWord(token)
                if (normalized in failedWords) {
                    addStyle(
                        style = SpanStyle(
                            color = FailedWordColor,
                            textDecoration = TextDecoration.Underline
                        ),
                        start = range.start,
                        end = range.end
                    )
                }
            }
        }

        if (activeRange != null) {
            val clamped = WordRangeTracker.clampRange(sentence, activeRange.start, activeRange.end)
            if (clamped != null) {
                addStyle(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold
                    ),
                    start = clamped.start,
                    end = clamped.end
                )
            }
        }
    }
}
