package dev.reportgenerator.ir

import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.dataSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// YAML `subTitle:` -> IrTable.subTitle (look) and IrRow.subTitle (text before the row that starts a new sub-group).
class FlowSubTitleIrTest {
    private val kinds = listOf("A", "B")
    private val schema = dataSchema { item { string("name"); enum("kind", kinds); string("sub"); integer("n") } }

    private fun spec(subTitle: String) = (TemplateLoader.load(
        """
        sheet: {format: A4, margins: {left: 20, right: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              columns: [{id: pos, width: 10}, {id: name, width: 175}]
              groupBy: {field: kind, order: [A, B], titles: {A: "Ах", B: "Бэ"}}
              groupTitle: {column: name}
              computed: {pos: {sequence: {}}}
              row:
                cells:
                  pos: {bind: "${'$'}{item.pos}"}
                  name: {bind: "${'$'}{item.name}"}
        """.trimIndent() + "\n" + subTitle.trimIndent().prependIndent("      "),
        Styles.named.keys
    ).blocks.single() as FlowBlock).table!!

    private fun item(name: String, kind: String, sub: String?) = dataContext {
        item { string("name", name); enum("kind", kinds, kind); string("sub", sub); integer("n") }
    }.get("item") as DataValue.Record

    private val rows = listOf(item("a", "A", "X"), item("b", "A", "X"), item("c", "A", "Y"), item("d", "B", null), item("e", "B", "Y"))

    @Test
    fun `rows carry the sub-title text where the value changes, the look comes from the spec`() {
        val table = FlowTables.build(spec("subTitle: {field: sub, style: tableText, align: left}"), schema, rows)

        assertEquals(IrSubTitle("name", Styles.tableText, TextAlign.LEFT), table.subTitle)
        assertEquals(
            listOf(listOf("X", null, "Y"), listOf(null, "Y")),
            table.content.map { g -> (g as IrGroup).rows.map { it.subTitle } }
        )
    }

    @Test
    fun `the sub-title row has no position and the numbering is unchanged`() {
        val table = FlowTables.build(spec("subTitle: {field: sub}"), schema, rows)

        assertEquals(listOf("1", "2", "3", "4", "5"), table.content.flatMap { g -> (g as IrGroup).rows.map { it.cells[0].text } })
        assertEquals(Styles.groupHeader, table.subTitle?.style, "default style is the group title's")
        assertEquals(TextAlign.CENTER, table.subTitle?.align)
    }

    @Test
    fun `without subTitle nothing changes`() {
        val table = FlowTables.build(spec(""), schema, rows)

        assertNull(table.subTitle)
        assertEquals(true, table.content.all { g -> (g as IrGroup).rows.all { it.subTitle == null } })
    }

    @Test
    fun `an unknown field or a non-string field is a contract error with the path`() {
        val unknown = assertFailsWith<TemplateException> { FlowTables.build(spec("subTitle: {field: nope}"), schema, rows, "t") }
        assertEquals(listOf("t.subTitle.field"), unknown.errors.map { it.path })
        val number = assertFailsWith<TemplateException> { FlowTables.build(spec("subTitle: {field: n}"), schema, rows, "t") }
        assertEquals(listOf("t.subTitle.field"), number.errors.map { it.path })
    }
}
