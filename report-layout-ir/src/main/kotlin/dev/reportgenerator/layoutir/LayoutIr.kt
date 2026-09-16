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

// Where a PositionedText's baseline sits within rect.height, as a fraction from the top (bigger
// ascent than descent, so > 0.5). Both renderers (SVG, PDF) must agree on this to draw identical
// output from the same Layout IR — it lives here, not duplicated per-renderer, for exactly that
// reason. Also used, negated to the perpendicular axis, to center VERTICAL_BOTTOM_TO_TOP text.
const val BASELINE_RATIO: Double = 0.8

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
    val sizePt: Double,
    // Synthetic slant, not a font swap — no italic variant of GOST Type A/B is loaded (see
    // fonts-and-licensing.md). Renderers apply a shear/skew transform when this is set.
    val italic: Boolean = false
)

data class LineStyle(
    val width: Length,
    val color: Color = Color.BLACK
)

data class BorderStyle(
    val width: Length,
    val color: Color = Color.BLACK
)
