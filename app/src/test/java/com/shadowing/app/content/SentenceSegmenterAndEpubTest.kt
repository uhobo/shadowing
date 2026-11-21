package com.shadowing.app.content

import org.junit.Assert.assertEquals
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
