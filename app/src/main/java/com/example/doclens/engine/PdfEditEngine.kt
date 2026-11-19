package com.example.doclens.engine

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown

/** Native PDF annotation requests. Points are absolute PDF user-space points. */
sealed class NativeAnnotation {
    abstract val pageIndex: Int

    data class Ink(
        override val pageIndex: Int,
        val points: List<PointF>,
        val colorArgb: Int,
        val widthPt: Float,
        val contents: String = "",
    ) : NativeAnnotation()

    data class Highlight(
        override val pageIndex: Int,
        val points: List<PointF>,
        val colorArgb: Int,
        val contents: String = "",
    ) : NativeAnnotation()

    data class Note(
        override val pageIndex: Int,
        val point: PointF,
        val text: String,
        val colorArgb: Int = 0xFFFFCC28.toInt(),
    ) : NativeAnnotation()
}

/** Text drawn into the page content stream at an absolute PDF point. */
data class TextPatch(
    val pageIndex: Int,
    val point: PointF,
    val text: String,
    val colorArgb: Int = 0xFF1E293B.toInt(),
    val fontSizePt: Float = 12f,
)

/** Describes true text/annotation removal plus an opaque cover for one page. */
data class RedactionSpec(
    val pageIndex: Int,
    val rectangles: List<RectF>,
    val sanitizeMetadata: Boolean = false,
)

sealed class PageOperation {
    data class Rotate(val pageIndex: Int, val degrees: Int) : PageOperation()
    data class Delete(val pageIndex: Int) : PageOperation()
    data class Move(val fromIndex: Int, val toIndex: Int) : PageOperation()
    data class Merge(val pdfBytes: ByteArray) : PageOperation()
}

sealed class FormStamp {
    data class TextField(val fieldName: String, val value: String) : FormStamp()
    data class SignatureImage(
        val pageIndex: Int,
        val rectangle: RectF,
        val imageBytes: ByteArray,
    ) : FormStamp()
}

/**
 * Contract for building [EditCommand] instances. Command execution is always
 * performed by [PdfDocumentRepository] on [kotlinx.coroutines.Dispatchers.IO].
 */
interface PdfEditEngine {
    fun addNativeAnnotation(annotation: NativeAnnotation): EditCommand
    fun patchText(patch: TextPatch): EditCommand
    fun applyRedaction(spec: RedactionSpec): EditCommand
    fun applyPageOperation(operation: PageOperation): EditCommand
    fun stampFormOrSignature(stamp: FormStamp): EditCommand
}

/**
 * PDFBox 2.0.x implementation. Native annotations, text patching, and true
 * redaction are fully implemented; the remaining methods are explicit
 * integration stubs that fail loudly rather than silently producing unsafe edits.
 */
class DefaultPdfBoxEditEngine : PdfEditEngine {

    override fun addNativeAnnotation(annotation: NativeAnnotation): EditCommand =
        EditCommand("Add ${annotation::class.simpleName}") { document ->
            addAnnotation(document, annotation)
        }

