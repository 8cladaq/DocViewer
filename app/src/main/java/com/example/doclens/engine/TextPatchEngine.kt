package com.example.doclens.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Exact values reported by PDFBox for one `TextPosition`. */
data class TextPositionInfo(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val fontSizeInPt: Float,
    val fontName: String,
)

/** One extracted word, with an absolute PDF user-space bounding box. */
data class PdfTextWord(
    val pageIndex: Int,
    val text: String,
    val position: TextPositionInfo,
    val baseline: PointF,
    val pdfBounds: RectF,
)

/** One extracted line, built from words sharing a baseline. */
data class PdfTextLine(
    val pageIndex: Int,
    val text: String,
    val words: List<PdfTextWord>,
    val position: TextPositionInfo,
    val baseline: PointF,
    val pdfBounds: RectF,
) {
    val widthPt: Float get() = pdfBounds.right - pdfBounds.left
    val heightPt: Float get() = pdfBounds.top - pdfBounds.bottom
}

data class PdfTextIndex(
    val wordsByPage: Map<Int, List<PdfTextWord>>,
    val linesByPage: Map<Int, List<PdfTextLine>>,
)

enum class TextPatchMode {
    /** Mask the old glyphs with sampled background, then draw replacement text. */
    VISUAL_PATCH,

    /** Remove intersecting `Tj`/`TJ` operators, then append replacement text. */
    CONTENT_STREAM_STRIP,
}

data class TextReplacement(
    val pageIndex: Int,
    val target: PdfTextLine,
    val replacement: String,
    val mode: TextPatchMode = TextPatchMode.VISUAL_PATCH,
    val customFillArgb: Int? = null,
    val textColorArgb: Int? = null,
    val backgroundSampleScale: Float = 2f,
    val fontProvider: FontProvider? = null,
)

/** Supplies an embedded TrueType font stream for replacement text. */
interface FontProvider {
    fun open(): InputStream
}

/** Bundled Roboto font. Roboto is licensed under the Apache License, Version 2.0. */
class AssetFontProvider(
    context: Context,
    private val assetPath: String = "fonts/Roboto-Regular.ttf",
) : FontProvider {
    private val appContext = context.applicationContext ?: context

    override fun open(): InputStream = try {
        appContext.assets.open(assetPath)
    } catch (missingAsset: Exception) {
        // Fallback for devices where the system font is available but the asset is not.
        FileInputStream("/system/fonts/Roboto-Regular.ttf")
    }
}

data class SampledColors(
    val backgroundArgb: Int,
    val textArgb: Int,
)

/**
 * Extracts selectable text geometry and produces [EditCommand] replacements.
 *
 * All PDFBox work is expected to run on [Dispatchers.IO], normally through
 * [PdfDocumentRepository.read] and [PdfDocumentRepository.execute].
 */
