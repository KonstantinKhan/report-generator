package dev.reportgenerator.ir

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.dataSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Flow table header -> IrTableHeader: the single-row form keeps its IR (cells + height, no grid), the `rows` form
// becomes a grid (row heights, cells with origin / span / rowSpan); a single-row header is the one-row grid of its cells.
class MultiHeaderIrTest {
    private val schema = dataSchema { item { string("name") } }
    private val items = listOf(dataContext { item { string("name", "x") } }.get("item") as DataValue.Record)

    private fun build(header: String): IrTable {
        val head = "sheet: {format: A4, margins: {left: 20, right: 5}}\nblocks:\n  - id: body\n    type: flow\n    table:\n"
        val table = "rowHeight: 6\ncolumns: [{id: a, width: 15}, {id: b, width: 70}, {id: c, width: 50}, {id: d, width: 50}]\n" +
            header + "\nrow:\n  cells: {a: ~, b: {bind: \"\${item.name}\"}, c: ~, d: ~}"
        val flow = TemplateLoader.load(head + table.prependIndent("      "), Styles.named.keys).blocks.single() as FlowBlock
        return FlowTables.build(flow.table!!, schema, items)
    }

    @Test
    fun `rows form maps to a grid with the total height`() {
        val header = build(
            """
            header:
              rows:
                - height: 9
                  cells:
                    - {text: "№", rowSpan: 2, rotate: 90}
                    - {text: "Наименование", rowSpan: 2, style: tableHeader}
                    - {text: "Количество", span: 2, align: left}
                - height: 18.5
                  cells:
                    - {text: "x", lines: ["a", "b"]}
                    - {text: "y"}
            """.trimIndent()
        ).header!!

        assertEquals(emptyList(), header.cells)
        assertEquals(27.5.mm, header.height)
        assertEquals(true, header.repeat)
        val grid = header.grid!!
        assertEquals(listOf(9.mm, 18.5.mm), grid.rowHeights)
        assertEquals(
            listOf(listOf(0, 0, 1, 2), listOf(0, 1, 1, 2), listOf(0, 2, 2, 1), listOf(1, 2, 1, 1), listOf(1, 3, 1, 1)),
            grid.cells.map { listOf(it.row, it.col, it.span, it.rowSpan) }
        )
        assertEquals(listOf("№", "Наименование", "Количество", "x", "y"), grid.cells.map { it.cell.text })
        assertEquals(TextOrientation.VERTICAL_BOTTOM_TO_TOP, grid.cells[0].cell.orientation)
        assertEquals(TextAlign.LEFT, grid.cells[2].cell.align)
        assertEquals(listOf("a", "b"), grid.cells[3].cell.manualLines)
        assertEquals(Styles.tableHeader, grid.cells[1].cell.style)
        assertEquals(TextAlign.CENTER, grid.cells[4].cell.align)
        assertEquals(grid, header.asGrid())
    }

    @Test
    fun `single-row form keeps the old IR and is the one-row grid`() {
        val header = build("header: {height: 10, repeat: false, cells: {a: A, b: B, c: C, d: D}}").header!!

        assertNull(header.grid)
        assertEquals(10.mm, header.height)
        assertEquals(false, header.repeat)
        assertEquals(listOf("A", "B", "C", "D"), header.cells.map { it.text })
        val grid = header.asGrid()!!
        assertEquals(listOf(10.mm), grid.rowHeights)
        assertEquals(header.cells, grid.cells.map { it.cell })
        assertEquals(listOf(0, 1, 2, 3), grid.cells.map { it.col })
        assertEquals(true, grid.cells.all { it.row == 0 && it.span == 1 && it.rowSpan == 1 })
    }

    @Test
    fun `an auto-height header has no grid`() {
        assertNull(IrTableHeader(listOf(IrCell("x"))).asGrid())
    }
}
