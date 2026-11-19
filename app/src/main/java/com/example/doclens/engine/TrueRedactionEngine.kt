package com.example.doclens.engine

import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.PDContentStream
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix

/**
 * Redaction zones for one document. Rectangles are absolute PDF user-space
 * points and use the Android `RectF` convention: `top` is the larger PDF y.
 */
data class RedactionRequest(
    val zonesByPage: Map<Int, List<RectF>>,
    val sanitizeMetadata: Boolean = false,
) {
    init {
        require(zonesByPage.keys.all { it >= 0 }) { "Page indexes must be non-negative" }
    }
}

/**
 * Removes redacted content from a PDF instead of merely covering it.
 *
 * For every requested page this engine:
 *  1. parses the page content stream with [PDFStreamParser],
 *  2. removes `Tj` / `TJ` / `'` / `"` operators whose computed glyph boxes
 *     intersect a redaction zone,
 *  3. removes annotations whose rectangles overlap a zone,
 *  4. rewrites the cleaned token stream, and
 *  5. paints an opaque black rectangle over each exact zone.
 *
 * Optionally, document information and catalog XMP metadata are replaced with
 * empty values.
 */
class TrueRedactionEngine {

    /** Builds an undoable redaction command. */
    fun redact(request: RedactionRequest): EditCommand =
        EditCommand("True redaction") { document -> execute(document, request) }

    /** Convenience overload for a single page. */
    fun redact(
        pageIndex: Int,
        zones: List<RectF>,
        sanitizeMetadata: Boolean = false,
    ): EditCommand = redact(RedactionRequest(mapOf(pageIndex to zones), sanitizeMetadata))

    private fun execute(document: PDDocument, request: RedactionRequest) {
        val normalized = request.zonesByPage.mapValues { (_, zones) -> zones.map(::normalizeZone) }
        for ((pageIndex, zones) in normalized) {
            require(pageIndex < document.numberOfPages) {
                "Page index $pageIndex is outside 0..${document.numberOfPages - 1}"
            }
            if (zones.isEmpty()) continue

            val page = document.getPage(pageIndex)
            stripTextOperators(document, page, zones)
            removeOverlappingAnnotations(page, zones)
            paintRedactionBoxes(document, page, zones)
        }

        if (request.sanitizeMetadata) {
            sanitizeDocumentMetadata(document)
        }
    }

    private fun stripTextOperators(document: PDDocument, page: PDPage, zones: List<RectF>) {
        if (!page.hasContents()) return

        val tokens = parseTokens(page)
        val scanner = TextShowRemovalScanner(page.resources, zones)
        val removals = scanner.scan(tokens, page.resources, Matrix())
        if (removals.isNotEmpty()) {
            val stream = PDStream(document)
            rewriteStream(stream, tokens, removals)
            page.setContents(stream)
        }

        for ((form, edit) in scanner.formEdits) {
            if (edit.removals.isNotEmpty()) {
                rewriteStream(form.contentStream, edit.tokens, edit.removals)
            }
        }
    }

    private fun rewriteStream(stream: PDStream, tokens: List<Any?>, removals: Set<Int>) {
        val rewritten = tokens.filterIndexed { index, _ -> index !in removals }
        stream.createOutputStream().use { output ->
            ContentStreamWriter(output).writeTokens(rewritten)
        }
    }

    private fun removeOverlappingAnnotations(page: PDPage, zones: List<RectF>) {
        val annotations = page.annotations
        if (annotations.isEmpty()) return

        val retained = annotations.filterNot { annotation ->
            val rectangle = annotation.rectangle ?: return@filterNot false
            val bounds = RectF(
                minOf(rectangle.lowerLeftX, rectangle.upperRightX),
                maxOf(rectangle.lowerLeftY, rectangle.upperRightY),
                maxOf(rectangle.lowerLeftX, rectangle.upperRightX),
                minOf(rectangle.lowerLeftY, rectangle.upperRightY),
            )
            zones.any { intersects(bounds, it, ANNOTATION_TOLERANCE_PT) }
        }
        if (retained.size != annotations.size) {
            page.annotations = retained
        }
    }

    private fun paintRedactionBoxes(document: PDDocument, page: PDPage, zones: List<RectF>) {
        PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { content ->
            content.setNonStrokingColor(0f, 0f, 0f)
            for (zone in zones) {
                content.addRect(
                    zone.left,
                    zone.bottom,
                    zone.right - zone.left,
                    zone.top - zone.bottom,
                )
                content.fill()
            }
        }
    }

    private fun sanitizeDocumentMetadata(document: PDDocument) {
        document.documentInformation = PDDocumentInformation()
        document.documentCatalog.metadata = null
    }

