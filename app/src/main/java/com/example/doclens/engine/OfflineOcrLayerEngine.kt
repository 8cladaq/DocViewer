package com.example.doclens.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Controls rasterization and page selection for the offline OCR layer. */
data class OcrLayerOptions(
    /** Target render resolution. ML Kit needs roughly 200-300 DPI for printed pages. */
    val dpi: Int = 260,
    /** Null means every page. Indexes are sorted and de-duplicated. */
    val pageIndices: List<Int>? = null,
) {
    init {
        require(dpi in 144..400) { "OCR render DPI must be between 144 and 400" }
        require(pageIndices?.all { it >= 0 } != false) { "Page indexes must be non-negative" }
    }
}

/** One recognized word in both source-image pixels and absolute PDF points. */
data class OcrWord(
    val text: String,
    /** Android-style pixel bounds: top is the smaller y. */
    val imageBounds: RectF,
    /** Normalized PDF bounds: top is the larger y. */
    val pdfBounds: RectF,
)

/** OCR results for one rendered page. */
data class OcrPageResult(
    val pageIndex: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val words: List<OcrWord>,
) {
    val wordCount: Int get() = words.size
}

/**
 * Adds a searchable, copy-pasteable text layer to scanned PDFs without a network
 * connection.
 *
 * Recognition is performed with ML Kit's bundled Latin text model on a high-DPI
 * `PdfRenderer` bitmap. Each word box is converted to absolute PDF points by
 * [PdfCoordinateMapper], then invisible text is appended to the original page
 * with `RenderingMode.NEITHER`. Horizontal scaling is calculated per word so the
 * invisible text advance matches the recognized bounding-box width.
 */
class OfflineOcrLayerEngine(context: Context) {

