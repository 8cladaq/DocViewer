package com.example.doclens.engine

import android.graphics.PointF
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PdfCoordinateMapperTest {

    private val baseGeometry = PageGeometry(
        mediaBox = PdfRect(10f, 20f, 622f, 812f),
        cropBox = PdfRect(50f, 60f, 400f, 500f),
        rotation = 0,
    )

    private val view = ViewMapping(
        viewWidthPx = 600f,
        viewHeightPx = 800f,
        zoom = 1.5f,
        panXPx = 37f,
        panYPx = -19f,
    )

    @Test
    fun pointMappingsRoundTripForEveryRotation() {
        val crop = baseGeometry.cropBox
        val samples = listOf(
            PointF(crop.lowerLeftX, crop.lowerLeftY),
            PointF(crop.upperRightX, crop.upperRightY),
            PointF(crop.lowerLeftX, crop.upperRightY),
            PointF(crop.upperRightX, crop.lowerLeftY),
            PointF((crop.lowerLeftX + crop.upperRightX) / 2f, (crop.lowerLeftY + crop.upperRightY) / 2f),
        )

        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = baseGeometry.copy(rotation = rotation)
            val mapper = PdfCoordinateMapper(geometry, view)
            for (pdf in samples) {
                val screen = mapper.pdfToScreen(pdf)
                assertTrue("Screen point must be on page for rotation $rotation", mapper.isOnPage(screen))
                val restored = mapper.screenToPdf(screen)
                assertClose(pdf.x, restored.x, "x, rotation $rotation")
                assertClose(pdf.y, restored.y, "y, rotation $rotation")
            }
        }
    }

    @Test
    fun rectanglesUseTopGreaterThanBottomInPdfSpace() {
        val crop = baseGeometry.cropBox
        val pdfRect = RectF(crop.lowerLeftX, crop.upperRightY, crop.upperRightX, crop.lowerLeftY)

        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = baseGeometry.copy(rotation = rotation)
            val displayRect = PdfCoordinateMapper.pdfRectToDisplay(geometry, pdfRect)

            assertClose(0f, displayRect.left, "display left, rotation $rotation")
            assertClose(0f, displayRect.top, "display top, rotation $rotation")
            assertClose(geometry.displayWidth, displayRect.right, "display right, rotation $rotation")
            assertClose(geometry.displayHeight, displayRect.bottom, "display bottom, rotation $rotation")

            val restored = PdfCoordinateMapper.displayRectToPdf(geometry, displayRect)
            assertClose(pdfRect.left, restored.left, "pdf left, rotation $rotation")
            assertClose(pdfRect.top, restored.top, "pdf top, rotation $rotation")
            assertClose(pdfRect.right, restored.right, "pdf right, rotation $rotation")
            assertClose(pdfRect.bottom, restored.bottom, "pdf bottom, rotation $rotation")
            assertTrue("PDF top must exceed bottom, rotation $rotation", restored.top > restored.bottom)
        }
    }

    private fun assertClose(expected: Float, actual: Float, label: String) {
        assertTrue(
            "$label: expected $expected but was $actual",
            abs(expected - actual) <= 0.01f,
        )
    }
}
