package com.shadowing.app.ui

import androidx.compose.ui.text.font.FontWeight
import com.shadowing.app.tts.HighlightRange
import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceHighlightFormatterTest {

    @Test
    fun test_buildAnnotatedSentence_appliesBoldSpanToCurrentlySpokenWordRange() {
        val sentence = "Practice English while driving."
        // "English" is indices 9..16
        val annotated = SentenceHighlightFormatter.buildAnnotatedSentence(
            sentence = sentence,
            activeRange = HighlightRange(9, 16),
            failedWords = setOf("driving")
        )

        assertEquals(sentence, annotated.text)
        val boldSpans = annotated.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldSpans.size)
        assertEquals(9, boldSpans.first().start)
        assertEquals(16, boldSpans.first().end)
    }
}
