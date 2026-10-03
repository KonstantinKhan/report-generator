package dev.reportgenerator.ir

import dev.reportgenerator.template.ArithOp
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowComputed
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.Operand
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.dataSchema
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// YAML `totals:` + arithmetic `computed:` -> IrTable: group footers (IrGroup.footer), table footer (IrTable.footer),
// label / value columns, the other cells empty, style resolution.
class FlowTotalsIrTest {
    private val kinds = listOf("A", "B")
    private val schema = dataSchema { item { string("name"); enum("kind", kinds); integer("qty"); decimal("price") } }

    private fun spec(extra: String) = (TemplateLoader.load(
        """
        sheet: {format: A4, margins: {left: 20, right: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              styles: {sum: totalText}
              columns:
                - {id: name, width: 100}
                - {id: qty, width: 20, align: center}
                - {id: cost, width: 65}
              groupBy: {field: kind, order: [A, B], titles: {A: "Ах", B: "Бэ"}}
              groupTitle: {column: name, spacerBefore: 0, spacerAfter: 0}
              computed: {cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}}
              row:
                cells:
                  name: {bind: "${'$'}{item.name}"}
                  qty: {bind: "${'$'}{item.qty}"}
                  cost: {bind: "${'$'}{item.cost}", optional: true, format: {pattern: "0.00", locale: ru}}
        """.trimIndent() + "\n" + extra.trimIndent().prependIndent("      "),
        Styles.named.keys
    ).blocks.single() as FlowBlock).table!!

    private fun item(name: String, kind: String, qty: Long, price: String?) = dataContext {
        item { string("name", name); enum("kind", kinds, kind); integer("qty", qty); decimal("price", price?.let(::BigDecimal)) }
    }.get("item") as DataValue.Record

    private val rows = listOf(item("a", "A", 2, "1.50"), item("b", "A", 3, "2.00"), item("c", "B", 1, "0.99"))

    private val totals = """
        totals:
          - {id: g, scope: group, agg: sum, field: cost, label: "Итого по группе", labelColumn: name, valueColumn: cost, format: {pattern: "0.00", locale: ru}, style: sum}
          - {id: all, scope: table, agg: sum, field: cost, label: "Всего", labelColumn: name, valueColumn: cost, format: {pattern: "0.00", locale: ru}}
          - {id: n, scope: table, agg: count, label: "Позиций", labelColumn: name, valueColumn: qty}
    """

    @Test
    fun `group and table totals become footer rows`() {
        val table = FlowTables.build(spec(totals), schema, rows)

        val groups = table.content.map { it as IrGroup }
        // 2 * 1.50 + 3 * 2.00 = 9.00; 1 * 0.99 = 0.99
        assertEquals(listOf(listOf("Итого по группе", "", "9,00")), groups[0].footer.map { r -> r.cells.map { it.text } })
        assertEquals(listOf(listOf("Итого по группе", "", "0,99")), groups[1].footer.map { r -> r.cells.map { it.text } })
        // 9.00 + 0.99 = 9.99; 3 positions
        assertEquals(listOf(listOf("Всего", "", "9,99"), listOf("Позиций", "3", "")), table.footer.map { r -> r.cells.map { it.text } })
    }

    @Test
    fun `total cells carry the style, the column alignment and leave the other cells plain`() {
        val table = FlowTables.build(spec(totals), schema, rows)

        val group = (table.content.first() as IrGroup).footer.single()
        assertEquals(Styles.totalText, group.cells[0].style, "alias 'sum' -> totalText")
        assertEquals(Styles.totalText, group.cells[2].style)
        assertEquals(IrCell(""), group.cells[1], "other cells are empty, default style")
        // no style: the built-in totalText; align comes from the column
        val count = table.footer[1]
        assertEquals(Styles.totalText, count.cells[0].style)
        assertEquals(TextAlign.CENTER, count.cells[1].align)
        assertEquals(TextAlign.LEFT, count.cells[0].align)
    }

    @Test
    fun `no totals section builds no footer`() {
        val table = FlowTables.build(spec(""), schema, rows)
        assertTrue(table.footer.isEmpty() && table.content.all { (it as IrGroup).footer.isEmpty() })
        // the computed cost still reaches the cell
        assertEquals("3,00", (table.content[0] as IrGroup).rows[0].cells[2].text)
    }

    @Test
    fun `a total over a non numeric field fails with a yaml path`() {
        val bad = spec(
            """
            totals:
              - {id: g, scope: table, agg: sum, field: name, label: L, labelColumn: name, valueColumn: cost}
            """
        )
        val e = assertFailsWith<TemplateException> { FlowTables.build(bad, schema, rows, "blocks[0].table") }
        assertEquals(listOf("blocks[0].table.totals[0].field"), e.errors.map { it.path })
    }

    @Test
    fun `division by zero in an arithmetic field surfaces while building`() {
        val base = spec("")
        val each = FlowComputed.Arithmetic(ArithOp.DIVIDE, listOf(Operand.Field("price"), Operand.Literal("0")), 2, RoundingMode.HALF_UP)
        val s = base.copy(computed = base.computed + ("each" to each))
        val e = assertFailsWith<IllegalStateException> { FlowTables.build(s, schema, rows) }
        assertTrue("division by zero" in e.message!! && "source row 1" in e.message!!, e.message)
    }
}
