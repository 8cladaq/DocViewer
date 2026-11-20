package com.example.doclens.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/** Progress for one completed OCR page. */
data class PdfOcrProgress(
    val completedPages: Int,
    val totalPages: Int,
    val pageIndex: Int,
)

/** All recognized text for one OCR run, in reading order. */
data class PdfOcrResult(
    val pages: List<OcrPageResult>,
    val pageCount: Int,
    val fullText: String,
    val wordCount: Int,
)

/**
 * Viewer-facing OCR pipeline.
 *
 * Recognition and PDF mutation run on [Dispatchers.IO]. Pages are rasterized by
 * Android's native [android.graphics.pdf.PdfRenderer] at roughly 250 DPI, read by
 * ML Kit, mapped back to crop-box-relative PDF points, and embedded as invisible
 * selectable text with PDFBox 2.0 APIs. The finished PDF is written to a temp file
 * in [Context.getCacheDir] before it is streamed to the caller's URI.
 */
class PdfOcrEngine(context: Context) {

    private val applicationContext = context.applicationContext ?: context
    private val recognizer = OfflineOcrLayerEngine(context)

    init {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(applicationContext)
        }
    }

    /** Java-friendly entry point; blocks the calling thread while running on IO. */
    @JvmOverloads
    fun recognize(
        pdfBytes: ByteArray,
        pageIndices: List<Int>? = null,
        dpi: Int = 250,
        onProgress: (PdfOcrProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): PdfOcrResult = runBlocking(Dispatchers.IO) {
        recognizeSuspend(pdfBytes, pageIndices, dpi, onProgress, isCancelled)
    }

    /** Suspendable core used by coroutine callers and tests. */
    suspend fun recognizeSuspend(
        pdfBytes: ByteArray,
        pageIndices: List<Int>?,
        dpi: Int,
        onProgress: (PdfOcrProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): PdfOcrResult = withContext(Dispatchers.IO) {
        require(pdfBytes.isNotEmpty()) { "PDF bytes must not be empty" }
        require(dpi in 144..400) { "OCR DPI must be between 144 and 400" }

        val pageCount = readPageCount(pdfBytes)
        val indices = resolvePageIndices(pageIndices, pageCount)
        val pages = mutableListOf<OcrPageResult>()

        indices.forEachIndexed { position, pageIndex ->
            if (isCancelled()) throw CancellationException("OCR cancelled")
            val result = recognizer.recognize(
                pdfBytes,
                OcrLayerOptions(dpi = dpi, pageIndices = listOf(pageIndex)),
            ).single()
            pages += result
            onProgress(PdfOcrProgress(position + 1, indices.size, pageIndex))
        }

        PdfOcrResult(
            pages = pages,
            pageCount = pageCount,
            fullText = composeText(pages),
            wordCount = pages.sumOf { it.wordCount },
        )
    }

    /** Embeds the recognized words into a copy of the PDF and returns the new bytes. */
    fun buildSearchablePdf(pdfBytes: ByteArray, result: PdfOcrResult): ByteArray =
        runBlocking(Dispatchers.IO) {
            val command = recognizer.createLayerCommandFromResults(result.pages)
            ByteArrayInputStream(pdfBytes).use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    command.execute(document)
                    ByteArrayOutputStream().use { output ->
                        document.save(output)
                        output.toByteArray()
                    }
                }
            }
        }

    /**
     * Writes [pdfBytes] to a temp file in cacheDir first, then streams that file
     * to [uri]. Returns the bytes so the viewer can reload immediately.
     */
    fun saveToUri(pdfBytes: ByteArray, uri: Uri, resolver: ContentResolver): ByteArray =
        runBlocking(Dispatchers.IO) {
            val temp = File.createTempFile("docviewer-ocr-", ".pdf", applicationContext.cacheDir)
            try {
                FileOutputStream(temp).use { output ->
                    output.write(pdfBytes)
                    output.fd.sync()
                }
                FileInputStream(temp).use { input ->
                    resolver.openOutputStream(uri).use { output ->
                        if (output == null) throw IllegalStateException("No output stream for $uri")
                        input.copyTo(output)
                        output.flush()
                    }
                }
                temp.readBytes()
            } finally {
                temp.delete()
            }
        }

    /** Releases the ML Kit recognizer. Do not call while recognition is running. */
    fun close() {
        recognizer.close()
    }

    private fun readPageCount(bytes: ByteArray): Int =
        ByteArrayInputStream(bytes).use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                document.numberOfPages
            }
        }

    private fun resolvePageIndices(requested: List<Int>?, pageCount: Int): List<Int> {
        val indices = (requested ?: (0 until pageCount).toList())
            .distinct()
            .sorted()
        require(indices.all { it in 0 until pageCount }) {
            "OCR page indexes must be within 0..${pageCount - 1}"
        }
        return indices
    }

    private fun composeText(pages: List<OcrPageResult>): String {
        val text = StringBuilder()
        for (page in pages.sortedBy { it.pageIndex }) {
            if (text.isNotEmpty()) text.append("\n\n")
            val words = page.words.sortedWith(
                compareByDescending<OcrWord> { it.pdfBounds.top }
                    .thenBy { it.pdfBounds.left },
            )
            var previousTop: Float? = null
            var previousBottom: Float? = null
            for (word in words) {
                val top = word.pdfBounds.top
                val previous = previousTop
                if (previous == null) {
                    text.append(word.text)
                } else {
                    val previousEnd = previousBottom ?: previous
                    val lineHeight = max(word.pdfBounds.height(), abs(top - previousEnd))
                    if (abs(top - previous) > max(3f, lineHeight * 0.6f)) {
                        text.append('\n')
                    } else {
                        text.append(' ')
                    }
                    text.append(word.text)
                }
                previousTop = top
                previousBottom = word.pdfBounds.bottom
            }
        }
        return text.toString().trim()
    }
}