class TextPatchEngine(
    context: Context,
    private val fontProvider: FontProvider = AssetFontProvider(context),
) {

    /** Extracts words and grouped lines from a PDFBox document. */
    fun extract(document: PDDocument): PdfTextIndex {
        val stripper = BoundingBoxTextStripper()
        stripper.sortByPosition = true
        stripper.getText(document)

        val wordsByPage = stripper.words.groupBy { it.pageIndex }
        val linesByPage = wordsByPage.mapValues { (_, words) -> groupIntoLines(words) }
        return PdfTextIndex(wordsByPage, linesByPage)
    }

    /** Convenience extraction through the repository's read-only document accessor. */
    suspend fun extract(repository: PdfDocumentRepository): PdfTextIndex =
        withContext(Dispatchers.IO) { repository.read { extract(it) } }

    /** Maps an Android view tap through [mapper] and returns the line under it. */
    fun selectLineAtScreen(
        index: PdfTextIndex,
        pageIndex: Int,
        screenPoint: PointF,
        mapper: PdfCoordinateMapper,
        tolerancePt: Float = 2f,
    ): PdfTextLine? = selectLineAtPdf(index, pageIndex, mapper.screenToPdf(screenPoint), tolerancePt)

    /** Returns the line whose absolute PDF bounding box contains [pdfPoint]. */
    fun selectLineAtPdf(
        index: PdfTextIndex,
        pageIndex: Int,
        pdfPoint: PointF,
        tolerancePt: Float = 2f,
    ): PdfTextLine? = index.linesByPage[pageIndex].orEmpty().firstOrNull { line ->
        containsPdfPoint(line.pdfBounds, pdfPoint.x, pdfPoint.y, tolerancePt)
    }

    /** Builds an undoable replacement command for the requested mode. */
    fun replace(request: TextReplacement): EditCommand = EditCommand(
        when (request.mode) {
            TextPatchMode.VISUAL_PATCH -> "Replace text (visual patch)"
            TextPatchMode.CONTENT_STREAM_STRIP -> "Replace text (content stream)"
        },
    ) { document -> executeReplacement(document, request) }

    private fun executeReplacement(document: PDDocument, request: TextReplacement) {
        require(request.pageIndex in 0 until document.numberOfPages) {
            "Page index ${request.pageIndex} is outside 0..${document.numberOfPages - 1}"
        }
        require(request.target.pageIndex == request.pageIndex) {
            "Target line belongs to page ${request.target.pageIndex}, not ${request.pageIndex}"
        }
        val replacement = sanitize(request.replacement)
        require(replacement.isNotEmpty()) { "Replacement text must not be blank" }

        val page = document.getPage(request.pageIndex)
        val colors = sampleColors(
            document,
            request.pageIndex,
            request.target.pdfBounds,
            request.backgroundSampleScale.coerceIn(1f, 6f),
        )
        val textColor = request.textColorArgb ?: colors.textArgb
        val provider = request.fontProvider ?: fontProvider
        val font = provider.open().use { stream -> PDType0Font.load(document, stream, true) }

        when (request.mode) {
            TextPatchMode.VISUAL_PATCH -> visualPatch(
                document,
                page,
                request,
                replacement,
                font,
                request.customFillArgb ?: colors.backgroundArgb,
                textColor,
            )
            TextPatchMode.CONTENT_STREAM_STRIP -> contentStreamStrip(
                document,
                page,
                request,
                replacement,
                font,
                textColor,
            )
        }
    }

    private fun visualPatch(
        document: PDDocument,
        page: PDPage,
        request: TextReplacement,
        replacement: String,
        font: PDType0Font,
        fillArgb: Int,
        textArgb: Int,
    ) {
        val bounds = request.target.pdfBounds
        val boundsHeight = bounds.top - bounds.bottom
        PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { content ->
            val (fr, fg, fb) = rgbComponents(fillArgb)
            content.setNonStrokingColor(fr, fg, fb)
            content.addRect(
                bounds.left - MASK_PADDING_PT,
                bounds.bottom - MASK_PADDING_PT,
                bounds.width() + MASK_PADDING_PT * 2f,
                boundsHeight + MASK_PADDING_PT * 2f,
            )
            content.fill()
            drawReplacement(content, request.target, replacement, font, textArgb)
        }
    }

    private fun contentStreamStrip(
        document: PDDocument,
        page: PDPage,
        request: TextReplacement,
        replacement: String,
        font: PDType0Font,
        textArgb: Int,
    ) {
        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens = parser.tokens
        val removals = ShowTextScanner(page, request.target.pdfBounds).findRemovals(tokens)

        if (removals.isNotEmpty()) {
            val rewritten = tokens.filterIndexed { index, _ -> index !in removals }
            val stream = PDStream(document)
            stream.createOutputStream().use { output ->
                ContentStreamWriter(output).writeTokens(rewritten)
            }
            page.setContents(stream)
        }

        PDPageContentStream(document, page, AppendMode.APPEND, true, true).use { content ->
            drawReplacement(content, request.target, replacement, font, textArgb)
        }
    }

    private fun drawReplacement(
        content: PDPageContentStream,
        line: PdfTextLine,
        replacement: String,
        font: PDType0Font,
        textArgb: Int,
    ) {
        val preferredSize = line.position.fontSizeInPt.coerceIn(4f, 96f)
        val fontSize = fitFontSize(
            font,
            replacement,
            line.widthPt,
            line.heightPt,
            preferredSize,
        )
        val (ascent, descent) = verticalMetrics(font, fontSize)

        // Keep the original baseline when possible, nudging only if the new font
        // would otherwise overflow the original line box.
        var baselineY = line.baseline.y
        if (baselineY + ascent > line.pdfBounds.top) {
            baselineY -= baselineY + ascent - line.pdfBounds.top
        }
        if (baselineY + descent < line.pdfBounds.bottom) {
            baselineY += line.pdfBounds.bottom - (baselineY + descent)
        }

        val (r, g, b) = rgbComponents(textArgb)
        content.setNonStrokingColor(r, g, b)
        content.beginText()
        content.setFont(font, fontSize)
        content.newLineAtOffset(line.pdfBounds.left, baselineY)
        content.showText(replacement)
        content.endText()
    }

    private fun groupIntoLines(words: List<PdfTextWord>): List<PdfTextLine> {
        if (words.isEmpty()) return emptyList()

        val sorted = words.sortedByDescending { it.baseline.y }
        val groups = mutableListOf<MutableList<PdfTextWord>>()
        for (word in sorted) {
            val group = groups.lastOrNull()
            val anchor = group?.first()?.baseline?.y ?: word.baseline.y
            val tolerance = max(2f, group?.first()?.position?.height ?: 2f) * 0.6f
            if (group != null && abs(anchor - word.baseline.y) <= tolerance) {
                group.add(word)
            } else {
                groups.add(mutableListOf(word))
            }
        }

        return groups.map { group ->
            val ordered = group.sortedBy { it.baseline.x }
            val bounds = RectF(ordered.first().pdfBounds)
            ordered.drop(1).forEach { bounds.union(it.pdfBounds) }
            val first = ordered.first()
            PdfTextLine(
                pageIndex = first.pageIndex,
                text = ordered.joinToString(" ") { it.text },
                words = ordered,
                position = first.position,
                baseline = PointF(first.baseline.x, first.baseline.y),
                pdfBounds = bounds,
            )
        }
    }

    private class BoundingBoxTextStripper : PDFTextStripper() {
        val words = mutableListOf<PdfTextWord>()
        private var geometry: PageGeometry? = null

        override fun startPage(page: PDPage) {
            super.startPage(page)
            geometry = PageGeometry.from(page)
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>?) {
            super.writeString(text, textPositions)
            val positions = textPositions.orEmpty()
            if (positions.isEmpty() || geometry == null) return

            val first = positions.first()
            val bounds = RectF()
            var initialized = false
            for (position in positions) {
                val glyph = glyphBounds(position, geometry!!)
                if (!initialized) {
                    bounds.set(glyph)
                    initialized = true
                } else {
                    bounds.union(glyph)
                }
            }

            val fontSize = if (first.fontSizeInPt > 0f) {
                first.fontSizeInPt
            } else {
                first.fontSize * abs(first.textMatrix.scalingFactorX)
            }
            words += PdfTextWord(
                pageIndex = currentPageNo - 1,
                text = text,
                position = TextPositionInfo(
                    x = first.x,
                    y = first.y,
                    width = first.widthDirAdj,
                    height = first.height,
                    fontSizeInPt = fontSize,
                    fontName = fontName(first.font),
                ),
                baseline = PointF(
                    first.textMatrix.translateX + geometry!!.cropBox.lowerLeftX,
                    first.textMatrix.translateY + geometry!!.cropBox.lowerLeftY,
                ),
                pdfBounds = bounds,
            )
        }
    }

    /** Scans page content tokens and records show-text operations over the target box. */
    private class ShowTextScanner(
        private val page: PDPage,
        private val target: RectF,
    ) {
        private var state = GraphicsState()
        private val stack = ArrayDeque<GraphicsState>()
        private var lastShowAdvance = 0f

        fun findRemovals(tokens: List<Any?>): Set<Int> {
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
                    "Tf" -> {
                        state.fontName = operands.firstOrNull { it is COSName } as? COSName
                        state.fontSize = number(operands, operands.lastIndex)
                    }
                    "Tz" -> state.horizontalScaling = number(operands, 0) / 100f
                    "Ts" -> state.rise = number(operands, 0)
                    "TL" -> state.leading = number(operands, 0)
                    "Tj", "TJ", "'", "\"" -> {
                        val segments = showSegments(operator.name, operands)
                        val intersects = segments.any { segment ->
                            val bounds = segmentBounds(segment) ?: return@any false
                            intersectsPdf(bounds, target, HIT_TOLERANCE_PT)
                        }
                        if (intersects) {
                            removals += operandStart..index
                        }
                        advanceAfterShow(segments)
                    }
                }
                operandStart = index + 1
            }
            return removals
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
                    for (i in 0 until array.size()) {
                        when (val item = array.getObject(i)) {
                            is COSString -> {
                                val width = item.string.textWidth()
                                segments += ShowSegment(item.string, offset)
                                offset += width
                            }
                            is COSNumber -> offset -= item.floatValue() / 1000f * state.fontSize * state.horizontalScaling
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

        private fun segmentBounds(segment: ShowSegment): RectF? {
            val font = state.font() ?: return null
            val width = segment.text.textWidth()
            if (width <= 0f) return null
            val (ascent, descent) = verticalMetrics(font, state.fontSize)
            val textMatrix = Matrix.getTranslateInstance(segment.offset, state.rise)
                .multiply(state.textMatrix)
                .multiply(state.ctm)
            return transformRect(textMatrix, 0f, descent, width, ascent)
        }

        private fun advanceAfterShow(segments: List<ShowSegment>) {
            if (segments.isEmpty()) return
            state.textMatrix = Matrix.getTranslateInstance(lastShowAdvance, 0f)
                .multiply(state.textMatrix)
        }

        private fun translateTextLine(tx: Float, ty: Float) {
            state.textLineMatrix = Matrix.getTranslateInstance(tx, ty).multiply(state.textLineMatrix)
            state.textMatrix = state.textLineMatrix.clone()
        }

        private fun GraphicsState.font(): PDFont? =
            fontName?.let { page.resources?.getFont(it) }

        private data class GraphicsState(
            var ctm: Matrix = Matrix(),
            var textMatrix: Matrix = Matrix(),
            var textLineMatrix: Matrix = Matrix(),
            var fontName: COSName? = null,
            var fontSize: Float = 0f,
            var horizontalScaling: Float = 1f,
            var rise: Float = 0f,
            var leading: Float = 0f,
        )

        private fun GraphicsState.copyDeep() = GraphicsState(
            ctm.clone(),
            textMatrix.clone(),
            textLineMatrix.clone(),
            fontName,
            fontSize,
            horizontalScaling,
            rise,
            leading,
        )

        private data class ShowSegment(val text: String, val offset: Float)

        private fun number(operands: List<Any?>, index: Int): Float =
            (operands.getOrNull(index) as? COSNumber)?.floatValue() ?: 0f
    }
}

private const val MASK_PADDING_PT = 0.6f
private const val HIT_TOLERANCE_PT = 1.5f

private fun glyphBounds(position: TextPosition, geometry: PageGeometry): RectF {
    val baselineX = position.textMatrix.translateX + geometry.cropBox.lowerLeftX
    val baselineY = position.textMatrix.translateY + geometry.cropBox.lowerLeftY
    val fontSize = if (position.fontSizeInPt > 0f) {
        position.fontSizeInPt
    } else {
        position.fontSize * abs(position.textMatrix.scalingFactorX)
    }
    var (ascent, descent) = verticalMetrics(position.font, fontSize)
    val reportedHeight = position.height
    if (ascent - descent < reportedHeight) {
        ascent += (reportedHeight - (ascent - descent)) / 2f
        descent -= (reportedHeight - (ascent - descent)) / 2f
    }

    val width = position.widthDirAdj.coerceAtLeast(0f)
    val direction = normalizeDegrees(position.dir)
    val corners = listOf(
        rotateOffset(0f, descent, direction),
        rotateOffset(width, descent, direction),
        rotateOffset(width, ascent, direction),
        rotateOffset(0f, ascent, direction),
    )
    val xs = corners.map { baselineX + it.first }
    val ys = corners.map { baselineY + it.second }
    val minY = ys.minOrNull() ?: 0f
    val maxY = ys.maxOrNull() ?: 0f
    // RectF keeps display-style bounds: top is the larger PDF y.
    return RectF(xs.minOrNull() ?: 0f, maxY, xs.maxOrNull() ?: 0f, minY)
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

private fun fitFontSize(
    font: PDType0Font,
    text: String,
    maxWidth: Float,
    maxHeight: Float,
    preferred: Float,
): Float {
    var size = preferred.coerceIn(4f, 96f)
    val widthAtSize = font.getStringWidth(text) / 1000f * size
    if (widthAtSize > maxWidth && widthAtSize > 0f) {
        size *= maxWidth / widthAtSize
    }
    val (ascent, descent) = verticalMetrics(font, size)
    val heightAtSize = ascent - descent
    if (heightAtSize > maxHeight && heightAtSize > 0f) {
        size *= maxHeight / heightAtSize
    }
    return size.coerceAtLeast(1f)
}

private fun sampleColors(
    document: PDDocument,
    pageIndex: Int,
    pdfBounds: RectF,
    scale: Float,
): SampledColors {
    val page = document.getPage(pageIndex)
    val display = PdfCoordinateMapper.pdfRectToDisplay(PageGeometry.from(page), pdfBounds)
    val bitmap = PDFRenderer(document).renderImage(pageIndex, scale, ImageType.ARGB)
    try {
        return sampleColors(bitmap, display, scale)
    } finally {
        bitmap.recycle()
    }
}

private fun sampleColors(bitmap: Bitmap, display: RectF, scale: Float): SampledColors {
    val fallbackBackground = Color.WHITE
    val fallbackText = 0xFF1F2937.toInt()
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        return SampledColors(fallbackBackground, fallbackText)
    }

    val left = floor(display.left * scale).toInt().coerceIn(0, bitmap.width - 1)
    val right = ceil(display.right * scale).toInt().coerceIn(0, bitmap.width - 1)
    val top = floor(display.top * scale).toInt().coerceIn(0, bitmap.height - 1)
    val bottom = ceil(display.bottom * scale).toInt().coerceIn(0, bitmap.height - 1)
    if (right <= left || bottom <= top) {
        return SampledColors(fallbackBackground, fallbackText)
    }

    val step = max(1, scale.roundToInt())
    val padding = max(2, (2f * scale).roundToInt())
    val ring = mutableListOf<Int>()
    for (x in (left - padding)..(right + padding) step step) {
        ring += safePixel(bitmap, x, top - padding)
        ring += safePixel(bitmap, x, bottom + padding)
    }
    for (y in (top - padding)..(bottom + padding) step step) {
        ring += safePixel(bitmap, left - padding, y)
        ring += safePixel(bitmap, right + padding, y)
    }

    val interior = mutableListOf<Int>()
    for (y in top..bottom step step) {
        for (x in left..right step step) {
            interior += safePixel(bitmap, x, y)
        }
    }

    val background = ring.filter { it != 0 }.mostFrequentOr(fallbackBackground)
    val text = interior
        .filter { Color.alpha(it) >= 128 && luminance(it) < 210 }
        .mostFrequentOr(interior.filter { Color.alpha(it) >= 128 }.minByOrNull { luminance(it) }
            ?: fallbackText)
    return SampledColors(background, text)
}

private fun safePixel(bitmap: Bitmap, x: Int, y: Int): Int =
    if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else 0

private fun List<Int>.mostFrequentOr(default: Int): Int {
    val counts = HashMap<Int, Int>()
    for (color in this) {
        val key = (color and 0x00F0F0F0.toInt()) or 0xFF000000.toInt()
        counts[key] = (counts[key] ?: 0) + 1
    }
    return counts.maxByOrNull { it.value }?.key ?: default
}

private fun luminance(color: Int): Int =
    (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

private fun transformRect(matrix: Matrix, left: Float, bottom: Float, right: Float, top: Float): RectF {
    val corners = listOf(
        matrix.transformPoint(left, bottom),
        matrix.transformPoint(right, bottom),
        matrix.transformPoint(right, top),
        matrix.transformPoint(left, top),
    )
    // Convert back to the display-style RectF convention used for PDF bounds.
    return RectF(
        corners.minOf { it.x }, corners.maxOf { it.y },
        corners.maxOf { it.x }, corners.minOf { it.y },
    )
}

private fun rotateOffset(x: Float, y: Float, degrees: Int): Pair<Float, Float> = when (degrees) {
    0 -> x to y
    90 -> -y to x
    180 -> -x to -y
    270 -> y to -x
    else -> x to y
}

private fun normalizeDegrees(value: Float): Int {
    val wrapped = ((value.roundToInt() % 360) + 360) % 360
    return when (wrapped) {
        0, 90, 180, 270 -> wrapped
        else -> 0
    }
}

private fun containsPdfPoint(rect: RectF, x: Float, y: Float, tolerance: Float): Boolean =
    x >= rect.left - tolerance && x <= rect.right + tolerance &&
        y >= rect.bottom - tolerance && y <= rect.top + tolerance

private fun intersectsPdf(a: RectF, b: RectF, tolerance: Float): Boolean =
    a.left <= b.right + tolerance && a.right >= b.left - tolerance &&
        a.top >= b.bottom - tolerance && a.bottom <= b.top + tolerance

private fun fontName(font: PDFont): String = when (font) {
    is PDType1Font -> font.baseFont ?: font.subType ?: "Type1"
    is PDType0Font -> font.baseFont ?: font.subType ?: "Type0"
    else -> font.subType ?: font.javaClass.simpleName
}

private fun rgbComponents(colorArgb: Int): FloatArray = floatArrayOf(
    Color.red(colorArgb) / 255f,
    Color.green(colorArgb) / 255f,
    Color.blue(colorArgb) / 255f,
)

private fun sanitize(value: String): String {
    val builder = StringBuilder(value.length)
    for (character in value) {
        builder.append(if (character == '\n' || character == '\r' || character == '\t') ' ' else character)
    }
    return builder.toString().trim()
}
