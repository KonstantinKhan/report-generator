package dev.reportgenerator.ir

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.template.Binding
import dev.reportgenerator.template.DataSchema
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowFill
import dev.reportgenerator.template.FlowStick
import dev.reportgenerator.template.FlowTableSpec
import dev.reportgenerator.template.MapDataContext
import dev.reportgenerator.template.TemplateError
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TextAlign as TemplateTextAlign

// One group of the flow table as the code assembles it: the title and its rows (one `item` record each).
// Titles, order and filtering of groups stay code for now (the template describes structure and style).
data class FlowGroup(val title: String, val rows: List<DataValue.Record>)

// YAML flow table spec (FlowTableSpec) + data supplied by code -> IrTable. Structure, widths, header,
// spacers, styles and the bind of every row cell come from the spec; the layout algorithm (measure, wrap,
// pagination, fill) stays in report-layout. `schema` declares the `item` record the row binds read.
object FlowTables {
    private const val DEFAULT_ROW_STYLE = "tableText"
    private const val DEFAULT_HEADER_STYLE = "tableHeader"
    private const val DEFAULT_TITLE_STYLE = "groupHeader"

    // `path` = YAML location of the spec (e.g. `blocks[5].table`), for error messages.
    fun build(spec: FlowTableSpec, schema: DataSchema, groups: List<FlowGroup>, path: String = "table"): IrTable {
        val styles = StyleResolver(spec)
        val columns = spec.columns.map {
            IrColumn(it.id, it.width.mm, stickToFirstRow = it.stick == FlowStick.FIRST, stickToLastRow = it.stick == FlowStick.LAST)
        }

        val header = spec.header?.let { h ->
            IrTableHeader(
                cells = spec.columns.map { column ->
                    val cell = h.cells.getValue(column.id)
                    IrCell(
                        text = cell.text,
                        style = styles.resolve(cell.style ?: DEFAULT_HEADER_STYLE, "$path.header.cells.${column.id}.style"),
                        orientation = if (cell.rotate == 90) TextOrientation.VERTICAL_BOTTOM_TO_TOP else TextOrientation.HORIZONTAL,
                        align = cell.align.toIr(),
                        manualLines = cell.lines
                    )
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
        fun row(item: DataValue.Record): IrRow {
            val data = MapDataContext(schema, mapOf("item" to item))
            return IrRow(
                spec.columns.map { column ->
                    val cell = spec.rowCells.getValue(column.id)
                    val text = cell.bind?.let { Binding.render(it, cell.format, cell.optional, data) } ?: cell.text.orEmpty()
                    IrCell(text, style = rowStyles.getValue(column.id), align = (cell.align ?: column.align).toIr())
                }
            )
        }

        return IrTable(
            columns = columns,
            header = header,
            content = groups.map { g -> IrGroup(g.title, g.rows.map(::row)) },
            rowHeight = spec.rowHeight.mm,
            groupTitle = groupTitle,
            fillBlank = spec.fill == FlowFill.BLANK
        )
    }

    // Rows with no groups (a plain list of `item` records).
    fun buildRows(spec: FlowTableSpec, schema: DataSchema, rows: List<DataValue.Record>, path: String = "table"): IrTable {
        val grouped = build(spec, schema, listOf(FlowGroup("", rows)), path)
        return grouped.copy(content = (grouped.content.single() as IrGroup).rows)
    }

    // alias (FlowTableSpec.styles) first, then a built-in style name.
    private class StyleResolver(private val spec: FlowTableSpec) {
        fun resolve(name: String, at: String): TextStyle {
            val target = spec.styles[name] ?: name
            return Styles.named[target] ?: throw TemplateException(
                listOf(TemplateError(at, "unknown style '$target' (${Styles.named.keys.joinToString()})"))
            )
        }
    }

    private fun TemplateTextAlign.toIr(): TextAlign = when (this) {
        TemplateTextAlign.LEFT -> TextAlign.LEFT
        TemplateTextAlign.CENTER -> TextAlign.CENTER
        TemplateTextAlign.RIGHT -> error("right alignment is not supported in flow tables")
    }
}