    /** Tracks enough PDF text/graphics state to locate text-showing operators. */
    private class TextShowRemovalScanner(
        private val pageResources: PDResources?,
        private val zones: List<RectF>,
    ) {
        val formEdits = LinkedHashMap<PDFormXObject, FormEdit>()
        private val activeForms = HashSet<PDFormXObject>()

        fun scan(
            tokens: List<Any?>,
            resources: PDResources?,
            initialCtm: Matrix,
        ): Set<Int> = Session(resources, initialCtm).find(tokens)

        private fun formEdit(form: PDFormXObject): FormEdit? = try {
            formEdits.getOrPut(form) { FormEdit(parseTokens(form), HashSet()) }
        } catch (ignored: Exception) {
            null
        }

        private inner class Session(
            private val resources: PDResources?,
            ctm: Matrix,
        ) {
            private var state = GraphicsState(ctm = ctm.clone())
            private val stack = ArrayDeque<GraphicsState>()
            private var lastShowAdvance = 0f

            fun find(tokens: List<Any?>): Set<Int> {
                val removals = HashSet<Int>()
                var operandStart = 0
                for (index in tokens.indices) {
                    val operator = tokens[index] as? Operator ?: continue
                    val operands = tokens.subList(operandStart, index)
                    when (operator.name) {
                        "q" -> stack.addLast(state.copyDeep())
                        "Q" -> if (stack.isNotEmpty()) state = stack.removeLast()
                        "cm" -> state.ctm = Matrix(
                            number(operands, 0), number(operands, 1),
                            number(operands, 2), number(operands, 3),
                            number(operands, 4), number(operands, 5),
                        ).multiply(state.ctm)
                        "BT" -> {
                            state.textMatrix = Matrix()
                            state.textLineMatrix = Matrix()
                        }
                        "ET" -> Unit
                        "Tm" -> {
                            val matrix = Matrix(
                                number(operands, 0), number(operands, 1),
                                number(operands, 2), number(operands, 3),
                                number(operands, 4), number(operands, 5),
                            )
                            state.textMatrix = matrix.clone()
                            state.textLineMatrix = matrix.clone()
                        }
                        "Td" -> translateTextLine(number(operands, 0), number(operands, 1))
                        "TD" -> {
                            state.leading = -number(operands, 1)
                            translateTextLine(number(operands, 0), number(operands, 1))
                        }
                        "T*" -> translateTextLine(0f, -state.leading)
                        "TL" -> state.leading = number(operands, 0)
                        "Tf" -> {
                            state.fontName = operands.firstOrNull { it is COSName } as? COSName
                            state.fontSize = number(operands, operands.lastIndex)
                        }
                        "Tz" -> state.horizontalScaling = number(operands, 0) / 100f
                        "Ts" -> state.rise = number(operands, 0)
                        "Do" -> scanFormXObject(operands)
                        "Tj", "TJ", "'", "\"" -> {
                            val segments = showSegments(operator.name, operands)
                            val shouldRemove = segments.any { segment ->
                                val bounds = segment.bounds() ?: return@any false
                                zones.any { intersects(bounds, it, TEXT_TOLERANCE_PT) }
                            }
                            if (shouldRemove) {
                                removals += operandStart..index
                            }
                            advanceAfterShow(segments)
                        }
                    }
                    operandStart = index + 1
                }
                return removals
            }

            private fun scanFormXObject(operands: List<Any?>) {
                val name = operands.firstOrNull { it is COSName } as? COSName ?: return
                val xObject = try {
                    resources?.getXObject(name)
                } catch (ignored: Exception) {
                    null
                }
                if (xObject !is PDFormXObject || !activeForms.add(xObject)) return

                try {
                    val edit = formEdit(xObject) ?: return
                    val formMatrix = xObject.matrix ?: Matrix()
                    val nestedCtm = formMatrix.multiply(state.ctm)
                    val nestedResources = xObject.resources ?: pageResources
                    edit.removals += this@TextShowRemovalScanner.scan(
                        edit.tokens,
                        nestedResources,
                        nestedCtm,
                    )
                } finally {
                    activeForms.remove(xObject)
                }
            }

            private fun showSegments(operator: String, operands: List<Any?>): List<ShowSegment> {
                if (operator == "'" || operator == "\"") {
                    translateTextLine(0f, -state.leading)
                }
                return when (operator) {
                    "TJ" -> {
                        val array = operands.firstOrNull { it is COSArray } as? COSArray
                            ?: return emptyList()
                        val segments = mutableListOf<ShowSegment>()
                        var offset = 0f
                        for (index in 0 until array.size()) {
                            when (val item = array.getObject(index)) {
                                is COSString -> {
                                    val width = item.string.textWidth()
                                    segments += ShowSegment(item.string, offset)
                                    offset += width
                                }
                                is COSNumber -> {
                                    offset -= item.floatValue() / 1000f * state.fontSize * state.horizontalScaling
                                }
                            }
                        }
                        lastShowAdvance = offset
                        segments
                    }
                    else -> {
                        val string = operands.firstOrNull { it is COSString } as? COSString
                            ?: return emptyList()
                        lastShowAdvance = string.string.textWidth()
                        listOf(ShowSegment(string.string, 0f))
                    }
                }
            }

            private fun String.textWidth(): Float {
                val font = state.font() ?: return 0f
                return font.getStringWidth(this) / 1000f * state.fontSize * state.horizontalScaling
            }

            private fun ShowSegment.bounds(): RectF? {
                val font = state.font() ?: return null
                val width = text.textWidth()
                if (width <= 0f) return null
                val (ascent, descent) = verticalMetrics(font, state.fontSize)
                val matrix = Matrix.getTranslateInstance(offset, state.rise)
                    .multiply(state.textMatrix)
                    .multiply(state.ctm)
                return transformRect(matrix, 0f, descent, width, ascent)
            }

            private fun advanceAfterShow(segments: List<ShowSegment>) {
                if (segments.isEmpty()) return
                state.textMatrix = Matrix.getTranslateInstance(lastShowAdvance, 0f)
                    .multiply(state.textMatrix)
            }

            private fun translateTextLine(tx: Float, ty: Float) {
                state.textLineMatrix = Matrix.getTranslateInstance(tx, ty)
                    .multiply(state.textLineMatrix)
                state.textMatrix = state.textLineMatrix.clone()
            }

            private fun GraphicsState.font(): PDFont? =
                fontName?.let { resources?.getFont(it) }
        }

        data class FormEdit(
            val tokens: List<Any?>,
            val removals: MutableSet<Int>,
        )

        private data class GraphicsState(
            var ctm: Matrix = Matrix(),
            var textMatrix: Matrix = Matrix(),
            var textLineMatrix: Matrix = Matrix(),
            var fontName: COSName? = null,
            var fontSize: Float = 0f,
            var horizontalScaling: Float = 1f,
            var rise: Float = 0f,
            var leading: Float = 0f,
        ) {
            fun copyDeep() = GraphicsState(
                ctm.clone(),
                textMatrix.clone(),
                textLineMatrix.clone(),
                fontName,
                fontSize,
                horizontalScaling,
                rise,
                leading,
            )
        }

        private data class ShowSegment(val text: String, val offset: Float)

        private fun number(operands: List<Any?>, index: Int): Float =
            (operands.getOrNull(index) as? COSNumber)?.floatValue() ?: 0f
    }
}

