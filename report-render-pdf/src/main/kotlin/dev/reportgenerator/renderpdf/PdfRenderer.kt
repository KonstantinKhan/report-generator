package dev.reportgenerator.renderpdf

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.layout.FontRegistry
import dev.reportgenerator.layoutir.BASELINE_RATIO
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedImage
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.layoutir.TextOrientation
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.io.ByteArrayOutputStream

private const val MM_TO_PT = 72.0 / 25.4

fun renderToPdf(document: LaidOutDocument, fontRegistry: FontRegistry): ByteArray {
    PDDocument().use { pdf ->
        val fontCache = mutableMapOf<String, PDFont>()

        for (page in document.pages) {
            renderPage(pdf, page, fontRegistry, fontCache)
        }

        val out = ByteArrayOutputStream()
        pdf.save(out)
        return out.toByteArray()
    }
}

private fun renderPage(
    pdf: PDDocument,
    page: Page,
    fontRegistry: FontRegistry,
    fontCache: MutableMap<String, PDFont>
) {
    val widthPt = page.format.width.toPt()
    val heightPt = page.format.height.toPt()
    val pdPage = PDPage(PDRectangle(widthPt, heightPt))
    pdf.addPage(pdPage)

    PDPageContentStream(pdf, pdPage).use { stream ->
        for (element in page.elements) {
            renderElement(stream, element, pdf, fontRegistry, fontCache, heightPt)
        }
    }
}

private fun renderElement(
    stream: PDPageContentStream,
    element: PageElement,
    pdf: PDDocument,
    fontRegistry: FontRegistry,
    fontCache: MutableMap<String, PDFont>,
    pageHeightPt: Float
) {
    when (element) {
        is PositionedText -> renderText(stream, element, pdf, fontRegistry, fontCache, pageHeightPt)
        is Line -> renderLine(stream, element, pageHeightPt)
        is Rectangle -> renderRectangle(stream, element, pageHeightPt)
        is PositionedImage -> renderImagePlaceholder(stream, element, pageHeightPt)
    }
}

private fun renderText(
    stream: PDPageContentStream,
    text: PositionedText,
    pdf: PDDocument,
    fontRegistry: FontRegistry,
    fontCache: MutableMap<String, PDFont>,
    pageHeightPt: Float
) {
    val font = fontCache.getOrPut(text.style.font.id) { fontRegistry.loadInto(pdf, text.style.font) }

    when (text.orientation) {
        TextOrientation.HORIZONTAL -> renderHorizontalText(stream, text, font, pageHeightPt)
        TextOrientation.VERTICAL_BOTTOM_TO_TOP -> renderVerticalText(stream, text, font, pageHeightPt)
    }
}

private fun renderHorizontalText(
    stream: PDPageContentStream,
    text: PositionedText,
    font: PDFont,
    pageHeightPt: Float
) {
    val x = text.rect.x.toPt()
    val topY = text.rect.y.toPt()
    val heightPt = text.rect.height.toPt()
    val baselineLayoutY = topY + heightPt * BASELINE_RATIO.toFloat()
    val pdfY = flip(baselineLayoutY, pageHeightPt)

    stream.beginText()
    stream.setFont(font, text.style.sizePt.toFloat())
    stream.setTextMatrix(italicMatrix(x, pdfY, text.style.italic))
    stream.showText(text.text)
    stream.endText()
}

// No italic variant of GOST Type A/B is loaded (see fonts-and-licensing.md), so italic is a
// synthetic oblique: a horizontal shear proportional to height above the baseline, same trick
// most renderers use for a "fake italic". ~11° slant (tan ≈ 0.2), applied via the c component of
// the PDF text matrix [a b c d e f], which maps (px, py) -> (px + c*py + e, d*py + f).
private const val ITALIC_SHEAR = 0.2f

private fun italicMatrix(x: Float, y: Float, italic: Boolean): Matrix =
    if (italic) Matrix(1f, 0f, ITALIC_SHEAR, 1f, x, y) else Matrix.getTranslateInstance(x, y)

// PDF space is already y-up with a standard-math rotation convention (positive = counterclockwise,
// and "counterclockwise" here means the same thing visually as it does on paper — unlike SVG,
// there's no coordinate-flip to compensate for). So +90° here produces the same on-page result as
// rotate(-90) in the SVG renderer: bottom-to-top reading, baseline side on the right. Anchor is the
// physical bottom of the cell box — same point as the SVG version, just expressed after the Y flip.
//
// anchorX is NOT the box's horizontal center, same reasoning as the SVG renderer: ascent (bigger
// than descent) maps to page-left under this rotation, so a center anchor leaves ascent overhanging
// the left edge. rect.width is one line height; placing the anchor at BASELINE_RATIO across it
// (not 50%) gives ascent the larger margin it needs and centers the visible glyphs.
private fun renderVerticalText(
    stream: PDPageContentStream,
    text: PositionedText,
    font: PDFont,
    pageHeightPt: Float
) {
    val anchorX = (text.rect.x + text.rect.width * BASELINE_RATIO).toPt()
    val anchorY = flip((text.rect.y + text.rect.height).toPt(), pageHeightPt)

    stream.beginText()
    stream.setFont(font, text.style.sizePt.toFloat())
    stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(90.0), anchorX, anchorY))
    stream.showText(text.text)
    stream.endText()
}

private fun renderLine(stream: PDPageContentStream, line: Line, pageHeightPt: Float) {
    val x1 = line.from.x.toPt()
    val y1 = flip(line.from.y.toPt(), pageHeightPt)
    val x2 = line.to.x.toPt()
    val y2 = flip(line.to.y.toPt(), pageHeightPt)

    stream.setStrokingColor(Color(line.style.color.r, line.style.color.g, line.style.color.b))
    stream.setLineWidth(line.style.width.toPt())
    stream.moveTo(x1, y1)
    stream.lineTo(x2, y2)
    stream.stroke()
}

private fun renderRectangle(stream: PDPageContentStream, rectangle: Rectangle, pageHeightPt: Float) {
    val x = rectangle.rect.x.toPt()
    val topY = rectangle.rect.y.toPt()
    val widthPt = rectangle.rect.width.toPt()
    val heightPt = rectangle.rect.height.toPt()
    val bottomY = flip(topY + heightPt, pageHeightPt)

    stream.setStrokingColor(Color(rectangle.style.color.r, rectangle.style.color.g, rectangle.style.color.b))
    stream.setLineWidth(rectangle.style.width.toPt())
    stream.addRect(x, bottomY, widthPt, heightPt)
    stream.stroke()
}

private fun renderImagePlaceholder(stream: PDPageContentStream, image: PositionedImage, pageHeightPt: Float) {
    val x = image.rect.x.toPt()
    val topY = image.rect.y.toPt()
    val widthPt = image.rect.width.toPt()
    val heightPt = image.rect.height.toPt()
    val bottomY = flip(topY + heightPt, pageHeightPt)

    stream.setStrokingColor(Color.GRAY)
    stream.setLineDashPattern(floatArrayOf(2f, 2f), 0f)
    stream.setLineWidth(0.5f)
    stream.addRect(x, bottomY, widthPt, heightPt)
    stream.stroke()
    stream.setLineDashPattern(floatArrayOf(), 0f)
}

private fun Length.toPt(): Float = (toMillimeters() * MM_TO_PT).toFloat()

private fun flip(yPt: Float, pageHeightPt: Float): Float = pageHeightPt - yPt
