package dev.reportgenerator.rendersvg

import dev.reportgenerator.layoutir.Color
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedImage
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.layoutir.TextOrientation

private const val PT_TO_MM = 25.4 / 72.0
private const val BASELINE_RATIO = 0.8

fun render(document: LaidOutDocument): List<String> = document.pages.map { renderPage(it) }

fun renderPage(page: Page): String {
    val widthMm = page.format.width.toMillimeters()
    val heightMm = page.format.height.toMillimeters()
    val body = page.elements.joinToString("\n") { renderElement(it) }

    return """
        |<svg xmlns="http://www.w3.org/2000/svg" width="${widthMm}mm" height="${heightMm}mm" viewBox="0 0 $widthMm $heightMm">
        |<rect x="0" y="0" width="$widthMm" height="$heightMm" fill="white"/>
        |$body
        |</svg>
    """.trimMargin()
}

private fun renderElement(element: PageElement): String = when (element) {
    is PositionedText -> renderText(element)
    is Line -> renderLine(element)
    is Rectangle -> renderRectangle(element)
    is PositionedImage -> renderImagePlaceholder(element)
}

private fun renderText(text: PositionedText): String = when (text.orientation) {
    TextOrientation.HORIZONTAL -> renderHorizontalText(text)
    TextOrientation.VERTICAL_BOTTOM_TO_TOP -> renderVerticalText(text)
}

private fun renderHorizontalText(text: PositionedText): String {
    val x = text.rect.x.toMillimeters()
    val top = text.rect.y.toMillimeters()
    val height = text.rect.height.toMillimeters()
    val baseline = top + height * BASELINE_RATIO
    val fontSizeMm = text.style.sizePt * PT_TO_MM

    return """<text x="$x" y="$baseline" font-size="$fontSizeMm" font-family="sans-serif">${escapeXml(text.text)}</text>"""
}

// rotate(-90) = counterclockwise in SVG's clockwise-positive convention: local +x (reading
// direction) maps to page "up", local +y (below baseline) maps to page "right" — so text reads
// bottom-to-top with the baseline side on the right, matching the ESKD "Формат"/"Зона" columns.
// Anchor point is the BOTTOM of the cell's box (rect.y + rect.height): text starts there and
// extends upward for exactly its own measured length, which is what rect.height already equals.
//
// px is NOT the box's horizontal center. A font's ascent (above baseline) is bigger than its
// descent (below) — under this rotation, ascent maps to page-left and descent to page-right, so
// anchoring at the center leaves the (larger) ascent overhanging the left edge: text visibly
// drifts left. rect.width here IS one line height (the box's on-page thickness, set when this
// PositionedText was built), so BASELINE_RATIO applied the same way as horizontal text — baseline
// sitting 80% of the way across, not 50% — gives ascent the 80%-sized margin it actually needs and
// centers the visible glyphs, not the baseline point.
private fun renderVerticalText(text: PositionedText): String {
    val px = (text.rect.x + text.rect.width * BASELINE_RATIO).toMillimeters()
    val py = (text.rect.y + text.rect.height).toMillimeters()
    val fontSizeMm = text.style.sizePt * PT_TO_MM

    return """<text x="0" y="0" font-size="$fontSizeMm" font-family="sans-serif" transform="translate($px, $py) rotate(-90)">${escapeXml(text.text)}</text>"""
}

private fun renderLine(line: Line): String {
    val x1 = line.from.x.toMillimeters()
    val y1 = line.from.y.toMillimeters()
    val x2 = line.to.x.toMillimeters()
    val y2 = line.to.y.toMillimeters()
    val strokeWidth = line.style.width.toMillimeters()

    return """<line x1="$x1" y1="$y1" x2="$x2" y2="$y2" stroke="${colorToRgb(line.style.color)}" stroke-width="$strokeWidth"/>"""
}

private fun renderRectangle(rectangle: Rectangle): String {
    val x = rectangle.rect.x.toMillimeters()
    val y = rectangle.rect.y.toMillimeters()
    val width = rectangle.rect.width.toMillimeters()
    val height = rectangle.rect.height.toMillimeters()
    val strokeWidth = rectangle.style.width.toMillimeters()

    return """<rect x="$x" y="$y" width="$width" height="$height" fill="none" stroke="${colorToRgb(rectangle.style.color)}" stroke-width="$strokeWidth"/>"""
}

private fun renderImagePlaceholder(image: PositionedImage): String {
    val x = image.rect.x.toMillimeters()
    val y = image.rect.y.toMillimeters()
    val width = image.rect.width.toMillimeters()
    val height = image.rect.height.toMillimeters()

    return """<g><rect x="$x" y="$y" width="$width" height="$height" fill="none" stroke="gray" stroke-dasharray="1,1"/><text x="$x" y="${y + height / 2}" font-size="2" fill="gray">[image: ${escapeXml(image.image.id)}]</text></g>"""
}

private fun colorToRgb(color: Color): String = "rgb(${color.r}, ${color.g}, ${color.b})"

private fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
