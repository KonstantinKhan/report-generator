package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.Styles
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
    val row: MeasuredRow
    val constraints: LayoutConstraints
    val path: String
    val height: Length get() = row.height
}

data class GroupHeaderBlock(
    override val row: MeasuredRow,
    override val constraints: LayoutConstraints,
    override val path: String
) : LayoutBlock

data class DataRowBlock(
    override val row: MeasuredRow,
    override val constraints: LayoutConstraints,
    override val path: String
) : LayoutBlock

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

private fun withKeepWithNext(block: LayoutBlock): LayoutBlock = when (block) {
    is GroupHeaderBlock -> block.copy(constraints = block.constraints.copy(keepWithNext = true))
    is DataRowBlock -> block.copy(constraints = block.constraints.copy(keepWithNext = true))
}

fun buildBlocks(
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
