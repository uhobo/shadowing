package com.shadowing.app.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SentenceSegmenterAndEpubTest {

    @Test
    fun test_segmenter_preservesAbbreviationsAndDecimalsInSingleSentence() {
        val raw = "Dr. Smith paid $3.50 in the U.S. today. He enjoyed the coffee very much."
        val sentences = SentenceSegmenter.segment(raw)
        assertEquals(
            listOf(
                "Dr. Smith paid $3.50 in the U.S. today.",
                "He enjoyed the coffee very much."
            ),
            sentences
        )

        // Multi-digit decimals and case-insensitive abbreviations
        val multiDecimal = "Mr. and mrs. Jones measured 123.456 meters vs. 78.90 meters today."
        val multiResult = SentenceSegmenter.segment(multiDecimal)
        assertEquals(
            listOf("Mr. and mrs. Jones measured 123.456 meters vs. 78.90 meters today."),
            multiResult
        )
    }

    @Test
    fun test_segmenter_enforcesMaxWordsPerSentenceOnLongSentences() {
        val longSentence = "When a particularly challenging sentence appears while you are driving on the highway, we split it into shorter pieces so it is easy to remember."
        val chunks = SentenceSegmenter.segment(longSentence, maxWordsPerChunk = 8)
        assertTrue("Expected multiple chunks", chunks.size >= 3)
        for (chunk in chunks) {
            val wordCount = chunk.split(Regex("\\s+")).size
            assertTrue("Expected chunk '$chunk' to have <= 8 words but had $wordCount", wordCount <= 8)
        }

        val rechunked = SentenceSegmenter.enforceMaxWords(listOf(longSentence), maxWords = 6, useDefaultFullSentences = false)
        for (chunk in rechunked) {
            val wordCount = chunk.split(Regex("\\s+")).size
            assertTrue("Expected rechunked '$chunk' to have <= 6 words but had $wordCount", wordCount <= 6)
        }

        // When useDefaultFullSentences = true (the original 28-word default), keep full sentence intact
        val defaultPreserved = SentenceSegmenter.enforceMaxWords(listOf(longSentence), maxWords = 6, useDefaultFullSentences = true)
        assertEquals(listOf(longSentence), defaultPreserved)
    }

    @Test
    fun test_enforceMaxWords_oneWordMode_splitsIntoCleanSingleWords() {
        val input = listOf("Focus on matching, rhythm — and stress!")
        val words = SentenceSegmenter.enforceMaxWords(
            sentences = input,
            maxWords = 1,
            useDefaultFullSentences = false
        )
        assertEquals(
            listOf("Focus", "on", "matching", "rhythm", "and", "stress"),
            words
        )
    }

    @Test
    fun test_parseEpubBytes_extractsChaptersInSpineOrder() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write("""
                <package><manifest>
                  <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="Chapter%202.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine>
                  <itemref idref="c1"/><itemref idref="c2"/>
                </spine></package>
            """.trimIndent().toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/ch1.xhtml"))
            zip.write("<html><body><p>First chapter sentence today.</p></body></html>".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("OEBPS/Chapter 2.xhtml"))
            zip.write("<html><body><p>Second chapter sentence here.</p></body></html>".toByteArray())
            zip.closeEntry()
        }

        val extracted = DocumentIngestor.extractTextFromEpubStream(baos.toByteArray().inputStream())
        val sentences = SentenceSegmenter.segment(extracted)
        assertEquals(
            listOf("First chapter sentence today.", "Second chapter sentence here."),
            sentences
        )
    }
}
