package dev.reportgenerator.ir

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.template.DataSchema
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowCellRenderer
import dev.reportgenerator.template.FlowFill
import dev.reportgenerator.template.FlowRemainder
import dev.reportgenerator.template.FlowHeaderCell
import dev.reportgenerator.template.placeCells
import dev.reportgenerator.template.FlowShaper
import dev.reportgenerator.template.FlowStick
import dev.reportgenerator.template.FlowTableSpec
import dev.reportgenerator.template.MapDataContext
import dev.reportgenerator.template.TemplateContract
import dev.reportgenerator.template.TotalValue
import dev.reportgenerator.template.TemplateError
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.FlowLines
import dev.reportgenerator.template.LinesScope
import dev.reportgenerator.template.itemFields
import dev.reportgenerator.template.itemSchema
import dev.reportgenerator.template.lineNumberColumn
import dev.reportgenerator.template.TextAlign as TemplateTextAlign

// YAML flow table spec (FlowTableSpec) + rows supplied by code -> IrTable. Structure, widths, header, spacers,
// styles, the data rules (where / sortBy / groupBy / computed numbers and arithmetic / totals) and the content of
// every row cell (bind, format, cases) come from the spec; the layout algorithm (measure, wrap, pagination, fill) stays in
// report-layout. `schema` declares the `item` record the rows are made of (the code adds no table logic).
object FlowTables {
    private const val DEFAULT_ROW_STYLE = "tableText"
    private const val DEFAULT_HEADER_STYLE = "tableHeader"
    private const val DEFAULT_TITLE_STYLE = "groupHeader"
    private const val DEFAULT_TOTAL_STYLE = "totalText"

