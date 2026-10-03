package dev.reportgenerator.ir.frames

import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.ir.BorderWeight
import dev.reportgenerator.ir.CellBorders
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.StaticSlot
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.template.Binding
import dev.reportgenerator.template.BlockType
import dev.reportgenerator.template.LineWeight
import dev.reportgenerator.template.PageKind
import dev.reportgenerator.template.ResolvedBlock
import dev.reportgenerator.template.ResolvedBorders
import dev.reportgenerator.template.ResolvedCell
import dev.reportgenerator.template.Template
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.TemplateResolver
import dev.reportgenerator.template.TextAlign as TemplateTextAlign

// The GOST specification page template (resources/templates/gost-spec.yaml) and the adapter from a
// resolved template block to the IR's FrameSpec (cells in the block's own top-left-origin space).
// Placement of these blocks on a page is done by the layout engine from the same template.
object GostSpecTemplate {
    private const val RESOURCE = "/templates/gost-spec.yaml"

    val template: Template by lazy {
        val yaml = requireNotNull(GostSpecTemplate::class.java.getResourceAsStream(RESOURCE)) {
            "template resource missing: $RESOURCE"
        }.readBytes().toString(Charsets.UTF_8)
        TemplateLoader.load(yaml)
    }

    // Content of one slot as a FrameSpec. The template's own sheet is only used to resolve the block;
    // the cells are made relative to the block's origin, so the sheet format does not matter.
    fun frameSpec(slot: StaticSlot, from: Template = template): FrameSpec {
        val kind = if (slot == StaticSlot.CONTINUATION_FRAME) PageKind.REST else PageKind.FIRST
        val resolved = TemplateResolver.resolve(from, kind)
        val root = checkNotNull(resolved.block(slot.blockId)) { "template has no block '${slot.blockId}' on $kind pages" }
        val origin = Point(root.rect.x, root.rect.y)
        val parts = resolved.blocks.filter { it.id == slot.blockId || it.id.startsWith(slot.blockId + "/") }
        val cells = parts.flatMap { part ->
            when (part.type) {
                BlockType.TABLE -> part.cells.mapNotNull { toFrameCell(it, origin) }
                BlockType.BLOCKSET -> emptyList() // container: its children are listed separately
                else -> error("block '${part.id}' of type ${part.type} cannot be a static frame block")
            }
        }
        return FrameSpec(Size(root.rect.width, root.rect.height), cells)
    }

    private fun toFrameCell(cell: ResolvedCell, origin: Point): FrameCell? {
        val rect = Rect(cell.rect.x - origin.x, cell.rect.y - origin.y, cell.rect.width, cell.rect.height)
        val borders = cell.borders.toIr()
        check(cell.fontSize == null) { "cell fontSize is not supported for frame cells, use 'style'" }
        val style = cell.style?.let { STYLES[it] ?: error("unknown text style '$it' (${STYLES.keys.joinToString()})") } ?: Styles.frameText
        val align = when (cell.align) {
            TemplateTextAlign.LEFT -> TextAlign.LEFT
            TemplateTextAlign.CENTER -> TextAlign.CENTER
            TemplateTextAlign.RIGHT -> error("right alignment is not supported for frame cells")
        }
        val orientation = when (cell.rotate) {
            0 -> TextOrientation.HORIZONTAL
            90 -> TextOrientation.VERTICAL_BOTTOM_TO_TOP
            else -> error("text rotation ${cell.rotate} is not supported for frame cells (0 or 90)")
        }
        val bind = cell.bind
        return when {
            bind != null -> FrameCell.Dynamic(rect, Binding.path(bind), style, align, borders, orientation, cell.format, cell.optional)
            // draws nothing at all: no text and no border
            cell.text.isNullOrEmpty() && cell.borders.isNone -> null
            else -> FrameCell.Constant(rect, cell.text ?: "", style, align, borders, orientation)
        }
    }

    private fun ResolvedBorders.toIr() = CellBorders(top.toIr(), right.toIr(), bottom.toIr(), left.toIr())

    private fun LineWeight.toIr() = when (this) {
        LineWeight.NONE -> BorderWeight.NONE
        LineWeight.THIN -> BorderWeight.THIN
        LineWeight.THICK -> BorderWeight.THICK
    }

    // Style keys usable as `style:` in the YAML.
    private val STYLES: Map<String, TextStyle> = mapOf(
        "frameText" to Styles.frameText,
        "frameTextLarge" to Styles.frameTextLarge
    )
}
