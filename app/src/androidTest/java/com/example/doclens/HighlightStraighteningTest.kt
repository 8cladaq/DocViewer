package com.example.doclens

import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HighlightStraighteningTest {

    @Test
    fun snapsWobblyHighlightToDetectedTextLine() {
        val annotation = Annotation.path(
            Annotation.Type.HIGHLIGHT, 0, Color.YELLOW, 14f
        )
        annotation.points.add(PointF(10f, 100f))
        annotation.points.add(PointF(40f, 104f))
        annotation.points.add(PointF(80f, 98f))
        annotation.points.add(PointF(120f, 103f))

        val lines = listOf(PdfPageView.TextLineBox(0, 20f, 99f, 110f, 112f))
        PdfPageView.straightenHighlight(annotation, lines, 0)

        assertEquals(18.5f, annotation.rect.left, 0.01f)
        assertEquals(98f, annotation.rect.top, 0.01f)
        assertEquals(111.5f, annotation.rect.right, 0.01f)
        assertEquals(113f, annotation.rect.bottom, 0.01f)
    }

    @Test
    fun straightensFreehandHighlightWhenNoTextIsDetected() {
        val annotation = Annotation.path(
            Annotation.Type.HIGHLIGHT, 0, Color.YELLOW, 14f
        )
        annotation.points.add(PointF(30f, 200f))
        annotation.points.add(PointF(70f, 205f))
        annotation.points.add(PointF(110f, 199f))

        PdfPageView.straightenHighlight(annotation, emptyList(), 0)

        assertEquals(30f, annotation.rect.left, 0.01f)
        assertEquals(110f, annotation.rect.right, 0.01f)
        assertTrue(annotation.rect.height() > 1f)
        assertEquals(202f, (annotation.rect.top + annotation.rect.bottom) / 2f, 0.01f)
    }
}
