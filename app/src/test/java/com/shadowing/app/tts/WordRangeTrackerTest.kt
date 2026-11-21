package com.shadowing.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordRangeTrackerTest {

    @Test
    fun test_highlightRange_clampsOutOfBoundsTtsOffsetsWithoutCrashing() {
        val sentence = "Practice while driving."
        val clamped = WordRangeTracker.clampRange(sentence, rawStart = -4, rawEnd = 99)
        assertEquals(HighlightRange(0, sentence.length), clamped)

        val inverted = WordRangeTracker.clampRange(sentence, rawStart = 12, rawEnd = 5)
        assertNull(inverted)
    }

    @Test
    fun test_extractWordRanges_identifiesEachWordCharacterSpanForSyncBolding() {
        val sentence = "Shadowing builds fluency."
        val ranges = WordRangeTracker.extractWordRanges(sentence)
        assertEquals(3, ranges.size)
        assertEquals("Shadowing", sentence.substring(ranges[0].start, ranges[0].end))
        assertEquals("builds", sentence.substring(ranges[1].start, ranges[1].end))
        assertEquals("fluency.", sentence.substring(ranges[2].start, ranges[2].end))
    }
}