    override fun patchText(patch: TextPatch): EditCommand =
        EditCommand("Add text") { document ->
            requirePage(document, patch.pageIndex)
            val sanitized = sanitize(patch.text)
            if (sanitized.isEmpty()) return@EditCommand
            val page = document.getPage(patch.pageIndex)
            val (r, g, b) = rgbComponents(patch.colorArgb)
            PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { content ->
                content.setNonStrokingColor(r, g, b)
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, patch.fontSizePt)
                content.newLineAtOffset(patch.point.x, patch.point.y)
                content.showText(sanitized)
                content.endText()
            }
        }

    override fun applyRedaction(spec: RedactionSpec): EditCommand =
        TrueRedactionEngine().redact(
            RedactionRequest(
                zonesByPage = mapOf(spec.pageIndex to spec.rectangles),
                sanitizeMetadata = spec.sanitizeMetadata,
            ),
        )

    override fun applyPageOperation(operation: PageOperation): EditCommand =
        EditCommand("Page operation") { _ ->
            throw UnsupportedOperationException(
                "Page operation ${operation::class.simpleName} is not wired into this engine.",
            )
        }

    override fun stampFormOrSignature(stamp: FormStamp): EditCommand =
        EditCommand("Form/signature stamp") { _ ->
            throw UnsupportedOperationException(
                "Stamp ${stamp::class.simpleName} is not wired into this engine.",
            )
        }

    private fun addAnnotation(document: PDDocument, annotation: NativeAnnotation) {
        requirePage(document, annotation.pageIndex)
        val page = document.getPage(annotation.pageIndex)
        val pdAnnotation = when (annotation) {
            is NativeAnnotation.Ink -> inkAnnotation(annotation)
            is NativeAnnotation.Highlight -> highlightAnnotation(annotation)
            is NativeAnnotation.Note -> noteAnnotation(annotation)
        }
        pdAnnotation.setPrinted(true)
        page.annotations.add(pdAnnotation)
        if (pdAnnotation !is PDAnnotationUnknown) {
            pdAnnotation.constructAppearances(document)
        }
    }

    private fun inkAnnotation(spec: NativeAnnotation.Ink): PDAnnotation {
        require(spec.points.size >= 2) { "Ink annotation needs at least two points" }
        require(spec.points.all { it.x.isFinite() && it.y.isFinite() }) {
            "Ink points must be finite"
        }
        require(spec.widthPt > 0f) { "Ink width must be positive" }

        val dictionary = COSDictionary()
        dictionary.setItem(pdfName("Type"), pdfName("Annot"))
        dictionary.setItem(pdfName("Subtype"), pdfName("Ink"))
        dictionary.setItem(pdfName("C"), rgbArray(spec.colorArgb))

        val border = COSDictionary()
        border.setItem(pdfName("S"), pdfName("S"))
        border.setItem(pdfName("W"), COSFloat(spec.widthPt))
        dictionary.setItem(pdfName("BS"), border)

        val stroke = COSArray()
        spec.points.forEach { point ->
            stroke.add(COSFloat(point.x))
            stroke.add(COSFloat(point.y))
        }
        val inkList = COSArray()
        inkList.add(stroke)
        dictionary.setItem(pdfName("InkList"), inkList)

        return PDAnnotationUnknown(dictionary).apply {
            val padding = (spec.widthPt / 2f).coerceAtLeast(1f)
            val bounds = boundingRect(spec.points, padding)
            rectangle = rectangle(bounds)
            contents = spec.contents
            annotationFlags = PRINT_FLAG
        }
    }

    private fun highlightAnnotation(spec: NativeAnnotation.Highlight): PDAnnotation {
        require(spec.points.isNotEmpty()) { "Highlight needs at least one point" }
        require(spec.points.all { it.x.isFinite() && it.y.isFinite() }) {
            "Highlight points must be finite"
        }

        val bounds = boundingRect(spec.points, 1f)
        return PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
            rectangle = rectangle(bounds)
            setQuadPoints(
                floatArrayOf(
                    bounds.left, bounds.top,
                    bounds.right, bounds.top,
                    bounds.left, bounds.bottom,
                    bounds.right, bounds.bottom,
                ),
            )
            color = PDColor(rgbComponents(spec.colorArgb), PDDeviceRGB.INSTANCE)
            contents = spec.contents
            annotationFlags = PRINT_FLAG
        }
    }

    private fun noteAnnotation(spec: NativeAnnotation.Note): PDAnnotation {
        require(spec.point.x.isFinite() && spec.point.y.isFinite()) {
            "Note point must be finite"
        }
        return PDAnnotationText().apply {
            rectangle = PDRectangle(spec.point.x, spec.point.y, NOTE_SIZE, NOTE_SIZE)
            setName(PDAnnotationText.NAME_NOTE)
            setOpen(false)
            contents = spec.text
            color = PDColor(rgbComponents(spec.colorArgb), PDDeviceRGB.INSTANCE)
            annotationFlags = PRINT_FLAG
        }
    }

    private fun requirePage(document: PDDocument, pageIndex: Int) {
        require(pageIndex in 0 until document.numberOfPages) {
            "Page index $pageIndex is outside 0..${document.numberOfPages - 1}"
        }
    }

    private fun rectangle(bounds: RectF): PDRectangle =
        PDRectangle(bounds.left, bounds.bottom, bounds.width(), bounds.height())

    private fun boundingRect(points: List<PointF>, padding: Float): RectF {
        val first = points.first()
        val rect = RectF(first.x, first.y, first.x, first.y)
        for (index in 1 until points.size) {
            rect.union(points[index].x, points[index].y)
        }
        rect.inset(-padding, -padding)
        return rect
    }

    private fun rgbComponents(colorArgb: Int): FloatArray = floatArrayOf(
        Color.red(colorArgb) / 255f,
        Color.green(colorArgb) / 255f,
        Color.blue(colorArgb) / 255f,
    )

    private fun rgbArray(colorArgb: Int): COSArray = COSArray().apply {
        rgbComponents(colorArgb).forEach { component -> add(COSFloat(component)) }
    }

    private fun pdfName(value: String): COSName = COSName.getPDFName(value)

    private fun sanitize(value: String): String {
        val builder = StringBuilder(value.length)
        for (character in value) {
            builder.append(
                when {
                    character == '\n' || character == '\r' || character == '\t' -> ' '
                    character.code in 32..126 -> character
                    else -> '?'
                },
            )
        }
        return builder.toString().trim()
    }

    private companion object {
        const val PRINT_FLAG = 4
        const val NOTE_SIZE = 24f
    }
}
