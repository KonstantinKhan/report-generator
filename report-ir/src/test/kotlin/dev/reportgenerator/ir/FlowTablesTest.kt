package dev.reportgenerator.ir

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.frames.GostSpecTemplate
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.dataSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// YAML flow spec -> IrTable: styles resolution, defaults, optional binds, errors with YAML paths.
class FlowTablesTest {
    private val schema = dataSchema { item { integer("n"); string("name"); string("note"); enum("kind", listOf("G")) } }

    private fun groups(grouped: Boolean, titleStyle: String) = if (!grouped) "" else
        "                  groupBy: {field: kind, order: [G], titles: {G: \"Детали\"}}\n" +
            "                  groupTitle: {column: name, style: $titleStyle, spacerBefore: 1, spacerAfter: 0}"

    private fun spec(extra: String = "", cell: String = "{bind: \"\${item.name}\", style: data}", titleStyle: String = "groupTitle", rowStyle: String = "tableText", grouped: Boolean = true) =
        (TemplateLoader.load(
            """
            sheet: {format: A4, margins: {left: 20, right: 5}}
            blocks:
              - id: body
                type: flow
                table:
                  rowHeight: 6
                  styles: {data: $rowStyle, groupTitle: groupHeader, head: tableHeader}
                  columns:
                    - {id: n, width: 15, align: center}
                    - {id: name, width: 170}
            $extra
${groups(grouped, titleStyle)}
                  row:
                    cells:
                      n: {bind: "${'$'}{item.n}"}
                      name: $cell
            """.trimIndent().replace("\n            ", "\n"),
            Styles.named.keys
        ).blocks.single() as FlowBlock).table!!

    private fun item(n: Long, name: String, note: String? = null) = dataContext {
        item { integer("n", n); string("name", name); string("note", note); enum("kind", listOf("G"), "G") }
    }.get("item") as DataValue.Record

    @Test
    fun `builds columns, rows and group title from the spec`() {
        val table = FlowTables.build(spec(), schema, listOf(item(1, "Вал"), item(2, "Ось")))

        assertEquals(listOf(15.mm, 170.mm), table.columns.map { it.width })
        assertEquals(6.mm, table.rowHeight)
        assertEquals(IrGroupTitle("name", Styles.groupHeader, TextAlign.CENTER, 1, 0, true), table.groupTitle)
        assertEquals(false, table.fillBlank)
        val rows = (table.content.single() as IrGroup).rows
        assertEquals(listOf(listOf("1", "Вал"), listOf("2", "Ось")), rows.map { r -> r.cells.map { it.text } })
        assertEquals(listOf(TextAlign.CENTER, TextAlign.LEFT), rows[0].cells.map { it.align })
        assertEquals(Styles.tableText, rows[0].cells[1].style)
        assertEquals(null, table.header)
    }

    @Test
    fun `a missing optional value renders empty, a missing required one fails`() {
        val optional = spec(cell = "{bind: \"\${item.note}\", optional: true}")
        val row = (FlowTables.build(optional, schema, listOf(item(1, "a"))).content.single() as IrGroup).rows.single()
        assertEquals("", row.cells[1].text)

        val required = spec(cell = "{bind: \"\${item.note}\"}")
        assertFailsWith<IllegalStateException> { FlowTables.build(required, schema, listOf(item(1, "a"))) }
    }

    @Test
    fun `a missing required value names the source row and its fields`() {
        val required = spec(cell = "{bind: \"\${item.note}\"}")
        val rows = listOf(item(1, "a", "x"), item(2, "b"))
        val e = assertFailsWith<IllegalStateException> { FlowTables.build(required, schema, rows) }
        val message = e.message!!
        assertTrue(message.startsWith("source row 2 {") && "name=b" in message && "no value for bind" in message && "item.note" in message, message)
    }

    @Test
    fun `style resolves through an alias or directly`() {
        val direct = spec(rowStyle = "heading", cell = "{bind: \"\${item.name}\", style: heading}")
        val cell = (FlowTables.build(direct, schema, listOf(item(1, "a"))).content.single() as IrGroup).rows.single().cells[1]
        assertEquals(Styles.heading, cell.style)

        val aliased = spec(rowStyle = "designation")
        val c2 = (FlowTables.build(aliased, schema, listOf(item(1, "a"))).content.single() as IrGroup).rows.single().cells[1]
        assertEquals(Styles.designation, c2.style)
    }

