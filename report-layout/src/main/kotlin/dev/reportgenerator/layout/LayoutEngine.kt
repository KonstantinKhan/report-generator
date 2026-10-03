package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.ir.BorderWeight
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrFillRemainder
import dev.reportgenerator.ir.IrLineScope
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.StaticSlot
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.template.Binding
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.withPage
import dev.reportgenerator.ir.BorderStyle as IrBorderStyle
import dev.reportgenerator.ir.TextOrientation as IrTextOrientation
import dev.reportgenerator.layoutir.BorderStyle as LayoutBorderStyle
import dev.reportgenerator.layoutir.TextOrientation as LayoutTextOrientation
import dev.reportgenerator.layoutir.Color
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.LineStyle
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.layoutir.ResolvedTextStyle
import dev.reportgenerator.template.TemplateResolver

private const val PT_TO_MM = 25.4 / 72.0

class LayoutOverflowException(
    val elementPath: String,
    val constraint: String,
    val pageNumber: Int,
    message: String
) : RuntimeException(message)

data class PageLayoutMetrics(
    val format: PageFormat,
    val margins: Insets,
    val headerHeight: Length,
    // Rects of the static blocks that reserve content space (template `reserves: true`) on first / later
    // pages, resolved by layOutStaticBlocks from PageSetup.staticTemplate.
    val firstPageBlocks: List<Rect> = emptyList(),
    val continuationPageBlocks: List<Rect> = emptyList(),
    // false: the table header is drawn on the first page only, later pages start at the top margin.
    val repeatHeader: Boolean = true
) {
    fun contentTop(isFirstPage: Boolean): Length = margins.top + if (isFirstPage || repeatHeader) headerHeight else Length.ZERO

    val frameRect: Rect get() = frameRectOf(format, margins)

    // Bottom of the content column = TemplateResolver.flowRegionFor on [contentTop, frame bottom): the
    // nearest top edge among reserving blocks that horizontally overlap the content column AND sit at or
    // below contentTop (a block in the margin gutter, e.g. leftMarginTable, doesn't participate - a
    // geometric fact of its resolved rect). Only the BOTTOM of the column is reserved; see the TODO on
    // flowRegionFor about a possible top case.
    fun contentBottom(isFirstPage: Boolean): Length {
        val blocks = if (isFirstPage) firstPageBlocks else continuationPageBlocks
        val top = contentTop(isFirstPage)
        val content = Rect(margins.left, top, format.width - margins.left - margins.right, frameRect.bottom - top)
        return TemplateResolver.flowRegionFor(content, blocks).bottom
    }
}

private fun frameRectOf(format: PageFormat, margins: Insets): Rect = Rect(
    x = margins.left,
    y = margins.top,
    width = format.width - margins.left - margins.right,
    height = format.height - margins.top - margins.bottom
)

fun layOut(
    document: IrDocument,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): LaidOutDocument {
    val setup = document.pageSetup
    val table = document.elements.filterIsInstance<IrTable>().firstOrNull()
    val staticBlocks = layOutStaticBlocks(setup)

    if (table == null) {
        val metrics = PageLayoutMetrics(setup.format, setup.margins, Length.ZERO, staticBlocks.firstReserved, staticBlocks.continuationReserved)
        val elements = mutableListOf<PageElement>()
        elements += frameRectangle(metrics, setup.frameStyle)
        // A table-less document has always drawn only these three blocks (not specLeft / mainTitleRight).
        val bindings = pageData(setup.dataContext, pageNumber = 1, totalPages = 1)
        elements += drawStaticBlocks(staticBlocks.first, TABLELESS_SLOTS, bindings, textMeasurer, fontResolver)
        return LaidOutDocument(listOf(Page(1, setup.format, elements)))
    }

    val contentLeft = setup.margins.left
    val contentWidth = setup.format.width - setup.margins.left - setup.margins.right
    val offsets = columnOffsets(table.columns, contentLeft)

    val headerLayout = buildHeaderLayout(table.header, table.columns, offsets, setup.margins.top, textMeasurer, fontResolver)

    val blocks = buildBlocks(table, offsets, contentWidth, contentLeft, textMeasurer, "Document/Table")
    val units = groupIntoUnits(blocks)

    val metrics = PageLayoutMetrics(
        format = setup.format,
        margins = setup.margins,
        headerHeight = headerLayout.height,
        firstPageBlocks = staticBlocks.firstReserved,
        continuationPageBlocks = staticBlocks.continuationReserved,
        repeatHeader = table.header?.repeat ?: true
    )

    return renderPages(units, metrics, staticBlocks, headerLayout.elements, setup, textMeasurer, fontResolver, table, offsets)
}

