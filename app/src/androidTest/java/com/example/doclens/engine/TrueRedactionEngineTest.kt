package com.example.doclens.engine

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TrueRedactionEngineTest {

    private lateinit var context: android.content.Context
    private lateinit var testContext: android.content.Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testContext = InstrumentationRegistry.getInstrumentation().context
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    @Test
    fun stripsTextAndAnnotationsThenPaintsBlackBox() = runBlocking {
        val repository = PdfDocumentRepository.create(context)
        repository.load(sourceWithMetadataAndAnnotation(), "redact.pdf")
        val zone = RectF(60f, 710f, 320f, 650f)

        repository.execute(TrueRedactionEngine().redact(0, listOf(zone)))
        val output = File(context.cacheDir, "true-redaction.pdf")
        repository.saveTo(output)

        load(output.readBytes()).use { document ->
            val text = PDFTextStripper().getText(document)
            assertFalse("Redacted text survived: $text", text.contains("DocLens - page one"))
            assertTrue("Page 2 text was damaged: $text", text.contains("DocLens - page two"))
            assertTrue(
                "Overlapping annotation survived",
                document.getPage(0).annotations.isEmpty(),
            )
            assertEquals("Secret title", document.documentInformation.title)

            val tokens = pageTokens(document, 0)
            assertBlackFill(tokens, zone)
        }
    }

    @Test
    fun sanitizeMetadataRemovesInfoAndXmp() = runBlocking {
        val repository = PdfDocumentRepository.create(context)
        repository.load(sourceWithMetadataAndAnnotation(), "redact-metadata.pdf")
        val zone = RectF(60f, 710f, 320f, 650f)

        repository.execute(TrueRedactionEngine().redact(0, listOf(zone), sanitizeMetadata = true))
        val output = File(context.cacheDir, "true-redaction-sanitized.pdf")
        repository.saveTo(output)

        load(output.readBytes()).use { document ->
            assertNull(document.documentInformation.title)
            assertNull(document.documentInformation.author)
            assertNull(document.documentCatalog.metadata)
        }
    }

    @Test
    fun stripsTextInsideFormXObjects() = runBlocking {
        val repository = PdfDocumentRepository.create(context)
        repository.load(sourceWithFormText(), "redact-form.pdf")
        val zone = RectF(60f, 710f, 400f, 650f)

        repository.execute(TrueRedactionEngine().redact(0, listOf(zone)))
        val output = File(context.cacheDir, "true-redaction-form.pdf")
        repository.saveTo(output)

        load(output.readBytes()).use { document ->
            val text = PDFTextStripper().getText(document)
            assertFalse("Form text survived: $text", text.contains("Form secret"))
            assertTrue("Outside text was damaged: $text", text.contains("Page text"))
        }
    }

    private fun sourceWithMetadataAndAnnotation(): ByteArray {
        val base = testContext.assets.open("doclens-test.pdf").use { it.readBytes() }
        return ByteArrayInputStream(base).use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                document.documentInformation.title = "Secret title"
                document.documentInformation.author = "Secret author"

                val metadata = PDMetadata(document)
                metadata.createOutputStream().use { stream ->
                    stream.write("<x:xmpmeta>secret</x:xmpmeta>".toByteArray())
                }
                document.documentCatalog.metadata = metadata

                document.getPage(0).annotations.add(
                    PDAnnotationText().apply {
                        rectangle = PDRectangle(70f, 670f, 220f, 30f)
                        contents = "secret annotation"
                    },
                )

                ByteArrayOutputStream().use { output ->
                    document.save(output)
                    output.toByteArray()
                }
            }
        }
    }

    private fun sourceWithFormText(): ByteArray {
        val document = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        document.addPage(page)

        val pageResources = PDResources()
        page.resources = pageResources
        val pageFont = pageResources.add(PDType1Font.HELVETICA)
        val pageText = "BT /${pageFont.name} 18 Tf 72 740 Td (Page text) Tj ET"

        val form = PDFormXObject(document)
        form.bBox = PDRectangle(0f, 0f, 612f, 792f)
        val formResources = PDResources()
        form.resources = formResources
        val formFont = formResources.add(PDType1Font.HELVETICA)
        form.contentStream.createOutputStream().use { stream ->
            stream.write("BT /${formFont.name} 28 Tf 72 680 Td (Form secret) Tj ET".toByteArray())
        }

        val formName = pageResources.add(form)
        val pageContent = "$pageText\nq 1 0 0 1 0 0 cm /${formName.name} Do Q"
        val stream = PDStream(document)
        stream.createOutputStream().use { it.write(pageContent.toByteArray()) }
        page.setContents(stream)

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

    private fun assertBlackFill(tokens: List<Any?>, zone: RectF) {
        assertNotNull("Missing black color operator", operatorIndex(tokens, "rg").takeIf { it >= 0 })
        assertTrue("Missing fill operator", operatorIndex(tokens, "f") >= 0)

        val rectIndex = operatorIndex(tokens, "re")
        assertTrue("Missing cover rectangle operator", rectIndex >= 4)
        assertClose(zone.left, number(tokens, rectIndex - 4), "rect left")
        assertClose(zone.bottom, number(tokens, rectIndex - 3), "rect bottom")
        assertClose(zone.right - zone.left, number(tokens, rectIndex - 2), "rect width")
        assertClose(zone.top - zone.bottom, number(tokens, rectIndex - 1), "rect height")

        val colorIndex = operatorIndex(tokens, "rg")
        assertTrue("Missing color operands", colorIndex >= 3)
        assertClose(0f, number(tokens, colorIndex - 3), "red")
        assertClose(0f, number(tokens, colorIndex - 2), "green")
        assertClose(0f, number(tokens, colorIndex - 1), "blue")
    }

    private fun operatorIndex(tokens: List<Any?>, name: String): Int =
        tokens.indexOfFirst { it is Operator && it.name == name }

    private fun number(tokens: List<Any?>, index: Int): Float =
        (tokens.getOrNull(index) as? COSNumber)?.floatValue()
            ?: throw AssertionError("Expected COSNumber at token $index")

    private fun assertClose(expected: Float, actual: Float, label: String) {
        assertTrue(
            "$label: expected $expected but was $actual",
            abs(expected - actual) <= 0.01f,
        )
    }
}
