package com.example.doclens.engine

import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlin.math.abs
import kotlin.math.min

/** A normalized rectangle in unrotated PDF user space (bottom-left origin, 72 dpi points). */
data class PdfRect(
    val lowerLeftX: Float,
    val lowerLeftY: Float,
    val upperRightX: Float,
    val upperRightY: Float,
) {
    init {
        require(lowerLeftX.isFinite() && lowerLeftY.isFinite()) { "PDF rect origin must be finite" }
        require(upperRightX.isFinite() && upperRightY.isFinite()) { "PDF rect end must be finite" }
        require(width > 0f && height > 0f) { "PDF rect must have positive dimensions" }
    }

    val width: Float get() = upperRightX - lowerLeftX
    val height: Float get() = upperRightY - lowerLeftY

    companion object {
        fun from(rectangle: PDRectangle): PdfRect {
            val x1 = minOf(rectangle.lowerLeftX, rectangle.upperRightX)
            val y1 = minOf(rectangle.lowerLeftY, rectangle.upperRightY)
            val x2 = maxOf(rectangle.lowerLeftX, rectangle.upperRightX)
            val y2 = maxOf(rectangle.lowerLeftY, rectangle.upperRightY)
            return PdfRect(x1, y1, x2, y2)
        }
    }
}

/** Immutable page geometry used by the mapper. Rotation is clockwise, as in PDF. */
data class PageGeometry(
    val mediaBox: PdfRect,
    val cropBox: PdfRect,
    val rotation: Int,
) {
    val normalizedRotation: Int = normalize(rotation)

    val cropWidth: Float get() = cropBox.width
    val cropHeight: Float get() = cropBox.height

    val displayWidth: Float
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) cropHeight else cropWidth

    val displayHeight: Float
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) cropWidth else cropHeight

    init {
        require(normalizedRotation == 0 || normalizedRotation == 90 ||
            normalizedRotation == 180 || normalizedRotation == 270) {
            "Page rotation must resolve to 0/90/180/270 degrees"
        }
    }

    companion object {
        fun from(page: PDPage): PageGeometry {
            val media = PdfRect.from(page.mediaBox)
            val crop = PdfRect.from(page.cropBox)
            return PageGeometry(media, crop, page.rotation)
        }

        private fun normalize(value: Int): Int {
            val wrapped = ((value % 360) + 360) % 360
            return when (wrapped) {
                0, 90, 180, 270 -> wrapped
                else -> ((wrapped + 45) / 90 * 90) % 360
            }
        }
    }
}

/** View state supplied by the Android `PdfRenderer` host view. */
data class ViewMapping(
    val viewWidthPx: Float,
    val viewHeightPx: Float,
    val zoom: Float = 1f,
    val panXPx: Float = 0f,
    val panYPx: Float = 0f,
) {
    init {
        require(viewWidthPx > 0f && viewHeightPx > 0f) { "View dimensions must be positive" }
        require(zoom > 0f && zoom.isFinite()) { "Zoom must be a positive finite number" }
        require(panXPx.isFinite() && panYPx.isFinite()) { "Pan must be finite" }
    }
}

/**
 * Converts between Android view pixels (top-left origin, zoom/pan applied) and
 * absolute PDF user-space points (bottom-left origin, 72 dpi). Crop-box offsets
 * and page rotation are applied in both directions.
 */
