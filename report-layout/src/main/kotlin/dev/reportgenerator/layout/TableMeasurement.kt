package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle

private val CELL_PADDING: Length = 1.mm

data class MeasuredLine(
    val text: String,
    val style: TextStyle,
    val x: Length,
    val width: Length,
    val lineIndex: Int
)

data class MeasuredRow(
    val lines: List<MeasuredLine>,
    val lineHeight: Length,
    val height: Length
)

sealed interface LayoutBlock {
    val constraints: LayoutConstraints
    val path: String
    val height: Length
}

// Legacy path (IrTable.rowHeight == null): auto height, no cell borders. Untouched by the
// fixed-height/bordered rewrite below — still exercised by generic (non-specification) tables.
data class GroupHeaderBlock(
    val row: MeasuredRow,
    override val constraints: LayoutConstraints,
    override val path: String
) : LayoutBlock {
    override val height: Length get() = row.height
}

data class DataRowBlock(
    val row: MeasuredRow,
    override val constraints: LayoutConstraints,
    override val path: String
) : LayoutBlock {
    override val height: Length get() = row.height
}

// IrTable.rowHeight != null path: every physical row (data, group-title, blank spacer/filler) is
// one of these — fixed height, always bordered per column (LayoutEngine.drawBorderedRow), text
// alignment honored (unlike the legacy path, where IrCell.align is ignored for body rows).
data class BorderedRowBlock(
    val cells: List<IrCell>,
    val columns: List<IrColumn>,
    val offsets: List<Length>,
    val rowHeight: Length,
    override val constraints: LayoutConstraints,
    override val path: String
) : LayoutBlock {
    override val height: Length get() = rowHeight
}

fun columnOffsets(columns: List<IrColumn>, contentLeft: Length): List<Length> {
    var x = contentLeft
    return columns.map { column ->
        val start = x
        x += column.width
        start
    }
}

fun measureRow(
    row: IrRow,
    columns: List<IrColumn>,
    offsets: List<Length>,
    textMeasurer: TextMeasurer
): MeasuredRow {
    val measurements = row.cells.mapIndexed { index, cell ->
        textMeasurer.measure(cell.text, cell.style, columns[index].width) to cell
    }

    val lineHeight = measurements
        .filter { (measurement, _) -> measurement.lineCount > 0 }
        .maxOfOrNull { (measurement, _) -> measurement.height / measurement.lineCount }
        ?: Length.ZERO

    val lines = measurements.flatMapIndexed { index, (measurement, cell) ->
        measurement.lines.mapIndexed { lineIndex, text ->
            MeasuredLine(text, cell.style, offsets[index], columns[index].width, lineIndex)
        }
    }

    val maxLineCount = measurements.maxOfOrNull { (measurement, _) -> measurement.lineCount } ?: 0
    val height = lineHeight * maxLineCount + CELL_PADDING

    return MeasuredRow(lines, lineHeight, height)
}

fun measureGroupHeader(
    title: String,
    tableWidth: Length,
    contentLeft: Length,
    textMeasurer: TextMeasurer
): MeasuredRow {
    val style = Styles.heading
    val measurement = textMeasurer.measure(title, style, tableWidth)
    val lineHeight = if (measurement.lineCount > 0) measurement.height / measurement.lineCount else Length.ZERO

    val lines = measurement.lines.mapIndexed { lineIndex, text ->
        MeasuredLine(text, style, contentLeft, tableWidth, lineIndex)
    }

    val height = lineHeight * measurement.lineCount + CELL_PADDING
    return MeasuredRow(lines, lineHeight, height)
}