    @Test
    fun `an unknown style fails with the yaml path`() {
        // not caught by the loader when no known set is given
        val loose = (TemplateLoader.load(
            """
            sheet: {format: A4, margins: {left: 20, right: 5}}
            blocks:
              - id: body
                type: flow
                table:
                  rowHeight: 6
                  columns: [{id: n, width: 185}]
                  row: {cells: {n: {text: x, style: nope}}}
            """.trimIndent()
        ).blocks.single() as FlowBlock).table!!
        val e = assertFailsWith<TemplateException> { FlowTables.build(loose, schema, emptyList(), "blocks[0].table") }
        assertEquals("blocks[0].table.row.cells.n.style", e.errors.single().path)

        // and the loader with the known set rejects it up front
        val rejected = assertFailsWith<TemplateException> {
            TemplateLoader.load(
                "sheet: {format: A4, margins: {left: 20, right: 5}}\nblocks:\n  - id: body\n    type: flow\n    table:\n      rowHeight: 6\n" +
                    "      columns: [{id: n, width: 185}]\n      row: {cells: {n: {text: x, style: nope}}}",
                Styles.named.keys
            )
        }
        assertEquals("blocks[0].table.row.cells.n.style", rejected.errors.single().path)
    }

    @Test
    fun `header cells map rotate, lines, align and style`() {
        val withHeader = spec(
            """
                  header:
                    height: 12
                    repeat: false
                    cells:
                      n: {text: "№", rotate: 90, style: head}
                      name: {text: "Наименование", lines: ["Наиме-", "нование"], align: left}
            """.trimEnd()
        )
        val header = requireNotNull(FlowTables.build(withHeader, schema, emptyList()).header)

        assertEquals(12.mm, header.height)
        assertEquals(false, header.repeat)
        assertEquals(IrCell("№", Styles.tableHeader, TextOrientation.VERTICAL_BOTTOM_TO_TOP, TextAlign.CENTER), header.cells[0])
        assertEquals(IrCell("Наименование", Styles.tableHeader, TextOrientation.HORIZONTAL, TextAlign.LEFT, listOf("Наиме-", "нование")), header.cells[1])
    }

    @Test
    fun `a table without groupBy is a flat row list`() {
        val table = FlowTables.build(spec(grouped = false), schema, listOf(item(1, "a"), item(2, "b")))
        assertEquals(2, table.content.size)
        assertTrue(table.content.all { it is IrRow })
    }

    @Test
    fun `gost-spec flow block is the specification table`() {
        val spec = GostSpecTemplate.flowTable
        assertEquals(8.0, spec.rowHeight)
        assertEquals(listOf("format", "zone", "position", "designation", "name", "quantity", "note"), spec.columns.map { it.id })
        assertEquals("blocks[${GostSpecTemplate.template.blocks.indexOfFirst { it is FlowBlock }}].table", GostSpecTemplate.flowTablePath)
    }

    private fun numberedSpec(lines: String = "") = spec(
        extra = lines,
        cell = "{bind: \"\${item.name}\", style: data}",
        grouped = false
    )

    @Test
    fun `a line number cell stays empty in the IR and the table numbers its lines with the defaults`() {
        val withNumber = (TemplateLoader.load(
            """
            sheet: {format: A4, margins: {left: 20, right: 5}}
            blocks:
              - id: body
                type: flow
                table:
                  rowHeight: 6
                  styles: {data: tableText}
                  columns:
                    - {id: n, width: 15, align: center}
                    - {id: name, width: 170}
                  row:
                    cells:
                      n: {bind: "${'$'}{line.number}", style: data}
                      name: {bind: "${'$'}{item.name}"}
            """.trimIndent(),
            Styles.named.keys
        ).blocks.single() as FlowBlock).table!!

        val table = FlowTables.build(withNumber, schema, listOf(item(1, "Вал"), item(2, "Ось")))

        assertEquals(IrLineNumbers("n", 1, IrLineScope.TABLE, false, Styles.tableText, TextAlign.CENTER), table.lineNumbers)
        assertEquals(listOf(listOf("", "Вал"), listOf("", "Ось")), table.content.map { r -> (r as IrRow).cells.map { it.text } })
    }

    @Test
    fun `lines section reaches the IR`() {
        val yaml = """
            sheet: {format: A4, margins: {left: 20, right: 5}}
            blocks:
              - id: body
                type: flow
                table:
                  rowHeight: 6
                  columns: [{id: n, width: 15}, {id: name, width: 170}]
                  lines: {start: 10, scope: page, fill: true}
                  row:
                    cells:
                      n: {bind: "${'$'}{line.number}"}
                      name: {bind: "${'$'}{item.name}"}
        """.trimIndent()
        val t = (TemplateLoader.load(yaml, Styles.named.keys).blocks.single() as FlowBlock).table!!

        val lines = FlowTables.build(t, schema, listOf(item(1, "Вал"))).lineNumbers
        assertEquals(IrLineNumbers("n", 10, IrLineScope.PAGE, true, Styles.tableText, TextAlign.LEFT), lines)
    }

    @Test
    fun `a table without a line number cell has no line numbers`() {
        assertEquals(null, FlowTables.build(numberedSpec(), schema, listOf(item(1, "Вал"))).lineNumbers)
    }
}
