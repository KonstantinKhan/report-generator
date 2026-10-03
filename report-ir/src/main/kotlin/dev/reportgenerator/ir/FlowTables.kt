package dev.reportgenerator.ir

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.template.DataSchema
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowCellRenderer
import dev.reportgenerator.template.FlowFill
import dev.reportgenerator.template.FlowShaper
import dev.reportgenerator.template.FlowStick
import dev.reportgenerator.template.FlowTableSpec
import dev.reportgenerator.template.MapDataContext
import dev.reportgenerator.template.TemplateContract
import dev.reportgenerator.template.TemplateError
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.itemFields
import dev.reportgenerator.template.itemSchema
import dev.reportgenerator.template.TextAlign as TemplateTextAlign

// YAML flow table spec (FlowTableSpec) + rows supplied by code -> IrTable. Structure, widths, header, spacers,
// styles, the data rules (where / sortBy / groupBy / computed numbering) and the content of every row cell
// (bind, format, cases) come from the spec; the layout algorithm (measure, wrap, pagination, fill) stays in
// report-layout. `schema` declares the `item` record the rows are made of (the code adds no table logic).
object FlowTables {
    private const val DEFAULT_ROW_STYLE = "tableText"
    private const val DEFAULT_HEADER_STYLE = "tableHeader"
    private const val DEFAULT_TITLE_STYLE = "groupHeader"

    // `rows` = one `item` record per row, in source order. `path` = YAML location of the spec (e.g.
    // `blocks[5].table`), for error messages. The spec is checked against `schema` first (TemplateContract).
    fun build(spec: FlowTableSpec, schema: DataSchema, rows: List<DataValue.Record>, path: String = "table"): IrTable {
        TemplateContract.requireFlowTable(spec, schema, path)
        val shaped = FlowShaper.shape(spec, schema, rows)
        val rowSchema = spec.itemSchema(schema)
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
        val fields = spec.itemFields(schema)
        val renderers = spec.columns.associate { it.id to FlowCellRenderer(spec.rowCells.getValue(it.id), fields) }
        fun row(item: DataValue.Record): IrRow {
            val data = MapDataContext(rowSchema, mapOf("item" to item))
            return IrRow(
                spec.columns.map { column ->
                    val cell = spec.rowCells.getValue(column.id)
                    IrCell(
                        renderers.getValue(column.id).render(item, data),
                        style = rowStyles.getValue(column.id), align = (cell.align ?: column.align).toIr()
                    )
                }
            )
        }

        return IrTable(
            columns = columns,
            header = header,
            content = if (spec.groupBy == null) shaped.flatMap { it.rows }.map(::row)
            else shaped.map { g -> IrGroup(requireNotNull(g.title), g.rows.map(::row)) },
            rowHeight = spec.rowHeight.mm,
            groupTitle = groupTitle,
            fillBlank = spec.fill == FlowFill.BLANK
        )
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
