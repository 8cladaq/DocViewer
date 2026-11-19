package com.example.doclens.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class OfflineOcrLayerEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    @Test
    fun addsInvisibleSearchableTextToImageOnlyScan() = runBlocking {
        val scannedBytes = imageOnlyScan()
        load(scannedBytes).use { before ->
            val initialText = PDFTextStripper().getText(before).trim()
            assertTrue("Scan already contained text: $initialText", initialText.isEmpty())
        }

        val engine = OfflineOcrLayerEngine(context)
        val options = OcrLayerOptions(dpi = 260, pageIndices = listOf(0))
        try {
            val crop = load(scannedBytes).use { document ->
                PdfRect.from(document.getPage(0).cropBox)
            }
            val recognized = engine.recognize(scannedBytes, options)
            assertTrue("No OCR words recognized", recognized.single().words.isNotEmpty())
            assertTrue(
                "Mapped word bounds must stay inside the crop box",
                recognized.single().words.all { word ->
                    word.pdfBounds.left >= crop.lowerLeftX - 1f &&
                        word.pdfBounds.right <= crop.upperRightX + 1f &&
                        word.pdfBounds.top <= crop.upperRightY + 1f &&
                        word.pdfBounds.bottom >= crop.lowerLeftY - 1f
                },
            )

            val repository = PdfDocumentRepository.create(context)
            repository.load(scannedBytes, "scan.pdf")
            repository.execute(engine.createLayerCommand(scannedBytes, options))
            val output = File(context.cacheDir, "ocr-searchable-scan.pdf")
            repository.saveTo(output)
            val savedBytes = output.readBytes()
            val savedPdfText = String(savedBytes, Charsets.ISO_8859_1)
            assertTrue(
                "Embedded font is missing a ToUnicode map for copy/paste",
                savedPdfText.contains("/ToUnicode"),
            )

            load(savedBytes).use { document ->
                val text = PDFTextStripper().getText(document)
                assertTrue(
                    "Invisible OCR text was not extractable: $text",
                    text.contains("DOCLENS", ignoreCase = true) ||
                        text.contains("OFFLINE", ignoreCase = true) ||
                        text.contains("SEARCHABLE", ignoreCase = true),
                )

                val tokens = pageTokens(document, 0)
                val renderingModes = operatorOperands(tokens, "Tr")
                assertFalse(
                    "No invisible rendering mode was written",
                    renderingModes.none { it.size == 1 && it[0].asInt() == 3 },
                )
                assertTrue(
                    "No horizontal scaling was written",
                    operatorOperands(tokens, "Tz").isNotEmpty(),
                )
            }
        } finally {
            engine.close()
        }
    }

    private fun imageOnlyScan(): ByteArray {
        val bitmap = Bitmap.createBitmap(1700, 2200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            textSize = 96f
        }
        canvas.drawText("DOCLENS", 140f, 320f, paint)
        canvas.drawText("OFFLINE OCR", 140f, 500f, paint)
        canvas.drawText("SEARCHABLE SCAN", 140f, 680f, paint)

        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val document = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        document.addPage(page)
        val image = PDImageXObject.createFromByteArray(document, png, "scan")
        PDPageContentStream(document, page, AppendMode.OVERWRITE, true, true).use { content ->
            content.drawImage(image, 0f, 0f, page.cropBox.width, page.cropBox.height)
        }
        return ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }.also { document.close() }
    }

    private fun load(bytes: ByteArray): PDDocument =
        PDDocument.load(ByteArrayInputStream(bytes), MemoryUsageSetting.setupTempFileOnly())

    private fun pageTokens(document: PDDocument, pageIndex: Int): List<Any?> {
        val parser = PDFStreamParser(document.getPage(pageIndex))
        parser.parse()
        return parser.tokens
    }

    private fun operatorOperands(tokens: List<Any?>, name: String): List<List<Any?>> {
        val result = mutableListOf<List<Any?>>()
        var operandStart = 0
        tokens.forEachIndexed { index, token ->
            if (token is Operator && token.name == name) {
                result += tokens.subList(operandStart, index).toList()
            }
            if (token is Operator) operandStart = index + 1
        }
        return result
    }

    private fun Any?.asInt(): Int = (this as? COSNumber)?.intValue() ?: -1
}
