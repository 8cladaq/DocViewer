package com.example.doclens.engine

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextPatchEngineTest {

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
    fun extractsSelectsAndVisuallyPatchesText() = runBlocking {
        val source = sourcePdf()
        val repository = PdfDocumentRepository.create(context)
        repository.load(source, "doclens-test.pdf")
        val engine = TextPatchEngine(context)

        val index = engine.extract(repository)
        val line = index.linesByPage[0].orEmpty().first { it.text.contains("DocLens") }
        val mapper = pageMapper()
        val tap = mapper.pdfToScreen(PointF(line.baseline.x + 8f, line.baseline.y + 4f))
        val selected = engine.selectLineAtScreen(index, 0, tap, mapper)

        assertNotNull(selected)
        assertEquals(line.text, selected!!.text)

        repository.execute(
            engine.replace(
                TextReplacement(
                    pageIndex = 0,
                    target = line,
                    replacement = "VISUAL_OK",
                    mode = TextPatchMode.VISUAL_PATCH,
                ),
            ),
        )
        val output = File(context.cacheDir, "text-visual.pdf")
        repository.saveTo(output)
        val text = extractText(output.readBytes())
        assertTrue("Expected new text in saved PDF, got: $text", text.contains("VISUAL_OK"))
    }

    @Test
    fun stripModeRemovesOriginalOperatorsAndAppendsReplacement() = runBlocking {
        val source = sourcePdf()
        val repository = PdfDocumentRepository.create(context)
        repository.load(source, "doclens-test.pdf")
        val engine = TextPatchEngine(context)

        val index = engine.extract(repository)
        val line = index.linesByPage[0].orEmpty().first { it.text.contains("DocLens") }

        repository.execute(
            engine.replace(
                TextReplacement(
                    pageIndex = 0,
                    target = line,
                    replacement = "STRIP_OK",
                    mode = TextPatchMode.CONTENT_STREAM_STRIP,
                ),
            ),
        )
        val output = File(context.cacheDir, "text-strip.pdf")
        repository.saveTo(output)
        val text = extractText(output.readBytes())

        assertFalse("Old text survived strip mode: $text", text.contains("DocLens - page one"))
        assertTrue("Expected replacement text in saved PDF, got: $text", text.contains("STRIP_OK"))
    }

    private fun sourcePdf(): ByteArray =
        testContext.assets.open("doclens-test.pdf").use { it.readBytes() }

    private fun pageMapper(): PdfCoordinateMapper {
        val geometry = PageGeometry(
            mediaBox = PdfRect(0f, 0f, 612f, 792f),
            cropBox = PdfRect(0f, 0f, 612f, 792f),
            rotation = 0,
        )
        return PdfCoordinateMapper(geometry, ViewMapping(612f, 792f))
    }

    private fun extractText(bytes: ByteArray): String =
        ByteArrayInputStream(bytes).use { input ->
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                PDFTextStripper().getText(document)
            }
        }
}
