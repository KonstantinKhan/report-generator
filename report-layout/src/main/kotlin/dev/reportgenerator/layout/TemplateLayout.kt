package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.ir.BorderWeight
import dev.reportgenerator.ir.CellBorders
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.BorderStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.template.Binding
import dev.reportgenerator.template.BlockType
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.FormatSpec
import dev.reportgenerator.template.withPage
import dev.reportgenerator.template.LineWeight
import dev.reportgenerator.template.ResolvedBlock
import dev.reportgenerator.template.ResolvedBorders
import dev.reportgenerator.template.ResolvedTemplate
import dev.reportgenerator.template.TextAlign as TemplateTextAlign

private const val PT_TO_MM = 25.4 / 72.0

// Generic adapter: one resolved template page -> Layout IR page, no domain data. FRAME/RECT become a
// Rectangle of the block thickness, TABLE cells and TEXT blocks go through the engine's own frame-cell
// drawing (borders with half-width caps, text centring, vertical text). FLOW and BLOCKSET draw nothing.
// `data` supplies the bind values (the contract against its schema is checked by the caller, see
// TemplateContract); page.number / page.total are overlaid here from `pageNumber` / `pageCount`. A bind
// without a value is an error (IllegalStateException) unless the cell/block is `optional`.
// Limits: text rotation is 0 or 90 only (the Layout IR has no other orientation); RIGHT align is
// supported for horizontal text only.
fun layOutTemplate(
    resolved: ResolvedTemplate,
    data: DataContext,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef,
    pageNumber: Int = 1,
    pageCount: Int = pageNumber
): Page {
    val pageData = data.withPage(pageNumber, pageCount)
    val elements = resolved.blocks.flatMap { block ->
        when (block.type) {
            BlockType.FRAME, BlockType.RECT -> listOf<PageElement>(
                Rectangle(block.rect, BorderStyle(checkNotNull(block.thickness) { "block '${block.id}' has no thickness" }))
            )
            BlockType.TABLE -> block.cells.flatMap { cell ->
                drawCell(
                    block.id, cell.rect, text(cell.text, cell.bind, cell.format, cell.optional, pageData), cell.fontSize, cell.rotate,
                    cell.align, cell.borders, textMeasurer, fontResolver
                )
            }
            BlockType.TEXT -> drawCell(
                block.id, block.rect, text(block.text, block.bind, block.format, block.optional, pageData), block.fontSize, block.rotate,
                block.align, NO_BORDERS, textMeasurer, fontResolver
            )
            BlockType.FLOW, BlockType.BLOCKSET -> emptyList()
        }
    }
    return Page(pageNumber, resolved.sheet.format, elements)
}

private val NO_BORDERS = ResolvedBorders(LineWeight.NONE, LineWeight.NONE, LineWeight.NONE, LineWeight.NONE)

private fun text(text: String?, bind: String?, format: FormatSpec?, optional: Boolean, data: DataContext): String =
    if (bind != null) Binding.render(bind, format, optional, data) else text.orEmpty()

private fun drawCell(
    blockId: String,
    rect: Rect,
    text: String,
    fontSizePt: Double?,
    rotate: Int,
    align: TemplateTextAlign,
    borders: ResolvedBorders,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> {
    val orientation = when (rotate) {
        0 -> TextOrientation.HORIZONTAL
        90 -> TextOrientation.VERTICAL_BOTTOM_TO_TOP
        else -> throw IllegalArgumentException("block '$blockId': text rotation $rotate is not supported (0 or 90)")
    }
    val style = fontSizePt?.let { Styles.frameText.copy(fontSizeMm = it * PT_TO_MM) } ?: Styles.frameText
    val cell = FrameCell.Constant(
        rect = Rect(Length.ZERO, Length.ZERO, rect.width, rect.height),
        text = text,
        style = style,
        align = if (align == TemplateTextAlign.CENTER) TextAlign.CENTER else TextAlign.LEFT,
        borders = CellBorders(borders.top.toIr(), borders.right.toIr(), borders.bottom.toIr(), borders.left.toIr()),
        orientation = orientation
    )
    val drawn = drawFrame(FrameSpec(Size(rect.width, rect.height), listOf(cell)), Point(rect.x, rect.y), DataContext.EMPTY, textMeasurer, fontResolver)
    if (align != TemplateTextAlign.RIGHT || orientation != TextOrientation.HORIZONTAL) return drawn
    // drawFrame knows LEFT/CENTER only: push left-aligned lines to the right edge (same 1mm padding)
    return drawn.map { el ->
        if (el is PositionedText) el.copy(rect = el.rect.copy(x = rect.right - FRAME_CELL_PADDING - el.rect.width)) else el
    }
}

private fun LineWeight.toIr() = when (this) {
    LineWeight.NONE -> BorderWeight.NONE
    LineWeight.THIN -> BorderWeight.THIN
    LineWeight.THICK -> BorderWeight.THICK
}