private data class HeaderLayout(val elements: List<PageElement>, val height: Length)

private fun buildHeaderLayout(
    header: IrTableHeader?,
    columns: List<IrColumn>,
    offsets: List<Length>,
    top: Length,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): HeaderLayout {
    if (header == null) return HeaderLayout(emptyList(), Length.ZERO)

    val fixedHeight = header.height
    return if (fixedHeight != null) {
        HeaderLayout(drawRichHeader(header, columns, offsets, top, textMeasurer, fontResolver), fixedHeight)
    } else {
        val row = measureRow(IrRow(header.cells), columns, offsets, textMeasurer)
        HeaderLayout(drawRow(row, top, fontResolver), row.height)
    }
}

// Two passes: content first, chrome (frame/header/stamp) second. The stamp's page.number/
// page.total bindings need the final page count, which isn't known until pagination finishes —
// drawing chrome per-page as each page closed (the old approach) couldn't supply that on page 1.
private fun renderPages(
    units: List<List<LayoutBlock>>,
    metrics: PageLayoutMetrics,
    staticBlocks: StaticLayout,
    headerElements: List<PageElement>,
    setup: PageSetup,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef,
    table: IrTable,
    offsets: List<Length>
): LaidOutDocument {
    // Pagination only decides WHERE every block goes (page, y); the elements are drawn afterwards, page by page in
    // document order, so the line numbers (IrTable.lineNumbers) follow the final placement, not the order the
    // units were tried in.
    val placements = mutableListOf(mutableListOf<Pair<LayoutBlock, Length>>())
    // Final `y` of each page at the moment it closes — needed after the loop to know how much
    // space is left to fill with blank bordered rows (fixed-rowHeight tables only, see below).
    val pageFinalY = mutableListOf<Length>()
    var y = metrics.contentTop(isFirstPage = true)

    fun isFirstPage() = placements.size == 1

    fun startNewPage() {
        pageFinalY += y
        placements.add(mutableListOf())
        y = metrics.contentTop(isFirstPage = false)
    }
    for (unit in units) {
        val unitHeight = unit.fold(Length.ZERO) { acc, block -> acc + block.height }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            if (placements.last().isNotEmpty() || isFirstPage()) {
                startNewPage()
            }
        }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            val first = unit.first()
            throw LayoutOverflowException(
                elementPath = first.path,
                constraint = describeConstraint(first.constraints),
                pageNumber = placements.size,
                message = "Block '${first.path}' height ${unitHeight.toMillimeters()}mm exceeds available content height on page ${placements.size}"
            )
        }

        for (block in unit) {
            placements.last() += block to y
            y += block.height
        }
    }

    pageFinalY += y

    // IrTable.lineNumbers: the running number, written into the numbered column of every data line (and, with
    // fillBlank, of every filler row) in document order; PAGE scope restarts it on each page.
    val numbers = table.lineNumbers
    val numberColumn = numbers?.let { n -> table.columns.indexOfFirst { it.id == n.column } } ?: -1
    var counter = numbers?.start ?: 0L

    val pageContents = placements.mapIndexed { index, placed ->
        val content = mutableListOf<PageElement>()
        if (numbers != null && numbers.scope == IrLineScope.PAGE) counter = numbers.start
        for ((block, top) in placed) {
            content += when (block) {
                is GroupHeaderBlock -> drawRow(block.row, top, fontResolver)
                is DataRowBlock -> drawRow(block.row, top, fontResolver)
                is BorderedRowBlock ->
                    drawBorderedRow(if (numbers != null && block.numbered) block.withLineNumber(numberColumn, counter++) else block, top, textMeasurer, fontResolver)
            }
        }

        // Fixed-rowHeight tables (IrTable.rowHeight != null, IrTable.fillBlank): the page must be fully
        // covered with bordered rows down to the frame/margin, even past the last real row (or with zero
        // elements at all) — not just as far as content happened to reach.
        val rowHeight = table.rowHeight
        if (rowHeight != null && table.fillBlank) {
            val bottom = metrics.contentBottom(index == 0)
            val contentStart = pageFinalY[index]
            val available = bottom - contentStart

            // Calculate full rows and remainder
            val fullRowsCount = (available.raw / rowHeight.raw).toInt()
            val remainderHeight = available - (rowHeight * fullRowsCount)

            var fillY = contentStart
            repeat(fullRowsCount) { rowIndex ->
                val isLastRow = rowIndex == fullRowsCount - 1
                // STRETCH: the last row includes the remainder height; GAP: it stays below the last row
                val currentRowHeight = if (isLastRow && remainderHeight > Length.ZERO && table.fillRemainder == IrFillRemainder.STRETCH) {
                    rowHeight + remainderHeight
                } else {
                    rowHeight
                }
                val filler = if (numbers != null && numbers.fillBlank) {
                    numberedFillerRow(table.columns, offsets, currentRowHeight, "Document/Table/Filler", numberColumn, counter++, numbers)
                } else {
                    blankBorderedRow(table.columns, offsets, currentRowHeight, "Document/Table/Filler")
                }
                content += drawBorderedRow(filler, fillY, textMeasurer, fontResolver)
                fillY += currentRowHeight
            }
        }
        content
    }

    val totalPages = pageContents.size
    val pages = pageContents.mapIndexed { index, content ->
        val pageNumber = index + 1
        val chrome = mutableListOf<PageElement>()
        chrome += frameRectangle(metrics, setup.frameStyle)
        if (pageNumber == 1 || metrics.repeatHeader) chrome += headerElements
        // which blocks appear on which pages, and where, is the static template's business
        val bindings = pageData(setup.dataContext, pageNumber, totalPages)
        val blocks = if (pageNumber == 1) staticBlocks.first else staticBlocks.continuation
        chrome += drawStaticBlocks(blocks, null, bindings, textMeasurer, fontResolver)
        Page(pageNumber, metrics.format, chrome + content)
    }

    return LaidOutDocument(pages)
}

