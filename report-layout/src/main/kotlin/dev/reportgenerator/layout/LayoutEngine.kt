package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.ir.TitleBlockSpec
import dev.reportgenerator.ir.BorderStyle as IrBorderStyle
import dev.reportgenerator.ir.TextOrientation as IrTextOrientation
import dev.reportgenerator.layoutir.BorderStyle as LayoutBorderStyle
import dev.reportgenerator.layoutir.TextOrientation as LayoutTextOrientation
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.layoutir.ResolvedTextStyle

private val TITLE_BLOCK_HEIGHT: Length = 20.mm
private val TITLE_LINE_HEIGHT: Length = 5.mm
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
    val titleBlockHeight: Length
) {
    val contentTop: Length get() = margins.top + headerHeight

    fun contentBottom(isFirstPage: Boolean): Length {
        val reserved = if (isFirstPage) titleBlockHeight else Length.ZERO
        return format.height - margins.bottom - reserved
    }
}

fun layOut(
    document: IrDocument,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): LaidOutDocument {
    val setup = document.pageSetup
    val table = document.elements.filterIsInstance<IrTable>().firstOrNull()
    val titleBlockHeight = if (setup.titleBlock != null) TITLE_BLOCK_HEIGHT else Length.ZERO

    if (table == null) {
        val metrics = PageLayoutMetrics(setup.format, setup.margins, Length.ZERO, titleBlockHeight)
        val elements = mutableListOf<PageElement>()
        elements += frameRectangle(metrics, setup.frameStyle)
        setup.titleBlock?.let { elements += drawTitleBlock(it, metrics, fontResolver) }
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
        titleBlockHeight = titleBlockHeight
    )

    return renderPages(units, metrics, headerLayout.elements, setup, fontResolver)
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
        HeaderLayout(drawRichHeader(header, columns, offsets, top, fixedHeight, textMeasurer, fontResolver), fixedHeight)
    } else {
        val row = measureRow(IrRow(header.cells), columns, offsets, textMeasurer)
        HeaderLayout(drawRow(row, top, fontResolver), row.height)
    }
}

private fun renderPages(
    units: List<List<LayoutBlock>>,
    metrics: PageLayoutMetrics,
    headerElements: List<PageElement>,
    setup: PageSetup,
    fontResolver: (TextStyle) -> FontRef
): LaidOutDocument {
    val pages = mutableListOf<Page>()
    var elements = mutableListOf<PageElement>()
    var y = metrics.contentTop
    var pageNumber = 1

    fun isFirstPage() = pageNumber == 1

    fun drawChrome() {
        elements += frameRectangle(metrics, setup.frameStyle)
        elements += headerElements
        if (isFirstPage()) {
            setup.titleBlock?.let { elements += drawTitleBlock(it, metrics, fontResolver) }
        }
    }

    fun finishPage() {
        drawChrome()
        pages += Page(pageNumber, metrics.format, elements)
    }

    fun startNewPage() {
        finishPage()
        pageNumber += 1
        elements = mutableListOf()
        y = metrics.contentTop
    }

    for (unit in units) {
        val unitHeight = unit.fold(Length.ZERO) { acc, block -> acc + block.height }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            if (elements.isNotEmpty() || isFirstPage()) {
                startNewPage()
            }
        }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            val first = unit.first()
            throw LayoutOverflowException(
                elementPath = first.path,
                constraint = describeConstraint(first.constraints),
                pageNumber = pageNumber,
                message = "Block '${first.path}' height ${unitHeight.toMillimeters()}mm exceeds available content height on page $pageNumber"
            )
        }

        for (block in unit) {
            elements += drawRow(block.row, y, fontResolver)
            y += block.height
        }
    }

    finishPage()
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
            style = ResolvedTextStyle(fontResolver(line.style), line.style.fontSizeMm / PT_TO_MM)
        )
    }

private fun drawRichHeader(
    header: IrTableHeader,
    columns: List<IrColumn>,
    offsets: List<Length>,
    top: Length,
    height: Length,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> =
    header.cells.mapIndexed { index, cell ->
        val columnWidth = columns[index].width
        val x = offsets[index]

        when (cell.orientation) {
            IrTextOrientation.HORIZONTAL ->
                drawHorizontalHeaderCell(cell, x, top, columnWidth, height, textMeasurer, fontResolver)
            IrTextOrientation.VERTICAL_BOTTOM_TO_TOP ->
                drawVerticalHeaderCell(cell, x, top, columnWidth, height, textMeasurer, fontResolver)
        }
    }.flatten()

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
    // lineHeight only depends on style (font size), not content, so measuring cell.text here is
    // safe even when manualLines overrides what actually gets drawn.
    val measurement = textMeasurer.measure(cell.text, cell.style, columnWidth)
    val lineHeight = if (measurement.lineCount > 0) measurement.height / measurement.lineCount else Length.ZERO
    // manualLines bypasses auto-wrap entirely — the cell is expected to fit as given (e.g. a
    // deliberate hyphenated break like "Приме-" / "чание"), not re-measured against columnWidth.
    val lines = cell.manualLines ?: measurement.lines
    val totalTextHeight = lineHeight * lines.size
    val startY = top + (rowHeight - totalTextHeight) / 2

    return lines.mapIndexed { index, line ->
        PositionedText(
            text = line,
            rect = Rect(x, startY + lineHeight * index, columnWidth, lineHeight),
            style = ResolvedTextStyle(fontResolver(cell.style), cell.style.fontSizeMm / PT_TO_MM)
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
            style = ResolvedTextStyle(fontResolver(cell.style), cell.style.fontSizeMm / PT_TO_MM),
            orientation = LayoutTextOrientation.VERTICAL_BOTTOM_TO_TOP
        )
    )
}

private fun ptToLength(pt: Double): Length = Length.ofMillimeters(pt * PT_TO_MM)

private fun frameRectangle(metrics: PageLayoutMetrics, style: IrBorderStyle): Rectangle =
    Rectangle(
        rect = Rect(
            x = metrics.margins.left,
            y = metrics.margins.top,
            width = metrics.format.width - metrics.margins.left - metrics.margins.right,
            height = metrics.format.height - metrics.margins.top - metrics.margins.bottom
        ),
        style = LayoutBorderStyle(width = ptToLength(style.widthPt))
    )

private fun drawTitleBlock(
    spec: TitleBlockSpec,
    metrics: PageLayoutMetrics,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> {
    val top = metrics.format.height - metrics.margins.bottom - TITLE_BLOCK_HEIGHT
    val left = metrics.margins.left
    val width = metrics.format.width - metrics.margins.left - metrics.margins.right
    val style = Styles.designation

    val box = Rectangle(
        rect = Rect(left, top, width, TITLE_BLOCK_HEIGHT),
        style = LayoutBorderStyle(width = ptToLength(Styles.tableBorder.widthPt))
    )

    val lines = listOf(
        "Обозначение: ${spec.designation}",
        "Наименование: ${spec.name}",
        "Лист 1 из ${spec.sheetsTotal}"
    )

    val texts = lines.mapIndexed { index, text ->
        PositionedText(
            text = text,
            rect = Rect(left + 2.mm, top + 2.mm + TITLE_LINE_HEIGHT * index, width - 4.mm, TITLE_LINE_HEIGHT),
            style = ResolvedTextStyle(fontResolver(style), style.fontSizeMm / PT_TO_MM)
        )
    }

    return listOf(box) + texts
}
