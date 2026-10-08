package dev.reportgenerator.template

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.mm

enum class BlockType { FRAME, RECT, TABLE, TEXT, FLOW, BLOCKSET }

data class ResolvedBorders(val top: LineWeight, val right: LineWeight, val bottom: LineWeight, val left: LineWeight) {
    companion object {
        val DEFAULT = ResolvedBorders(LineWeight.THICK, LineWeight.THICK, LineWeight.THICK, LineWeight.THICK)
    }

    val isNone: Boolean get() = this == ResolvedBorders(LineWeight.NONE, LineWeight.NONE, LineWeight.NONE, LineWeight.NONE)
}

// `rect` is in sheet orientation (already rotated with its block). `rotate` = text rotation in degrees
// counterclockwise, block rotation composed with the cell's own (mod 360, so 0/90/180/270). `borders` are per
// sheet side. `rowSpan` > 1: the rect covers that many rows.
data class ResolvedCell(
    val row: Int,
    val col: Int,
    val span: Int,
    val rect: Rect,
    val text: String?,
    val bind: String?,
    val rotate: Int,
    val align: TextAlign,
    val fontSize: Double?,
    val rowSpan: Int = 1,
    val style: String? = null,
    val borders: ResolvedBorders = ResolvedBorders.DEFAULT,
    val format: FormatSpec? = null,
    val optional: Boolean = false
)

// Absolute geometry. Blocks nested in a blockset instance appear flat with ids "instance/child".
data class ResolvedBlock(
    val id: String,
    val type: BlockType,
    val rect: Rect,
    val anchors: Map<String, Point>,
    val cells: List<ResolvedCell> = emptyList(),
    val visibleOn: PageSelector = PageSelector.ALL,
    val reserves: Boolean = false,
    val thickness: Length? = null,
    val text: String? = null,
    val bind: String? = null,
    val align: TextAlign = TextAlign.LEFT,
    // text block: glyph rotation; table block: block rotation (the rect is already the rotated bounding box)
    val rotate: Int = 0,
    val fontSize: Double? = null,
    val format: FormatSpec? = null,
    val optional: Boolean = false
) {
    fun translated(dx: Length, dy: Length): ResolvedBlock = copy(
        rect = rect.translated(dx, dy),
        anchors = anchors.mapValues { (_, p) -> Point(p.x + dx, p.y + dy) },
        cells = cells.map { it.copy(rect = it.rect.translated(dx, dy)) }
    )
}

internal fun Rect.translated(dx: Length, dy: Length) = Rect(x + dx, y + dy, width, height)

data class ResolvedSheet(
    val format: PageFormat,
    val rect: Rect,
    // sheet minus margins; base area of the flow region
    val contentRect: Rect,
    val anchors: Map<String, Point>
)

// Template geometry for one page kind: only blocks visible on it, in dependency order.
data class ResolvedTemplate(
    val name: String,
    val kind: PageKind,
    val sheet: ResolvedSheet,
    val blocks: List<ResolvedBlock>,
    val flowRegion: Rect
) {
    fun block(id: String): ResolvedBlock? = blocks.firstOrNull { it.id == id }
}

object SheetFormats {
    // portrait sizes, mm
    val presets: Map<String, Pair<Double, Double>> = linkedMapOf(
        "A4" to (210.0 to 297.0),
        "A3" to (297.0 to 420.0),
        "A2" to (420.0 to 594.0),
        "A1" to (594.0 to 841.0),
        "A0" to (841.0 to 1189.0)
    )

    fun resolve(sheet: SheetSpec): PageFormat {
        val (w, h) = sheet.format?.let { presets.getValue(it.uppercase()) } ?: (sheet.width!! to sheet.height!!)
        val short = minOf(w, h)
        val long = maxOf(w, h)
        val (rw, rh) = if (sheet.orientation == Orientation.PORTRAIT) short to long else long to short
        return PageFormat(sheet.format?.uppercase() ?: "${w}x$h", rw.mm, rh.mm)
    }
}
