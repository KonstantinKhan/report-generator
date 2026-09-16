package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.ir.BorderWeight
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameBindings
import dev.reportgenerator.ir.FrameField
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.ir.BorderStyle as IrBorderStyle
import dev.reportgenerator.ir.TextOrientation as IrTextOrientation
import dev.reportgenerator.layoutir.BorderStyle as LayoutBorderStyle
import dev.reportgenerator.layoutir.TextOrientation as LayoutTextOrientation
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.LineStyle
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.layoutir.ResolvedTextStyle

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
    val frameHeight: Length
) {
    val contentTop: Length get() = margins.top + headerHeight

    fun contentBottom(isFirstPage: Boolean): Length {
        val reserved = if (isFirstPage) frameHeight else Length.ZERO
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
    val frameHeight = setup.frame?.size?.height ?: Length.ZERO

    if (table == null) {
        val metrics = PageLayoutMetrics(setup.format, setup.margins, Length.ZERO, frameHeight)
        val elements = mutableListOf<PageElement>()
        elements += frameRectangle(metrics, setup.frameStyle)
        setup.frame?.let { spec ->
            val bindings = resolveBindings(setup.frameBindings, pageNumber = 1, totalPages = 1)
            elements += drawFrame(spec, frameOrigin(metrics, spec), bindings, textMeasurer, fontResolver)
        }
        setup.leftMarginFrame?.let { spec ->
            elements += drawFrame(spec, leftMarginFrameOrigin(metrics, spec), emptyMap(), textMeasurer, fontResolver)
        }
        setup.belowFrame?.let { spec ->
            elements += drawFrame(spec, belowFrameOrigin(metrics, spec), emptyMap(), textMeasurer, fontResolver)
        }
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
        frameHeight = frameHeight
    )

    return renderPages(units, metrics, headerLayout.elements, setup, textMeasurer, fontResolver, table, offsets)
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

// Two passes: content first, chrome (frame/header/stamp) second. The stamp's SHEET_NUMBER/
// SHEETS_TOTAL bindings need the final page count, which isn't known until pagination finishes —
// drawing chrome per-page as each page closed (the old approach) couldn't supply that on page 1.
private fun renderPages(
    units: List<List<LayoutBlock>>,
    metrics: PageLayoutMetrics,
    headerElements: List<PageElement>,
    setup: PageSetup,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef,
    table: IrTable,
    offsets: List<Length>
): LaidOutDocument {
    val pageContents = mutableListOf(mutableListOf<PageElement>())
    // Final `y` of each page at the moment it closes — needed after the loop to know how much
    // space is left to fill with blank bordered rows (fixed-rowHeight tables only, see below).
    val pageFinalY = mutableListOf<Length>()
    var y = metrics.contentTop

    fun isFirstPage() = pageContents.size == 1

    fun startNewPage() {
        pageFinalY += y
        pageContents.add(mutableListOf())
        y = metrics.contentTop
    }

    for (unit in units) {
        val unitHeight = unit.fold(Length.ZERO) { acc, block -> acc + block.height }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            if (pageContents.last().isNotEmpty() || isFirstPage()) {
                startNewPage()
            }
        }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            val first = unit.first()
            throw LayoutOverflowException(
                elementPath = first.path,
                constraint = describeConstraint(first.constraints),
                pageNumber = pageContents.size,
                message = "Block '${first.path}' height ${unitHeight.toMillimeters()}mm exceeds available content height on page ${pageContents.size}"
            )
        }

        for (block in unit) {
            val elements = when (block) {
                is GroupHeaderBlock -> drawRow(block.row, y, fontResolver)
                is DataRowBlock -> drawRow(block.row, y, fontResolver)
                is BorderedRowBlock -> drawBorderedRow(block, y, textMeasurer, fontResolver)
            }
            pageContents.last() += elements
            y += block.height
        }
    }

    pageFinalY += y

    // Fixed-rowHeight tables (IrTable.rowHeight != null): the page must be fully covered with
    // bordered rows down to the frame/margin, even past the last real row (or with zero elements
    // at all) — not just as far as content happened to reach.
    val rowHeight = table.rowHeight
    if (rowHeight != null) {
        pageContents.forEachIndexed { index, content ->
            val bottom = metrics.contentBottom(index == 0)
            var fillY = pageFinalY[index]
            while (fillY + rowHeight <= bottom) {
                content += drawBorderedRow(
                    blankBorderedRow(table.columns, offsets, rowHeight, "Document/Table/Filler"),
                    fillY,
                    textMeasurer,
                    fontResolver
                )
                fillY += rowHeight
            }
        }
    }

    val totalPages = pageContents.size
    val pages = pageContents.mapIndexed { index, content ->
        val pageNumber = index + 1
        val chrome = mutableListOf<PageElement>()
        chrome += frameRectangle(metrics, setup.frameStyle)
        chrome += headerElements
        if (pageNumber == 1) {
            setup.frame?.let { spec ->
                val bindings = resolveBindings(setup.frameBindings, pageNumber, totalPages)
                chrome += drawFrame(spec, frameOrigin(metrics, spec), bindings, textMeasurer, fontResolver)
            }
            setup.leftMarginFrame?.let { spec ->
                chrome += drawFrame(spec, leftMarginFrameOrigin(metrics, spec), emptyMap(), textMeasurer, fontResolver)
            }
            setup.belowFrame?.let { spec ->
                chrome += drawFrame(spec, belowFrameOrigin(metrics, spec), emptyMap(), textMeasurer, fontResolver)
            }
        } else {
            setup.continuationFrame?.let { spec ->
                val bindings = resolveBindings(setup.frameBindings, pageNumber, totalPages)
                val origin = Point(metrics.margins.left, metrics.contentTop)
                chrome += drawFrame(spec, origin, bindings, textMeasurer, fontResolver)
            }
        }
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
    val border = Rectangle(
        rect = Rect(x, top, width, block.rowHeight),
        style = LayoutBorderStyle(width = ptToLength(Styles.tableBorderThin.widthPt))
    )

    // Left-aligned text sits flush against the column's left border without this — only LEFT
    // needs it, CENTER already keeps clear of both edges on its own.
    val (textX, textWidth) = if (cell.align == TextAlign.LEFT) {
        (x + FRAME_CELL_PADDING) to (width - FRAME_CELL_PADDING)
    } else {
        x to width
    }

    val text = drawHorizontalHeaderCell(cell, textX, top, textWidth, block.rowHeight, textMeasurer, fontResolver)
    val underline = if (cell.style.underline) {
        underlineElements(cell, textX, top, textWidth, block.rowHeight, textMeasurer)
    } else {
        emptyList()
    }

    listOf<PageElement>(border) + text + underline
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

        val border = Rectangle(
            rect = Rect(x, top, columnWidth, height),
            style = LayoutBorderStyle(width = ptToLength(Styles.tableBorder.widthPt))
        )

        val text = when (cell.orientation) {
            IrTextOrientation.HORIZONTAL ->
                drawHorizontalHeaderCell(cell, x, top, columnWidth, height, textMeasurer, fontResolver)
            IrTextOrientation.VERTICAL_BOTTOM_TO_TOP ->
                drawVerticalHeaderCell(cell, x, top, columnWidth, height, textMeasurer, fontResolver)
        }

        listOf<PageElement>(border) + text
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
        rect = Rect(
            x = metrics.margins.left,
            y = metrics.margins.top,
            width = metrics.format.width - metrics.margins.left - metrics.margins.right,
            height = metrics.format.height - metrics.margins.top - metrics.margins.bottom
        ),
        style = LayoutBorderStyle(width = ptToLength(style.widthPt))
    )

private fun frameOrigin(metrics: PageLayoutMetrics, spec: FrameSpec): Point = Point(
    x = metrics.format.width - metrics.margins.right - spec.size.width,
    y = metrics.format.height - metrics.margins.bottom - spec.size.height
)

// Outside the main frame, in the left margin gutter — its right edge touches the frame's left
// border from the outside, it doesn't eat into the content area. Bottom of the strip = bottom of
// the main frame itself (5mm from the sheet edge, same as the frame's own bottom line) — safe to
// go all the way down because, being outside the frame (x < margins.left), it sits in a different
// x-range than the stamp entirely, so there's no overlap to worry about (unlike when it was
// briefly placed inside the frame).
private fun leftMarginFrameOrigin(metrics: PageLayoutMetrics, spec: FrameSpec): Point = Point(
    x = metrics.margins.left - spec.size.width,
    y = metrics.format.height - metrics.margins.bottom - spec.size.height
)

// Below the frame's bottom line, in the sheet's own bottom-right margin gutter (between the
// frame and the physical page edge) — "Копировал"/"Формат" notes sit outside the frame entirely,
// not inside it like the stamp does.
private fun belowFrameOrigin(metrics: PageLayoutMetrics, spec: FrameSpec): Point = Point(
    x = metrics.format.width - spec.size.width,
    y = metrics.format.height - spec.size.height
)

private fun resolveBindings(bindings: FrameBindings?, pageNumber: Int, totalPages: Int): Map<FrameField, String> =
    buildMap {
        bindings?.designation?.let { put(FrameField.DESIGNATION, it) }
        bindings?.name?.let { put(FrameField.NAME, it) }
        put(FrameField.SHEET_NUMBER, pageNumber.toString())
        put(FrameField.SHEETS_TOTAL, totalPages.toString())
    }

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
private val FRAME_CELL_PADDING: Length = Length.ofMillimeters(1.0)

private fun drawFrame(
    spec: FrameSpec,
    origin: Point,
    bindings: Map<FrameField, String>,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> = spec.cells.flatMap { cell ->
    val rect = Rect(origin.x + cell.rect.x, origin.y + cell.rect.y, cell.rect.width, cell.rect.height)

    val text = when (cell) {
        is FrameCell.Constant -> cell.text
        is FrameCell.Dynamic -> checkNotNull(bindings[cell.field]) {
            "FrameSpec references ${cell.field} but no value was supplied"
        }
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