// A logical IrRow can wrap (by word, per column) into several physical table rows when
// IrTable.rowHeight is fixed: rather than growing one row's height to fit multiple lines (the
// legacy auto-height behavior), overflow spills into a whole new bordered physical row. Columns
// that don't need continuation (position/quantity/etc., normally single-line) naturally come back
// blank on lineIndex > 0 — no separate "don't repeat on continuation" branch needed.
fun splitRowIntoPhysicalRows(row: IrRow, columns: List<IrColumn>, textMeasurer: TextMeasurer): List<List<IrCell>> {
    val linesPerColumn = row.cells.mapIndexed { index, cell ->
        textMeasurer.measure(cell.text, cell.style, cellTextWidth(columns[index].width), breakLongWords = true).lines
    }
    val physicalCount = (linesPerColumn.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

    return (0 until physicalCount).map { lineIndex ->
        row.cells.mapIndexed { index, cell ->
            val ownLines = linesPerColumn[index]
            val text = when {
                // stickToFirstRow/stickToLastRow assume a single-line value anchored to one edge
                // of a row stretched by some OTHER column wrapping (e.g. a long "Наименование").
                // If this column's OWN text needs more than one line (e.g. a material's "0,35 кг"
                // overflowing the narrow "Кол." column), that assumption doesn't hold — fall back
                // to normal per-line rendering so no line is silently dropped.
                columns[index].stickToFirstRow && ownLines.size <= 1 -> {
                    if (lineIndex == 0) ownLines.firstOrNull() ?: "" else ""
                }
                columns[index].stickToLastRow && ownLines.size <= 1 -> {
                    if (lineIndex == physicalCount - 1) ownLines.firstOrNull() ?: "" else ""
                }
                else -> {
                    // Normal: show text for this line, empty if doesn't exist
                    ownLines.getOrNull(lineIndex) ?: ""
                }
            }
            cell.copy(text = text)
        }
    }
}

// Width actually available to text inside a bordered cell: the column minus FRAME_CELL_PADDING on
// BOTH sides, for every alignment. LEFT text is drawn 1mm off the left border (drawBorderedRow), so
// wrapping against the full column let a line run into the right border; the same margin on the
// right keeps the text block symmetric, and centered text gets the same limit so a full-width
// line never touches either border.
internal fun cellTextWidth(columnWidth: Length): Length =
    maxOf(columnWidth - FRAME_CELL_PADDING * 2, Length.ZERO)

fun blankBorderedRow(columns: List<IrColumn>, offsets: List<Length>, rowHeight: Length, path: String): BorderedRowBlock =
    BorderedRowBlock(
        cells = columns.map { IrCell("") },
        columns = columns,
        offsets = offsets,
        rowHeight = rowHeight,
        constraints = LayoutConstraints.Default,
        path = path
    )

private fun withKeepWithNext(block: LayoutBlock): LayoutBlock = when (block) {
    is GroupHeaderBlock -> block.copy(constraints = block.constraints.copy(keepWithNext = true))
    is DataRowBlock -> block.copy(constraints = block.constraints.copy(keepWithNext = true))
    is BorderedRowBlock -> block.copy(constraints = block.constraints.copy(keepWithNext = true))
}

fun buildBlocks(
    table: IrTable,
    offsets: List<Length>,
    tableWidth: Length,
    contentLeft: Length,
    textMeasurer: TextMeasurer,
    tablePath: String
): List<LayoutBlock> {
    val rowHeight = table.rowHeight
    return if (rowHeight != null) {
        buildBorderedBlocks(table, offsets, tableWidth, contentLeft, rowHeight, textMeasurer, tablePath)
    } else {
        buildLegacyBlocks(table, offsets, tableWidth, contentLeft, textMeasurer, tablePath)
    }
}

private fun buildLegacyBlocks(
    table: IrTable,
    offsets: List<Length>,
    tableWidth: Length,
    contentLeft: Length,
    textMeasurer: TextMeasurer,
    tablePath: String
): List<LayoutBlock> {
    val blocks = mutableListOf<LayoutBlock>()

    table.content.forEachIndexed { elementIndex, element ->
        when (element) {
            is IrGroup -> {
                val headerPath = "$tablePath/Group[${element.title}]"
                val headerRow = measureGroupHeader(element.title, tableWidth, contentLeft, textMeasurer)
                val headerBlock = GroupHeaderBlock(headerRow, element.constraints, headerPath)

                val rowBlocks = element.rows.mapIndexed { rowIndex, row ->
                    val rowPath = "$headerPath/Row[$rowIndex]"
                    DataRowBlock(measureRow(row, table.columns, offsets, textMeasurer), row.constraints, rowPath)
                }

                val groupBlocks: List<LayoutBlock> = listOf(headerBlock) + rowBlocks

                val effective = if (element.constraints.keepTogether && groupBlocks.size > 1) {
                    groupBlocks.mapIndexed { i, block -> if (i < groupBlocks.lastIndex) withKeepWithNext(block) else block }
                } else {
                    groupBlocks
                }

                blocks += effective
            }

            is IrRow -> {
                val rowPath = "$tablePath/Row[$elementIndex]"
                blocks += DataRowBlock(measureRow(element, table.columns, offsets, textMeasurer), element.constraints, rowPath)
            }
        }
    }

    return blocks
}

// Every physical row — group title, blank spacer, or a (possibly word-wrapped) data line — is a
// BorderedRowBlock so it's uniformly bordered per column. The "2 blank before / 1 blank after"
// spacer rule around a group title is expressed here as an unconditional keepWithNext chain
// (blank, blank, header, blank all bind to the next block, ending at the group's first data line)
// so they can never be split by a page break or left orphaned at the bottom of a page —
// independent of the group's own keepTogether (which only decides whether the WHOLE group binds).
private fun buildBorderedBlocks(
    table: IrTable,
    offsets: List<Length>,
    tableWidth: Length,
    contentLeft: Length,
    rowHeight: Length,
    textMeasurer: TextMeasurer,
    tablePath: String
): List<LayoutBlock> {
    val blocks = mutableListOf<LayoutBlock>()

    fun physicalRowBlocks(row: IrRow, path: String): List<BorderedRowBlock> =
        splitRowIntoPhysicalRows(row, table.columns, textMeasurer).mapIndexed { lineIndex, cells ->
            BorderedRowBlock(cells, table.columns, offsets, rowHeight, row.constraints, "$path/Line[$lineIndex]")
        }

    table.content.forEachIndexed { elementIndex, element ->
        when (element) {
            is IrGroup -> {
                val headerPath = "$tablePath/Group[${element.title}]"
                val titleColumnIndex = table.groupTitleColumn?.let { id -> table.columns.indexOfFirst { it.id == id } }

                // A title too wide for its cell must wrap onto a new physical row, exactly like a
                // data cell would (splitRowIntoPhysicalRows) — not overflow its fixed 8mm height
                // into the rows around it.
                val headerBlocks = if (titleColumnIndex != null && titleColumnIndex >= 0) {
                    val titleRow = IrRow(
                        cells = table.columns.mapIndexed { i, _ ->
                            if (i == titleColumnIndex) IrCell(element.title, style = Styles.groupHeader, align = TextAlign.CENTER) else IrCell("")
                        },
                        constraints = element.constraints
                    )
                    physicalRowBlocks(titleRow, headerPath)
                } else {
                    listOf(
                        BorderedRowBlock(
                            cells = listOf(IrCell(element.title, style = Styles.groupHeader, align = TextAlign.CENTER)),
                            columns = listOf(IrColumn("groupHeader", tableWidth)),
                            offsets = listOf(contentLeft),
                            rowHeight = rowHeight,
                            constraints = element.constraints,
                            path = headerPath
                        )
                    )
                }

                val blankBefore1 = blankBorderedRow(table.columns, offsets, rowHeight, "$headerPath/Blank[0]")
                val blankBefore2 = blankBorderedRow(table.columns, offsets, rowHeight, "$headerPath/Blank[1]")
                val blankAfter = blankBorderedRow(table.columns, offsets, rowHeight, "$headerPath/Blank[2]")

                val rowBlocks = element.rows.flatMapIndexed { rowIndex, row ->
                    physicalRowBlocks(row, "$headerPath/Row[$rowIndex]")
                }

                val prefix = listOf(blankBefore1, blankBefore2) + headerBlocks + listOf(blankAfter)
                val unit = prefix + rowBlocks

                val effective = unit.mapIndexed { i, block ->
                    // Every block of the prefix binds forward (blanks -> header line(s) ->
                    // blankAfter), and blankAfter binds to the FIRST data line only — so a title
                    // plus its spacers can never be stranded at the bottom of a page, while the
                    // rest of a long group stays free to break. A group without data rows has
                    // nothing to bind to (and must not bind to the next group's spacers). The
                    // whole group binds together only when keepTogether says so.
                    val inPrefix = i < prefix.size - 1 || (i == prefix.size - 1 && rowBlocks.isNotEmpty())
                    val boundToRows = element.constraints.keepTogether && i < unit.lastIndex
                    if (inPrefix || boundToRows) withKeepWithNext(block) else block
                }

                blocks += effective
            }

            is IrRow -> {
                blocks += physicalRowBlocks(element, "$tablePath/Row[$elementIndex]")
            }
        }
    }

    return blocks
}

fun groupIntoUnits(blocks: List<LayoutBlock>): List<List<LayoutBlock>> {
    val units = mutableListOf<List<LayoutBlock>>()
    var i = 0
    while (i < blocks.size) {
        val unit = mutableListOf(blocks[i])
        var j = i
        while (blocks[j].constraints.keepWithNext && j + 1 < blocks.size) {
            j += 1
            unit += blocks[j]
        }
        units += unit
        i = j + 1
    }
    return units
}