    private val applicationContext = context.applicationContext ?: context
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(applicationContext)
        }
    }

    /** Renders and recognizes words. No PDF bytes are modified. */
    suspend fun recognize(
        pdfBytes: ByteArray,
        options: OcrLayerOptions = OcrLayerOptions(),
    ): List<OcrPageResult> = withContext(Dispatchers.IO) {
        require(pdfBytes.isNotEmpty()) { "PDF bytes must not be empty" }

        val source = File.createTempFile("doclens-ocr-source", ".pdf", applicationContext.cacheDir)
        try {
            FileOutputStream(source).use { output -> output.write(pdfBytes) }
            ByteArrayInputStream(pdfBytes).use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                    val indices = resolvePageIndices(options.pageIndices, document.numberOfPages)
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        val renderer = PdfRenderer(descriptor)
                        try {
                            indices.map { pageIndex ->
                                recognizePage(renderer, document, pageIndex, options.dpi)
                            }
                        } finally {
                            renderer.close()
                        }
                    }
                }
            }
        } finally {
            source.delete()
        }
    }

    /**
     * Recognizes the document now and returns an undoable command that embeds the
     * invisible text layer. Recognition results are captured in the command, so
     * undo/redo does not require re-running ML Kit.
     */
    suspend fun createLayerCommand(
        pdfBytes: ByteArray,
        options: OcrLayerOptions = OcrLayerOptions(),
    ): EditCommand {
        val pages = recognize(pdfBytes, options)
        val wordCount = pages.sumOf { it.wordCount }
        return EditCommand("Add searchable OCR layer ($wordCount words)") { document ->
            writeInvisibleText(document, pages)
        }
    }

    /** Convenience overload for the active repository document. */
    suspend fun createLayerCommand(
        repository: PdfDocumentRepository,
        options: OcrLayerOptions = OcrLayerOptions(),
    ): EditCommand {
        val state = repository.currentState()
            ?: throw IllegalStateException("No PDF is loaded")
        return createLayerCommand(state.bytes, options)
    }

    /** Releases the ML Kit recognizer. Do not call while a command is executing. */
    fun close() {
        recognizer.close()
    }

    private suspend fun recognizePage(
        renderer: PdfRenderer,
        document: PDDocument,
        pageIndex: Int,
        dpi: Int,
    ): OcrPageResult {
        val page = renderer.openPage(pageIndex)
        val (width, height) = bitmapSize(page.width, page.height, dpi)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val visionText = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

            val geometry = PageGeometry.from(document.getPage(pageIndex))
            val mapper = PdfCoordinateMapper(
                geometry,
                ViewMapping(bitmap.width.toFloat(), bitmap.height.toFloat()),
            )
            val words = visionText.textBlocks
                .flatMap { block: Text.TextBlock -> block.lines }
                .flatMap { line: Text.Line -> line.elements }
                .mapNotNull { element -> mappedWord(element, mapper) }

            return OcrPageResult(pageIndex, bitmap.width, bitmap.height, words)
        } finally {
            bitmap.recycle()
            page.close()
        }
    }

    private fun mappedWord(element: Text.Element, mapper: PdfCoordinateMapper): OcrWord? {
        val text = element.text.trim()
        val pixelRect = element.boundingBox ?: return null
        if (text.isEmpty() || pixelRect.width() <= 0 || pixelRect.height() <= 0) return null

        val imageBounds = RectF(
            pixelRect.left.toFloat(),
            pixelRect.top.toFloat(),
            pixelRect.right.toFloat(),
            pixelRect.bottom.toFloat(),
        )
        val corners = listOf(
            mapper.screenToPdf(PointF(imageBounds.left, imageBounds.top)),
            mapper.screenToPdf(PointF(imageBounds.right, imageBounds.top)),
            mapper.screenToPdf(PointF(imageBounds.right, imageBounds.bottom)),
            mapper.screenToPdf(PointF(imageBounds.left, imageBounds.bottom)),
        )
        val raw = RectF(
            corners.minOf { it.x },
            corners.maxOf { it.y },
            corners.maxOf { it.x },
            corners.minOf { it.y },
        )

        val crop = mapper.geometry.cropBox
        val clipped = RectF(
            maxOf(raw.left, crop.lowerLeftX),
            minOf(raw.top, crop.upperRightY),
            minOf(raw.right, crop.upperRightX),
            maxOf(raw.bottom, crop.lowerLeftY),
        )
        if (clipped.right <= clipped.left || clipped.top <= clipped.bottom) return null
        return OcrWord(text, imageBounds, clipped)
    }

    private fun writeInvisibleText(document: PDDocument, pages: List<OcrPageResult>) {
        if (pages.isEmpty()) return

        applicationContext.assets.open(FONT_ASSET).use { stream ->
            val font = PDType0Font.load(document, stream, true)
            val (ascentUnits, descentUnits) = fontVerticalMetrics(font)
            val unitHeight = ascentUnits - descentUnits
            require(unitHeight > 0f) { "OCR font has invalid vertical metrics" }

            for (pageResult in pages) {
                if (pageResult.words.isEmpty()) continue
                require(pageResult.pageIndex in 0 until document.numberOfPages) {
                    "OCR page index ${pageResult.pageIndex} is outside the document"
                }
                val page = document.getPage(pageResult.pageIndex)
                PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { content ->
                    for (word in pageResult.words) {
                        val text = sanitizeForFont(word.text, font)
                        if (text.isEmpty()) continue

                        val boxWidth = word.pdfBounds.right - word.pdfBounds.left
                        val boxHeight = word.pdfBounds.top - word.pdfBounds.bottom
                        if (boxWidth <= 0f || boxHeight <= 0f) continue

                        val fontSize = boxHeight * 1000f / unitHeight
                        val naturalWidth = font.getStringWidth(text) / 1000f * fontSize
                        if (naturalWidth <= 0f) continue
                        val horizontalScaling = boxWidth / naturalWidth * 100f
                        if (!horizontalScaling.isFinite() || horizontalScaling <= 0f) continue
                        val baselineY = word.pdfBounds.bottom - descentUnits * fontSize / 1000f

                        content.saveGraphicsState()
                        content.beginText()
                        content.setRenderingMode(RenderingMode.NEITHER)
                        content.setFont(font, fontSize)
                        content.setHorizontalScaling(horizontalScaling)
                        content.newLineAtOffset(word.pdfBounds.left, baselineY)
                        content.showText(text)
                        content.endText()
                        content.restoreGraphicsState()
                    }
                }
            }
        }
    }

    private fun sanitizeForFont(raw: String, font: PDType0Font): String {
        val result = StringBuilder(raw.length)
        var pendingSpace = false
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = result.isNotEmpty()
                continue
            }
            val hasGlyph = try {
                font.hasGlyph(codePoint)
            } catch (ignored: Exception) {
                false
            }
            if (!hasGlyph) continue
            if (pendingSpace) {
                result.append(' ')
                pendingSpace = false
            }
            result.appendCodePoint(codePoint)
        }
        return result.toString()
    }

    private fun fontVerticalMetrics(font: PDType0Font): Pair<Float, Float> {
        val descriptor = font.fontDescriptor
        val box = font.boundingBox
        var ascent = descriptor?.ascent ?: box?.upperRightY ?: 800f
        var descent = descriptor?.descent ?: box?.lowerLeftY ?: -200f
        if (!ascent.isFinite() || ascent <= 0f) ascent = 800f
        if (!descent.isFinite() || descent >= 0f) descent = -200f
        return ascent to descent
    }

    private fun bitmapSize(pageWidthPt: Int, pageHeightPt: Int, dpi: Int): Pair<Int, Int> {
        var width = ceil(pageWidthPt * dpi / 72f).toInt().coerceAtLeast(1)
        var height = ceil(pageHeightPt * dpi / 72f).toInt().coerceAtLeast(1)
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > MAX_BITMAP_PIXELS) {
            val reduction = sqrt(MAX_BITMAP_PIXELS.toDouble() / pixelCount).toFloat()
            width = floor(width * reduction).toInt().coerceAtLeast(1)
            height = floor(height * reduction).toInt().coerceAtLeast(1)
        }
        return width to height
    }

    private fun resolvePageIndices(requested: List<Int>?, pageCount: Int): List<Int> {
        val indices = (requested ?: (0 until pageCount).toList()).distinct().sorted()
        require(indices.all { it in 0 until pageCount }) {
            "OCR page indexes must be within 0..${pageCount - 1}"
        }
        return indices
    }

    private companion object {
        const val FONT_ASSET = "fonts/Roboto-Regular.ttf"
        const val MAX_BITMAP_PIXELS = 24_000_000
    }
}