class PdfCoordinateMapper(
    val geometry: PageGeometry,
    val view: ViewMapping,
) {
    private val fitScalePxPerPt = min(
        view.viewWidthPx / geometry.displayWidth,
        view.viewHeightPx / geometry.displayHeight,
    )
    private val scalePxPerPt = fitScalePxPerPt * view.zoom
    private val originXPx =
        (view.viewWidthPx - geometry.displayWidth * scalePxPerPt) / 2f + view.panXPx
    private val originYPx =
        (view.viewHeightPx - geometry.displayHeight * scalePxPerPt) / 2f + view.panYPx

    init {
        require(fitScalePxPerPt.isFinite() && fitScalePxPerPt > 0f) {
            "Page geometry must produce a positive display scale"
        }
    }

    /** Screen px -> rotated, top-left display points of the page image. */
    fun screenToDisplay(screen: PointF): PointF = PointF(
        (screen.x - originXPx) / scalePxPerPt,
        (screen.y - originYPx) / scalePxPerPt,
    )

    /** Rotated, top-left display points -> screen px. */
    fun displayToScreen(display: PointF): PointF = PointF(
        originXPx + display.x * scalePxPerPt,
        originYPx + display.y * scalePxPerPt,
    )

    /**
     * Rotated display points -> absolute PDF user-space points.
     * `display` is relative to the crop box top-left after rotation.
     */
    fun displayToPdf(display: PointF): PointF = displayToPdf(geometry, display)

    /** Absolute PDF user-space points -> rotated, top-left display points. */
    fun pdfToDisplay(pdf: PointF): PointF = pdfToDisplay(geometry, pdf)

    /** Screen px -> absolute PDF user-space points. */
    fun screenToPdf(screen: PointF): PointF = displayToPdf(screenToDisplay(screen))

    /** Absolute PDF user-space points -> screen px. */
    fun pdfToScreen(pdf: PointF): PointF = displayToScreen(pdfToDisplay(pdf))

    /** Screen-space bounds of the displayed crop box. */
    fun pageScreenBounds(): RectF = RectF(
        originXPx,
        originYPx,
        originXPx + geometry.displayWidth * scalePxPerPt,
        originYPx + geometry.displayHeight * scalePxPerPt,
    )

    /** True when the screen point lies within the displayed crop box. */
    fun isOnPage(screen: PointF): Boolean {
        val display = screenToDisplay(screen)
        return display.x >= 0f && display.x <= geometry.displayWidth &&
            display.y >= 0f && display.y <= geometry.displayHeight
    }

    /** Clamps a screen point to the displayed crop box and returns the PDF point. */
    fun clampScreenToPdf(screen: PointF): PointF {
        val display = screenToDisplay(screen)
        val clamped = PointF(
            display.x.coerceIn(0f, geometry.displayWidth),
            display.y.coerceIn(0f, geometry.displayHeight),
        )
        return displayToPdf(clamped)
    }

    /** Current scale in pixels per PDF point, including zoom. */
    fun scalePxPerPoint(): Float = scalePxPerPt

    companion object {
        /** Display top-left points -> absolute PDF points, independent of any view scale. */
        fun displayToPdf(geometry: PageGeometry, display: PointF): PointF {
            val crop = geometry.cropBox
            val cropW = geometry.cropWidth
            val cropH = geometry.cropHeight
            val xInCrop: Float
            val yInCrop: Float
            when (geometry.normalizedRotation) {
                0 -> {
                    xInCrop = display.x
                    yInCrop = cropH - display.y
                }
                90 -> {
                    xInCrop = display.y
                    yInCrop = display.x
                }
                180 -> {
                    xInCrop = cropW - display.x
                    yInCrop = display.y
                }
                270 -> {
                    xInCrop = cropW - display.y
                    yInCrop = cropH - display.x
                }
                else -> throw IllegalStateException("Unreachable normalized rotation")
            }
            return PointF(crop.lowerLeftX + xInCrop, crop.lowerLeftY + yInCrop)
        }

        /** Absolute PDF points -> display top-left points, independent of any view scale. */
        fun pdfToDisplay(geometry: PageGeometry, pdf: PointF): PointF {
            val crop = geometry.cropBox
            val xInCrop = pdf.x - crop.lowerLeftX
            val yInCrop = pdf.y - crop.lowerLeftY
            val cropW = geometry.cropWidth
            val cropH = geometry.cropHeight
            return when (geometry.normalizedRotation) {
                0 -> PointF(xInCrop, cropH - yInCrop)
                90 -> PointF(yInCrop, xInCrop)
                180 -> PointF(cropW - xInCrop, yInCrop)
                270 -> PointF(cropH - yInCrop, cropW - xInCrop)
                else -> throw IllegalStateException("Unreachable normalized rotation")
            }
        }

        /** Converts a display-space rectangle to a normalized PDF user-space rectangle. */
        fun displayRectToPdf(geometry: PageGeometry, display: RectF): RectF {
            val corners = listOf(
                displayToPdf(geometry, PointF(display.left, display.top)),
                displayToPdf(geometry, PointF(display.right, display.top)),
                displayToPdf(geometry, PointF(display.right, display.bottom)),
                displayToPdf(geometry, PointF(display.left, display.bottom)),
            )
            // PDF y increases upward, so the display "top" is the larger PDF y.
            return RectF(
                corners.minOf { it.x }, corners.maxOf { it.y },
                corners.maxOf { it.x }, corners.minOf { it.y },
            )
        }

        /** Converts a PDF user-space rectangle to a display-space rectangle. */
        fun pdfRectToDisplay(geometry: PageGeometry, pdf: RectF): RectF {
            val corners = listOf(
                pdfToDisplay(geometry, PointF(pdf.left, pdf.top)),
                pdfToDisplay(geometry, PointF(pdf.right, pdf.top)),
                pdfToDisplay(geometry, PointF(pdf.right, pdf.bottom)),
                pdfToDisplay(geometry, PointF(pdf.left, pdf.bottom)),
            )
            // Display y increases downward, so the PDF "top" is the smaller display y.
            return RectF(
                corners.minOf { it.x }, corners.minOf { it.y },
                corners.maxOf { it.x }, corners.maxOf { it.y },
            )
        }

        /** Convenience factory for a PDFBox page and the current Android view state. */
        fun fromPage(page: PDPage, view: ViewMapping): PdfCoordinateMapper =
            PdfCoordinateMapper(PageGeometry.from(page), view)

        /** Sensible zoom-independent mapper for hit testing in PDF points. */
        fun forPageBox(page: PDPage, viewWidthPx: Float, viewHeightPx: Float): PdfCoordinateMapper =
            fromPage(page, ViewMapping(viewWidthPx, viewHeightPx))
    }
}

/** Returns a copy with all NaN/Inf components replaced by zero. */
internal fun PointF.finiteOrZero(): PointF = PointF(
    if (x.isFinite()) x else 0f,
    if (y.isFinite()) y else 0f,
)

/** True when two floats differ by no more than [tolerance]. */
internal fun approximatelyEqual(a: Float, b: Float, tolerance: Float = 0.001f): Boolean =
    abs(a - b) <= tolerance