private const val TEXT_TOLERANCE_PT = 0.5f
private const val ANNOTATION_TOLERANCE_PT = 0.1f

private fun parseTokens(source: PDContentStream): List<Any?> {
    val parser = PDFStreamParser(source)
    parser.parse()
    return parser.tokens
}

private fun normalizeZone(zone: RectF): RectF {
    val left = minOf(zone.left, zone.right)
    val right = maxOf(zone.left, zone.right)
    val bottom = minOf(zone.top, zone.bottom)
    val top = maxOf(zone.top, zone.bottom)
    require(left.isFinite() && right.isFinite() && bottom.isFinite() && top.isFinite()) {
        "Redaction bounds must be finite"
    }
    require(right > left && top > bottom) {
        "Redaction bounds must have positive width and height"
    }
    return RectF(left, top, right, bottom)
}

private fun verticalMetrics(font: PDFont, fontSize: Float): Pair<Float, Float> {
    val descriptor = font.fontDescriptor
    val box = font.boundingBox
    var ascentUnits = descriptor?.ascent ?: box?.upperRightY ?: 800f
    var descentUnits = descriptor?.descent ?: box?.lowerLeftY ?: -200f
    if (!ascentUnits.isFinite() || ascentUnits <= 0f) ascentUnits = 800f
    if (!descentUnits.isFinite() || descentUnits >= 0f) descentUnits = -200f
    val scale = fontSize / 1000f
    return ascentUnits * scale to descentUnits * scale
}

private fun transformRect(
    matrix: Matrix,
    left: Float,
    bottom: Float,
    right: Float,
    top: Float,
): RectF {
    val corners = listOf(
        matrix.transformPoint(left, bottom),
        matrix.transformPoint(right, bottom),
        matrix.transformPoint(right, top),
        matrix.transformPoint(left, top),
    )
    return RectF(
        corners.minOf { it.x },
        corners.maxOf { it.y },
        corners.maxOf { it.x },
        corners.minOf { it.y },
    )
}

private fun intersects(a: RectF, b: RectF, tolerance: Float): Boolean =
    a.left <= b.right + tolerance && a.right >= b.left - tolerance &&
        a.top >= b.bottom - tolerance && a.bottom <= b.top + tolerance