private fun describeConstraint(constraints: LayoutConstraints): String = when {
    constraints.keepTogether -> "keepTogether"
    constraints.keepWithNext -> "keepWithNext"
    else -> "content overflow"
}

private fun drawRow(row: MeasuredRow, top: Length, fontResolver: (TextStyle) -> FontRef): List<PageElement> =
    row.lines.map { line ->
        PositionedText(
            text = line.text,
            rect = Rect(
                x = line.x,
                y = top + row.lineHeight * line.lineIndex,
                width = line.width,
                height = row.lineHeight
            ),
            style = ResolvedTextStyle(fontResolver(line.style), line.style.fontSizeMm / PT_TO_MM, italic = line.style.italic)
        )
    }

// Every column of a fixed-height physical row (data, group title, or blank spacer/filler) gets
// its own border, per §"границы ячеек по ширине заголовков, высота 8 мм" — unlike the legacy
// drawRow() path, which draws no borders at all for body rows. Text reuses drawHorizontalHeaderCell
// (already handles cell.align + vertical centering within a fixed height) instead of drawRow's
// left-anchored, non-centering line placement.
private fun drawBorderedRow(
    block: BorderedRowBlock,
    top: Length,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> = block.cells.mapIndexed { index, cell ->
    val x = block.offsets[index]
    val width = block.columns[index].width

    // Thin per GOST 2.303 (§"граница ячеек для данных тонкая") — distinct from the thick outer
    // page frame (frameRectangle) and the thick header-row grid (drawRichHeader).
    val lineWidth = ptToLength(Styles.tableBorderThin.widthPt)
    val lineStyle = LineStyle(width = lineWidth, color = Color(0, 0, 0))
    val borders = buildList {
        // Top border
        add(Line(Point(x, top), Point(x + width, top), lineStyle))
        // Right border
        add(Line(Point(x + width, top), Point(x + width, top + block.rowHeight), lineStyle))
        // Bottom border
        add(Line(Point(x, top + block.rowHeight), Point(x + width, top + block.rowHeight), lineStyle))
        // Left border
        add(Line(Point(x, top), Point(x, top + block.rowHeight), lineStyle))
    }

    // Left-aligned text sits flush against the column's left border without this — only LEFT
    // needs the offset, CENTER already keeps clear of both edges on its own. The width is the
    // same one splitRowIntoPhysicalRows wrapped against (cellTextWidth), so a pre-wrapped line is
    // never re-wrapped here.
    val (textX, textWidth) = if (cell.align == TextAlign.LEFT) {
        (x + FRAME_CELL_PADDING) to cellTextWidth(width)
    } else {
        x to width
    }

    val text = drawHorizontalHeaderCell(cell, textX, top, textWidth, block.rowHeight, textMeasurer, fontResolver)
    val underline = if (cell.style.underline) {
        underlineElements(cell, textX, top, textWidth, block.rowHeight, textMeasurer)
    } else {
        emptyList()
    }

    borders + text + underline
}.flatten()

// Underline is drawn as an explicit Line under each text line rather than a font/renderer
// concept (§12: renderers stay dumb, and there's no italic/underline font variant loaded anyway —
// same known gap as Styles.heading.bold, see fonts-and-licensing.md). Mirrors
// drawHorizontalHeaderCell's own centering math so the line sits exactly under the glyphs it
// belongs to, not the cell's full box.
private fun underlineElements(
    cell: IrCell,
    x: Length,
    top: Length,
    columnWidth: Length,
    rowHeight: Length,
    textMeasurer: TextMeasurer
): List<PageElement> {
    val lines = textMeasurer.measure(cell.text, cell.style, columnWidth).lines
    val lineMeasurements = lines.map { line -> textMeasurer.measure(line, cell.style, columnWidth) }
    val lineHeight = lineMeasurements.firstOrNull { it.lineCount > 0 }
        ?.let { it.height / it.lineCount }
        ?: Length.ZERO
    val totalTextHeight = lineHeight * lines.size
    val startY = top + (rowHeight - totalTextHeight) / 2

    return lines.mapIndexed { index, line ->
        val lineWidth = lineMeasurements[index].width
        val lineX = when (cell.align) {
            TextAlign.LEFT -> x
            TextAlign.CENTER -> x + (columnWidth - lineWidth) / 2
        }
        val underlineY = startY + lineHeight * (index + 1)
        Line(
            Point(lineX, underlineY),
            Point(lineX + lineWidth, underlineY),
            LineStyle(width = ptToLength(Styles.tableBorderThin.widthPt))
        )
    }
}

// Every header cell is one thick-bordered rectangle over its grid area: the widths of the spanned columns, the
// heights of the spanned rows. A single-row header is the one-row grid of its cells (IrTableHeader.asGrid).
private fun drawRichHeader(
    header: IrTableHeader,
    columns: List<IrColumn>,
    offsets: List<Length>,
    top: Length,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> {
    val grid = requireNotNull(header.asGrid())
    val rowTops = grid.rowHeights.runningFold(top) { y, h -> y + h }
    return grid.cells.flatMap { placed ->
        val cell = placed.cell
        val x = offsets[placed.col]
        val width = columns.subList(placed.col, placed.col + placed.span).map { it.width }.reduce { a, b -> a + b }
        val y = rowTops[placed.row]
        val endRow = minOf(placed.row + placed.rowSpan, grid.rowHeights.size)
        val height = rowTops[endRow] - y

        val border = Rectangle(
            rect = Rect(x, y, width, height),
            style = LayoutBorderStyle(width = ptToLength(Styles.tableBorder.widthPt))
        )

        val text = when (cell.orientation) {
            IrTextOrientation.HORIZONTAL ->
                drawHorizontalHeaderCell(cell, x, y, width, height, textMeasurer, fontResolver)
            IrTextOrientation.VERTICAL_BOTTOM_TO_TOP ->
                drawVerticalHeaderCell(cell, x, y, width, height, textMeasurer, fontResolver)
        }

        listOf<PageElement>(border) + text
    }
}

// Fixed row height (unlike data rows): text is vertically centered within it rather than
// driving the height, since the header's own IrTableHeader.height is the source of truth.
private fun drawHorizontalHeaderCell(
    cell: IrCell,
    x: Length,
    top: Length,
    columnWidth: Length,
    rowHeight: Length,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> {
    // manualLines bypasses auto-wrap entirely — the cell is expected to fit as given (e.g. a
    // deliberate hyphenated break like "Приме-" / "чание"), not re-measured against columnWidth.
    val lines = cell.manualLines ?: textMeasurer.measure(cell.text, cell.style, columnWidth).lines
    // Re-measure each line individually: manual lines were never measured above, and centering
    // needs each line's own width, not just the cell's overall wrapped width.
    val lineMeasurements = lines.map { line -> textMeasurer.measure(line, cell.style, columnWidth) }
    val lineHeight = lineMeasurements.firstOrNull { it.lineCount > 0 }
        ?.let { it.height / it.lineCount }
        ?: Length.ZERO
    val totalTextHeight = lineHeight * lines.size
    val startY = top + (rowHeight - totalTextHeight) / 2

    return lines.mapIndexed { index, line ->
        val lineWidth = lineMeasurements[index].width
        val lineX = when (cell.align) {
            TextAlign.LEFT -> x
            TextAlign.CENTER -> x + (columnWidth - lineWidth) / 2
        }

        PositionedText(
            text = line,
            rect = Rect(lineX, startY + lineHeight * index, lineWidth, lineHeight),
            style = ResolvedTextStyle(fontResolver(cell.style), cell.style.fontSizeMm / PT_TO_MM, italic = cell.style.italic)
        )
    }
}

// Rotated 90° CCW: reading runs bottom-to-top, the glyph baseline (bottom of the letterforms)
// ends up on the column's right edge. See report-render-svg/report-render-pdf for the actual
// rotation — this only computes the (already-rotated) bounding box in normal page axes: width
// is the text's on-page thickness (one line height), height is its on-page run length.
private fun drawVerticalHeaderCell(
    cell: IrCell,
    x: Length,
    top: Length,
    columnWidth: Length,
    rowHeight: Length,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> {
    val measurement = textMeasurer.measure(cell.text, cell.style, rowHeight)
    val lineHeight = if (measurement.lineCount > 0) measurement.height / measurement.lineCount else Length.ZERO
    val textLength = if (measurement.lineCount > 0) measurement.width else Length.ZERO

    val boxWidth = lineHeight
    val boxHeight = textLength
    val boxX = x + (columnWidth - boxWidth) / 2
    val boxY = top + (rowHeight - boxHeight) / 2

    return listOf(
        PositionedText(
            text = measurement.lines.firstOrNull() ?: cell.text,
            rect = Rect(boxX, boxY, boxWidth, boxHeight),
            style = ResolvedTextStyle(fontResolver(cell.style), cell.style.fontSizeMm / PT_TO_MM, italic = cell.style.italic),
            orientation = LayoutTextOrientation.VERTICAL_BOTTOM_TO_TOP
        )
    )
}

private fun ptToLength(pt: Double): Length = Length.ofMillimeters(pt * PT_TO_MM)

private fun frameRectangle(metrics: PageLayoutMetrics, style: IrBorderStyle): Rectangle =
    Rectangle(
        rect = metrics.frameRect,
        style = LayoutBorderStyle(width = ptToLength(style.widthPt))
    )

// Frame/stamp bindings go to every block (cells of the other blocks are plain constants today).
private val TABLELESS_SLOTS = setOf(StaticSlot.FRAME, StaticSlot.LEFT_MARGIN, StaticSlot.BELOW_FRAME)

private fun drawStaticBlocks(
    blocks: List<PlacedStaticBlock>,
    only: Set<StaticSlot>?,
    bindings: DataContext,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> = blocks
    .filter { only == null || it.slot in only }
    .flatMap { drawFrame(it.spec, it.origin, bindings, textMeasurer, fontResolver) }

// Document data of the page setup with the layout-derived page.number / page.total laid over it.
private fun pageData(data: DataContext?, pageNumber: Int, totalPages: Int): DataContext =
    (data ?: DataContext.EMPTY).withPage(pageNumber, totalPages)

private fun borderLineStyle(weight: BorderWeight): LineStyle {
    val widthPt = when (weight) {
        BorderWeight.THICK -> Styles.tableBorder.widthPt
        BorderWeight.THIN -> Styles.tableBorderThin.widthPt
        BorderWeight.NONE -> 0.0
    }
    return LineStyle(width = ptToLength(widthPt))
}

private fun halfWidth(weight: BorderWeight): Length = borderLineStyle(weight).width / 2

// Keeps left-aligned text (e.g. "Разраб.") off the cell border it would otherwise touch exactly;
// applied uniformly (also to centered cells) rather than only for LEFT, since it's harmless there.
internal val FRAME_CELL_PADDING: Length = Length.ofMillimeters(1.0)

internal fun drawFrame(
    spec: FrameSpec,
    origin: Point,
    bindings: DataContext,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> = spec.cells.flatMap { cell ->
    val rect = Rect(origin.x + cell.rect.x, origin.y + cell.rect.y, cell.rect.width, cell.rect.height)

    val text = when (cell) {
        is FrameCell.Constant -> cell.text
        is FrameCell.Dynamic -> Binding.render(cell.path, cell.format, cell.optional, bindings)
    }

    // Each side is stretched by half its own width beyond the corner (a manual square line cap —
    // Line/LineStyle has no cap concept, so it's done here in coordinates). Without it, two butt-
    // capped segments meeting exactly at a corner leave a small square notch unpainted there: a
    // horizontal line only ever paints y in [top-w/2, top+w/2] for x >= its own left endpoint, so
    // the diagonal quadrant beyond the corner (x < left, y < top) is covered by neither segment.
    val topHalf = halfWidth(cell.borders.top)
    val rightHalf = halfWidth(cell.borders.right)
    val bottomHalf = halfWidth(cell.borders.bottom)
    val leftHalf = halfWidth(cell.borders.left)
    val borders = buildList {
        if (cell.borders.top != BorderWeight.NONE) {
            add(Line(Point(rect.left - topHalf, rect.top), Point(rect.right + topHalf, rect.top), borderLineStyle(cell.borders.top)))
        }
        if (cell.borders.right != BorderWeight.NONE) {
            add(Line(Point(rect.right, rect.top - rightHalf), Point(rect.right, rect.bottom + rightHalf), borderLineStyle(cell.borders.right)))
        }
        if (cell.borders.bottom != BorderWeight.NONE) {
            add(Line(Point(rect.left - bottomHalf, rect.bottom), Point(rect.right + bottomHalf, rect.bottom), borderLineStyle(cell.borders.bottom)))
        }
        if (cell.borders.left != BorderWeight.NONE) {
            add(Line(Point(rect.left, rect.top - leftHalf), Point(rect.left, rect.bottom + leftHalf), borderLineStyle(cell.borders.left)))
        }
    }

    val texts = if (text.isEmpty()) {
        emptyList()
    } else {
        val irCell = IrCell(text = text, style = cell.style, align = cell.align)
        when (cell.orientation) {
            IrTextOrientation.HORIZONTAL -> drawHorizontalHeaderCell(
                irCell,
                rect.x + FRAME_CELL_PADDING,
                rect.y,
                rect.width - FRAME_CELL_PADDING * 2,
                rect.height,
                textMeasurer,
                fontResolver
            )
            // No padding here: drawVerticalHeaderCell already centers on both axes (unlike the
            // horizontal path, it ignores cell.align entirely — there's no LEFT variant for
            // rotated text in this codebase), so text never touches the cell border.
            IrTextOrientation.VERTICAL_BOTTOM_TO_TOP -> drawVerticalHeaderCell(
                irCell,
                rect.x,
                rect.y,
                rect.width,
                rect.height,
                textMeasurer,
                fontResolver
            )
        }
    }

    borders + texts
}
