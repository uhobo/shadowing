package com.shadowing.app.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

data class IngestedDocument(
    val title: String,
    val sourceType: String,
    val sentences: List<String>
)

class DocumentIngestor(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    fun ingestRawText(title: String, rawText: String): IngestedDocument {
        val cleanTitle = title.trim().ifEmpty { "Pasted Practice Text" }
        val sentences = SentenceSegmenter.segment(rawText)
        return IngestedDocument(
            title = cleanTitle,
            sourceType = "PASTE",
            sentences = sentences
        )
    }

    suspend fun ingestWebUrl(url: String): IngestedDocument = withContext(Dispatchers.IO) {
        val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val request = Request.Builder()
            .url(normalizedUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; ShadowingApp/1.0)")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to load URL (${response.code})")
            }
            val html = response.body?.string().orEmpty()
            val doc = Jsoup.parse(html, normalizedUrl)
            doc.select("script, style, nav, footer, header, aside, noscript").remove()
            val pageTitle = doc.title().ifBlank { normalizedUrl }
            val paragraphText = doc.select("article p, main p, p")
                .map { it.text().trim() }
                .filter { it.length > 20 }
                .joinToString("\n")
                .ifBlank { doc.body()?.text().orEmpty() }

            IngestedDocument(
                title = pageTitle,
                sourceType = "URL",
                sentences = SentenceSegmenter.segment(paragraphText)
            )
        }
    }

    suspend fun ingestUri(uri: Uri): IngestedDocument = withContext(Dispatchers.IO) {
        val displayName = resolveDisplayName(uri) ?: "Imported Book"
        val lower = displayName.lowercase()
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()

        val rawText = context.contentResolver.openInputStream(uri)?.use { stream ->
            when {
                lower.endsWith(".epub") || mime.contains("epub") -> {
                    extractTextFromEpubStream(stream)
                }
                lower.endsWith(".pdf") || mime.contains("pdf") -> {
                    extractTextFromPdfStream(stream)
                }
                else -> {
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        } ?: throw IOException("Unable to open selected file")

        val sourceType = when {
            lower.endsWith(".epub") -> "EPUB"
            lower.endsWith(".pdf") -> "PDF"
            else -> "TXT"
        }

        IngestedDocument(
            title = displayName.substringBeforeLast('.').ifBlank { displayName },
            sourceType = sourceType,
            sentences = SentenceSegmenter.segment(rawText)
        )
    }

    private fun extractTextFromPdfStream(inputStream: InputStream): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(inputStream).use { document ->
            val stripper = PDFTextStripper()
            return stripper.getText(document)
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    companion object {
        fun extractTextFromEpubStream(inputStream: InputStream): String {
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                        entries[normalizedName] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            val opfPath = containerXml?.let { xml ->
                val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                doc.selectFirst("rootfile")?.attr("full-path")?.replace('\\', '/')?.trimStart('/')
            } ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }

            if (opfPath != null && entries.containsKey(opfPath)) {
                val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') + "/" else ""
                val opfXml = entries.getValue(opfPath).toString(Charsets.UTF_8)
                val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())

                val manifestHrefById = opfDoc.select("manifest > item").associate { item ->
                    item.attr("id") to item.attr("href")
                }
                val spineIds = opfDoc.select("spine > itemref").map { it.attr("idref") }

                val chapterTexts = spineIds.mapNotNull { id ->
                    val href = manifestHrefById[id]?.substringBefore('#') ?: return@mapNotNull null
                    val fullEntryPath = normalizeZipPath(opfDir + href)
                    val htmlBytes = entries[fullEntryPath] ?: entries[href] ?: return@mapNotNull null
                    val chapterDoc = Jsoup.parse(ByteArrayInputStream(htmlBytes), "UTF-8", "")
                    chapterDoc.select("script, style").remove()
                    chapterDoc.body()?.text()?.trim()?.takeIf { it.isNotEmpty() }
                }

                if (chapterTexts.isNotEmpty()) {
                    return chapterTexts.joinToString("\n\n")
                }
            }

            // Fallback: concatenate all XHTML/HTML entries in alphabetical order
            return entries.entries
                .filter { it.key.endsWith(".xhtml", true) || it.key.endsWith(".html", true) || it.key.endsWith(".htm", true) }
                .sortedBy { it.key }
                .mapNotNull { (_, bytes) ->
                    val doc = Jsoup.parse(ByteArrayInputStream(bytes), "UTF-8", "")
                    doc.select("script, style").remove()
                    doc.body()?.text()?.trim()?.takeIf { it.isNotEmpty() }
                }
                .joinToString("\n\n")
        }

        private fun normalizeZipPath(path: String): String {
            val parts = mutableListOf<String>()
            for (segment in path.replace('\\', '/').split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                    else -> parts.add(segment)
                }
            }
            return parts.joinToString("/")
        }
    }
}
