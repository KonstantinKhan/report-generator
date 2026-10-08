package dev.reportgenerator.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// `${line.number}` (root `line`, layout-derived) and `lines:` of a flow table: loading, validation and the contract.
// Every error carries a YAML path.
class LineNumbersTemplateTest {
    private fun yaml(table: String, other: String = "") =
        "sheet: {format: A4, margins: {top: 5, right: 5, bottom: 5, left: 20}}\nblocks:\n$other  - id: body\n    type: flow\n    table:\n" +
            table.trimIndent().prependIndent("      ")

    private val table = """
        rowHeight: 8
        columns:
          - {id: n, width: 15, align: center}
          - {id: name, width: 170}
        row:
          cells:
            n: {bind: "${'$'}{line.number}"}
            name: {bind: "${'$'}{item.name}"}
    """.trimIndent()

    private val numberCell = """n: {bind: "${'$'}{line.number}"}"""
    private val nameCell = """name: {bind: "${'$'}{item.name}"}"""

    private val schema = dataSchema { item { string("name") } }

    private fun with(extra: String) = table + "\n" + extra

    private fun spec(text: String): FlowTableSpec =
        (TemplateLoader.load(yaml(text)).blocks.single() as FlowBlock).table!!

    private fun errors(text: String, other: String = "") =
        TemplateValidator.validate(TemplateLoader.parse(yaml(text, other)), null).associate { it.path to it.message }

    @Test
    fun `line is a root with the layout derived number, always declared`() {
        assertEquals(DataType.Integer, DataSchema().typeOf("line.number"))
        assertTrue("line" in DATA_ROOTS)
        assertEquals(listOf("number"), DataSchema().roots.getValue("line").fields.keys.toList())
    }

    @Test
    fun `without a lines section the defaults apply and the cell is found`() {
        val t = spec(table)

        assertNull(t.lines)
        assertEquals("n", t.lineNumberColumn())
        assertEquals(FlowLines(start = 1, scope = LinesScope.TABLE, fill = false), FlowLines())
        assertEquals(emptyList(), TemplateContract.checkFlowTable(t, schema, "t"))
    }

    @Test
    fun `a table without a line number cell has no numbered column`() {
        val t = spec(table.replace(numberCell, "n: {text: x}"))

        assertNull(t.lineNumberColumn())
    }

    @Test
    fun `lines section loads start, scope and fill`() {
        val t = spec(with("lines: {start: 5, scope: page, fill: true}"))

        assertEquals(FlowLines(start = 5, scope = LinesScope.PAGE, fill = true), t.lines)
        assertEquals(emptyMap(), errors(with("lines: {start: 5, scope: page, fill: true}")))
    }

    @Test
    fun `lines keys and enums are validated with yaml paths`() {
        fun failure(extra: String) = assertFailsWith<TemplateException> { TemplateLoader.parse(yaml(with(extra))) }.errors.single()

        assertEquals("blocks[0].table.lines.scope", failure("lines: {scope: sheet}").path)
        assertTrue("expected table|page" in failure("lines: {scope: sheet}").message)
        assertEquals("blocks[0].table.lines.fill", failure("lines: {fill: maybe}").path)
        assertEquals("blocks[0].table.lines.start", failure("lines: {start: one}").path)
        assertEquals("blocks[0].table.lines.from", failure("lines: {from: 1}").path)
        assertEquals("blocks[0].table.lines.start", errors(with("lines: {start: -1}")).keys.single())
    }

    @Test
    fun `lines without a numbered cell is an error`() {
        val e = errors(with("lines: {start: 1}").replace(numberCell, "n: {text: x}"))

        assertEquals(setOf("blocks[0].table.lines"), e.keys)
        assertTrue("line.number" in e.getValue("blocks[0].table.lines"))
    }

    @Test
    fun `only one column shows the line number, no cases, no format`() {
        val two = errors(table.replace(nameCell, """name: {bind: "${'$'}{line.number}"}"""))
        assertEquals(setOf("blocks[0].table.row.cells.name.bind"), two.keys)

        val cases = errors(table.replace(numberCell, """n: {bind: "${'$'}{line.number}", cases: [{where: {field: name, eq: x}, text: "-"}]}"""))
        assertEquals(setOf("blocks[0].table.row.cells.n.cases"), cases.keys)

        val format = errors(table.replace(numberCell, """n: {bind: "${'$'}{line.number}", format: {pattern: "0"}}"""))
        assertEquals(setOf("blocks[0].table.row.cells.n.format"), format.keys)
    }

    @Test
    fun `line number is not allowed in a cases variant`() {
        val e = errors(table.replace(nameCell, """name: {bind: "${'$'}{item.name}", cases: [{where: {field: name, eq: x}, bind: "${'$'}{line.number}"}]}"""))

        assertEquals(setOf("blocks[0].table.row.cells.name.cases[0].bind"), e.keys)
    }

    @Test
    fun `other line fields do not exist`() {
        // the validator already limits the bind to ${'$'}{line.number}; the contract (schema lookup) has its own message
        val e = assertFailsWith<TemplateException> { TemplateLoader.load(yaml(table.replace("line.number", "line.count"))) }.errors.single()

        assertEquals("blocks[0].table.row.cells.n.bind", e.path)
        assertTrue("line.number" in e.message, e.toString())
    }

    @Test
    fun `line number outside a flow row cell is a contract error with the yaml path`() {
        val other = """
              - {id: t, type: text, bind: "${'$'}{line.number}", size: {width: 10, height: 8}}
              - id: tb
                type: table
                columns: [10]
                rows: [{height: 8, cells: [{bind: "${'$'}{line.number}"}]}]
        """.trimIndent().prependIndent("  ").let { it + "\n" }
        val template = TemplateLoader.load(yaml(table, other))
        val e = TemplateContract.check(template, schema).associate { it.path to it.message }

        assertEquals(setOf("blocks[0].bind", "blocks[1].rows[0].cells[0].bind"), e.keys)
        assertTrue("exists only in the row cells of a flow table" in e.getValue("blocks[0].bind"), e.toString())
    }

    @Test
    fun `the root line cannot be declared in a data file`() {
        val e = assertFailsWith<TemplateException> { DataYaml.parse("line: {number: 3}") }

        assertEquals("line", e.errors.single().path)
        assertNotNull(e.errors.single().message.takeIf { "layout engine" in it })
    }
}
