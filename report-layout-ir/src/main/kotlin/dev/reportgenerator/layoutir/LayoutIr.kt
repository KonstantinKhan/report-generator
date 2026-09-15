package dev.reportgenerator.layoutir

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect

data class LaidOutDocument(val pages: List<Page>)

data class Page(
    val number: Int,
    val format: PageFormat,
    val elements: List<PageElement>
)

sealed interface PageElement

enum class TextOrientation { HORIZONTAL, VERTICAL_BOTTOM_TO_TOP }

data class PositionedText(
    val text: String,
    val rect: Rect,
    val style: ResolvedTextStyle,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL
) : PageElement

data class Line(
    val from: Point,
    val to: Point,
    val style: LineStyle
) : PageElement

data class Rectangle(
    val rect: Rect,
    val style: BorderStyle
) : PageElement

data class PositionedImage(
    val rect: Rect,
    val image: ImageRef
) : PageElement

data class FontRef(val id: String)

data class ImageRef(val id: String)

data class Color(val r: Int, val g: Int, val b: Int) {
    companion object {
        val BLACK = Color(0, 0, 0)
    }
}

data class ResolvedTextStyle(
    val font: FontRef,
    val sizePt: Double
)

data class LineStyle(
    val width: Length,
    val color: Color = Color.BLACK
)

data class BorderStyle(
    val width: Length,
    val color: Color = Color.BLACK
)