    // `rows` = one `item` record per row, in source order. `path` = YAML location of the spec (e.g.
    // `blocks[5].table`), for error messages. The spec is checked against `schema` first (TemplateContract).
    fun build(spec: FlowTableSpec, schema: DataSchema, rows: List<DataValue.Record>, path: String = "table"): IrTable {
        TemplateContract.requireFlowTable(spec, schema, path)
        val shaped = FlowShaper.shapeTable(spec, schema, rows)
        val rowSchema = spec.itemSchema(schema)
        val styles = StyleResolver(spec)
        val columns = spec.columns.map {
            IrColumn(it.id, it.width.mm, stickToFirstRow = it.stick == FlowStick.FIRST, stickToLastRow = it.stick == FlowStick.LAST)
        }

        fun headerCell(cell: FlowHeaderCell, stylePath: String) = IrCell(
            text = cell.text,
            style = styles.resolve(cell.style ?: DEFAULT_HEADER_STYLE, stylePath),
            orientation = if (cell.rotate == 90) TextOrientation.VERTICAL_BOTTOM_TO_TOP else TextOrientation.HORIZONTAL,
            align = cell.align.toIr(),
            manualLines = cell.lines
        )
        val header = spec.header?.let { h ->
            if (h.rows.isNotEmpty()) {
                val rowHeights = h.rows.map { it.height.mm }
                IrTableHeader(
                    cells = emptyList(),
                    height = rowHeights.reduce { a, b -> a + b },
                    repeat = h.repeat,
                    grid = IrHeaderGrid(
                        rowHeights = rowHeights,
                        cells = h.placeCells(spec.columns.size).map {
                            IrHeaderCell(
                                headerCell(it.cell, "$path.header.rows[${it.row}].cells[${it.index}].style"),
                                it.row, it.col, it.cell.span, it.cell.rowSpan
                            )
                        }
                    )
                )
            } else IrTableHeader(
                cells = spec.columns.map { column ->
                    headerCell(h.cells.getValue(column.id), "$path.header.cells.${column.id}.style")
                },
                height = h.height.mm,
                repeat = h.repeat
            )
        }

        val groupTitle = spec.groupTitle?.let { g ->
            IrGroupTitle(
                column = g.column,
                style = styles.resolve(g.style ?: DEFAULT_TITLE_STYLE, "$path.groupTitle.style"),
                align = g.align.toIr(),
                spacerBefore = g.spacerBefore,
                spacerAfter = g.spacerAfter,
                keepWithRows = spec.keep.titleChain
            )
        } ?: IrGroupTitle(spacerBefore = 0, spacerAfter = 0, keepWithRows = spec.keep.titleChain)

        val rowStyles = spec.columns.associate {
            val cell = spec.rowCells.getValue(it.id)
            it.id to styles.resolve(cell.style ?: DEFAULT_ROW_STYLE, "$path.row.cells.${it.id}.style")
        }
        val fields = spec.itemFields(schema)
        val renderers = spec.columns.associate { it.id to FlowCellRenderer(spec.rowCells.getValue(it.id), fields) }
        // the cell bound to ${line.number} stays empty here: the layout writes the number of every physical line into it
        val lineColumn = spec.lineNumberColumn()
        fun row(item: DataValue.Record): IrRow {
            val data = MapDataContext(rowSchema, mapOf("item" to item))
            return IrRow(
                spec.columns.map { column ->
                    val cell = spec.rowCells.getValue(column.id)
                    IrCell(
                        if (column.id == lineColumn) "" else renderers.getValue(column.id).render(item, data, rows),
                        style = rowStyles.getValue(column.id), align = (cell.align ?: column.align).toIr()
                    )
                }
            )
        }

        // total row: the label and the result in their columns, the other cells empty (bordered like any row)
        val totalStyles = spec.totals.mapIndexed { i, t ->
            t.id to styles.resolve(t.style ?: DEFAULT_TOTAL_STYLE, "$path.totals[$i].style")
        }.toMap()
        fun totalRow(v: TotalValue): IrTotalRow = IrTotalRow(
            spec.columns.map { column ->
                val style = totalStyles.getValue(v.total.id)
                when (column.id) {
                    v.total.labelColumn -> IrCell(v.total.label, style, align = column.align.toIr())
                    v.total.valueColumn -> IrCell(v.text, style, align = column.align.toIr())
                    else -> IrCell("")
                }
            }
        )

        return IrTable(
            columns = columns,
            header = header,
            content = if (spec.groupBy == null) shaped.groups.flatMap { it.rows }.map(::row)
            else shaped.groups.map { g -> IrGroup(requireNotNull(g.title), g.rows.map(::row), footer = g.totals.map(::totalRow)) },
            rowHeight = spec.rowHeight.mm,
            groupTitle = groupTitle,
            fillBlank = spec.fill == FlowFill.BLANK,
            fillRemainder = (spec.remainder?.first ?: FlowRemainder.STRETCH).toIr(),
            fillRemainderRest = (spec.remainder?.rest ?: FlowRemainder.STRETCH).toIr(),
            footer = shaped.totals.map(::totalRow),
            lineNumbers = lineColumn?.let { id ->
                val lines = spec.lines ?: FlowLines()
                val cell = spec.rowCells.getValue(id)
                IrLineNumbers(
                    column = id,
                    start = lines.start,
                    scope = if (lines.scope == LinesScope.PAGE) IrLineScope.PAGE else IrLineScope.TABLE,
                    fillBlank = lines.fill,
                    style = rowStyles.getValue(id),
                    align = (cell.align ?: spec.columns.first { it.id == id }.align).toIr()
                )
            }
        )
    }

    // alias (FlowTableSpec.styles) first, then a built-in style name. An alias is its base style, a field the alias
    // sets (size, bold, italic, underline) replaces the base's (TextStyle.copy), the others stay the base's.
    private class StyleResolver(private val spec: FlowTableSpec) {
        fun resolve(name: String, at: String): TextStyle {
            val alias = spec.styles[name]
            val target = alias?.base ?: name
            val base = Styles.named[target] ?: throw TemplateException(
                listOf(TemplateError(at, "unknown style '$target' (${Styles.named.keys.joinToString()})"))
            )
            return if (alias == null) base else base.copy(
                fontSizeMm = alias.size ?: base.fontSizeMm,
                bold = alias.bold ?: base.bold,
                italic = alias.italic ?: base.italic,
                underline = alias.underline ?: base.underline
            )
        }
    }

    private fun FlowRemainder.toIr(): IrFillRemainder = if (this == FlowRemainder.GAP) IrFillRemainder.GAP else IrFillRemainder.STRETCH

    private fun TemplateTextAlign.toIr(): TextAlign = when (this) {
        TemplateTextAlign.LEFT -> TextAlign.LEFT
        TemplateTextAlign.CENTER -> TextAlign.CENTER
        TemplateTextAlign.RIGHT -> error("right alignment is not supported in flow tables")
    }
}
