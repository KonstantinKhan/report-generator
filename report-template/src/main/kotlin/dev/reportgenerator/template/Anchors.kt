package dev.reportgenerator.template

import dev.reportgenerator.geometry.Corner
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.corner

// The 9 standard anchors every rectangular block (and the sheet) exposes.
enum class StdAnchor(val key: String, private val fx: Int, private val fy: Int, val corner: Corner? = null) {
    TOP_LEFT("topLeft", 0, 0, Corner.TOP_LEFT),
    TOP_CENTER("topCenter", 1, 0),
    TOP_RIGHT("topRight", 2, 0, Corner.TOP_RIGHT),
    MIDDLE_LEFT("middleLeft", 0, 1),
    CENTER("center", 1, 1),
    MIDDLE_RIGHT("middleRight", 2, 1),
    BOTTOM_LEFT("bottomLeft", 0, 2, Corner.BOTTOM_LEFT),
    BOTTOM_CENTER("bottomCenter", 1, 2),
    BOTTOM_RIGHT("bottomRight", 2, 2, Corner.BOTTOM_RIGHT);

    // Corners come from the shared geometry (report-geometry Rect.corner), edge/centre points from the grid.
    fun pointOn(rect: Rect): Point =
        corner?.let(rect::corner) ?: Point(rect.left + rect.width * fx / 2, rect.top + rect.height * fy / 2)

    companion object {
        val keys: Set<String> = entries.map { it.key }.toSet()
    }
}

fun Rect.standardAnchors(prefix: String = ""): Map<String, Point> =
    StdAnchor.entries.associate { prefix + it.key to it.pointOn(this) }

// "stamp.cell[2,3].topLeft" -> block "stamp", anchor "cell[2,3].topLeft".
data class AnchorRef(val blockId: String, val anchor: String)

fun parseAnchorRef(text: String): AnchorRef? {
    val dot = text.indexOf('.')
    if (dot <= 0 || dot == text.lastIndex) return null
    return AnchorRef(text.substring(0, dot), text.substring(dot + 1))
}

// Whitespace inside "cell[2, 3]" is tolerated.
internal fun normalizeAnchorName(name: String): String = name.filterNot { it.isWhitespace() }

internal val COL_ANCHOR = Regex("""col\[(\d+)]\.(left|right)""")
internal val ROW_ANCHOR = Regex("""row\[(\d+)]\.(top|bottom)""")
internal val CELL_ANCHOR = Regex("""cell\[(\d+),(\d+)]\.(\w+)""")
