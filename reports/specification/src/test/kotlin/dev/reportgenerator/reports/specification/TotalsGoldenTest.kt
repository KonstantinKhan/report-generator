package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.PositionedImage
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.dataSchema
import java.io.File
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Golden dumps for flow-table totals (stage 4): group subtotal + grand total with cost = qty * price, a total row at
// a page bottom, avg / min / max / count on a flat table. The same text dump as StaticBlocksGoldenTest (every coordinate
// as raw 1/100 mm); a missing golden file is generated on the first run. The numbers of every fixture are also asserted
// below against values worked out by hand, so the golden cannot silently freeze a wrong total.
class TotalsGoldenTest {
    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private val kinds = listOf("FASTENER", "BEARING")
    private val schema = dataSchema { item { string("name"); enum("kind", kinds); integer("qty"); decimal("price") } }

    private fun item(name: String, kind: String, qty: Long, price: String?) = dataContext {
        item { string("name", name); enum("kind", kinds, kind); integer("qty", qty); decimal("price", price?.let(::BigDecimal)) }
    }.get("item") as DataValue.Record

    // Columns 10 + 85 + 15 + 35 + 40 = 185 mm = A4 content width (left 20, right 5).
    private fun spec(grouping: String, totals: String): String = """
        sheet: {format: A4, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              fill: blank
              columns:
                - {id: pos, width: 10, stick: first, align: center}
                - {id: name, width: 85}
                - {id: qty, width: 15, stick: last, align: center}
                - {id: price, width: 35, stick: last, align: center}
                - {id: cost, width: 40, stick: last, align: center}
              header:
                height: 10
                cells: {pos: "№", name: "Наименование", qty: "Кол.", price: "Цена", cost: "Сумма"}
              computed:
                pos: {sequence: {}}
                cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}
              row:
                cells:
                  pos: {bind: "${'$'}{item.pos}"}
                  name: {bind: "${'$'}{item.name}"}
                  qty: {bind: "${'$'}{item.qty}"}
                  price: {bind: "${'$'}{item.price}", optional: true, format: {pattern: "0.00", locale: ru}}
                  cost: {bind: "${'$'}{item.cost}", optional: true, format: {pattern: "0.00", locale: ru}}
        """.trimIndent() + "\n" + grouping.trimIndent().prependIndent("      ") + "\n" + totals.trimIndent().prependIndent("      ")

    private val groups = """
        groupBy:
          field: kind
          order: [FASTENER, BEARING]
          titles: {FASTENER: "Крепёж", BEARING: "Подшипники"}
        groupTitle: {column: name, spacerBefore: 1, spacerAfter: 1}
    """

    private val groupAndGrandTotals = """
        totals:
          - {id: groupSum, scope: group, agg: sum, field: cost, label: "Итого по группе", labelColumn: name, valueColumn: cost, format: {pattern: "0.00", locale: ru}}
          - {id: grandSum, scope: table, agg: sum, field: cost, label: "Всего", labelColumn: name, valueColumn: cost, format: {pattern: "0.00", locale: ru}}
          - {id: positions, scope: table, agg: count, label: "Позиций", labelColumn: name, valueColumn: qty}
    """

    private val aggregates = """
        totals:
          - {id: rows, scope: table, agg: count, label: "Строк", labelColumn: name, valueColumn: qty}
          - {id: pieces, scope: table, agg: sum, field: qty, label: "Штук", labelColumn: name, valueColumn: qty}
          - {id: priced, scope: table, agg: count, label: "Строк с ценой", labelColumn: name, valueColumn: qty, where: {field: price, notNull: true}}
          - {id: minPrice, scope: table, agg: min, field: price, label: "Минимальная цена", labelColumn: name, valueColumn: price, format: {pattern: "0.00", locale: ru}}
          - {id: maxPrice, scope: table, agg: max, field: price, label: "Максимальная цена", labelColumn: name, valueColumn: price, format: {pattern: "0.00", locale: ru}}
          - {id: avgPrice, scope: table, agg: avg, field: price, label: "Средняя цена", labelColumn: name, valueColumn: price, scale: 2, rounding: HALF_UP, format: {pattern: "0.00", locale: ru}}
          - {id: sumCost, scope: table, agg: sum, field: cost, label: "Сумма", labelColumn: name, valueColumn: cost, format: {pattern: "0.00", locale: ru}}
    """

    private fun document(yaml: String, rows: List<DataValue.Record>): IrDocument {
        val flow = TemplateLoader.load(yaml, Styles.named.keys).blocks.single() as FlowBlock
        val table = FlowTables.build(flow.table!!, schema, rows, "blocks[0].table")
        return IrDocument(PageSetup(PageFormat.A4, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table))
    }

    // Fixture 1. Крепёж: Болт 4 * 3.25 = 13.00, Гайка 10 * 1.10 = 11.00, Шайба 3 * 0.335 = 1.005 -> 1.01 (HALF_UP);
    // sum 25.01. Подшипники: 2 * 150.50 = 301.00, 1 * 99.99 = 99.99, a row without price (no cost); sum 400.99.
    // Grand total 25.01 + 400.99 = 426.00, 6 positions.
    private val groupAndGrandRows = listOf(
        item("Болт М6", "FASTENER", 4, "3.25"),
        item("Подшипник 6204", "BEARING", 2, "150.50"),
        item("Гайка М6", "FASTENER", 10, "1.10"),
        item("Шайба 6", "FASTENER", 3, "0.335"),
        item("Подшипник 6000", "BEARING", 1, "99.99"),
        item("Кабель", "BEARING", 5, null)
    )

    // Fixture 2. A4 has 34 rows of 8 mm under the 10 mm header (15..292 mm). "Крепёж": blank, title, blank + 31 lines
    // fill page 1 to the last row, so its total would be row 35: the last line moves to page 2 together with it.
    // Lines 1..31 cost 1 * i = i, sum 31 * 32 / 2 = 496.00. "Подшипники": 2 lines, 2 * 5.00 = 10.00 each, sum 20.00.
    // Grand total 516.00, 33 positions.
    private val pageBreakRows =
        (1..31).map { item("Деталь $it", "FASTENER", 1, "$it.00") } + listOf(item("Втулка 1", "BEARING", 2, "5.00"), item("Втулка 2", "BEARING", 2, "5.00"))

    // Fixture 3 (flat, no groupBy): qty 2, 9, 5, 1; price 10.00, 20.00, 5.00, none; cost 20.00, 180.00, 25.00, none.
    // rows 4, pieces 2 + 9 + 5 + 1 = 17, priced rows 3, min 5.00, max 20.00, avg (10 + 20 + 5) / 3 = 11.666.. -> 11.67,
    // cost 20 + 180 + 25 = 225.00.
    private val flatRows = listOf(
        item("Вал", "FASTENER", 2, "10.00"),
        item("Ось", "FASTENER", 9, "20.00"),
        item("Втулка", "FASTENER", 5, "5.00"),
        item("Кабель", "FASTENER", 1, null)
    )

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "totals-group-and-grand" to document(spec(groups, groupAndGrandTotals), groupAndGrandRows),
        "totals-page-break" to document(spec(groups, groupAndGrandTotals), pageBreakRows),
        "totals-aggregates-flat" to document(spec("", aggregates), flatRows)
    )

    private fun dump(doc: LaidOutDocument): String = buildString {
        fun L(l: Length) = l.raw.toString()
        fun R(r: Rect) = "${L(r.x)} ${L(r.y)} ${L(r.width)} ${L(r.height)}"
        for (page in doc.pages) {
            appendLine("PAGE ${page.number} ${page.format.name} ${L(page.format.width)} ${L(page.format.height)}")
            for (e in page.elements) when (e) {
                is PositionedText -> appendLine("T \"${e.text}\" ${R(e.rect)} ${e.style.font.id} ${e.style.sizePt} i=${e.style.italic} ${e.orientation}")
                is Line -> appendLine("L ${L(e.from.x)} ${L(e.from.y)} ${L(e.to.x)} ${L(e.to.y)} w=${L(e.style.width)} ${e.style.color}")
                is Rectangle -> appendLine("R ${R(e.rect)} w=${L(e.style.width)} ${e.style.color}")
                is PositionedImage -> appendLine("I ${R(e.rect)} ${e.image.id}")
            }
        }
    }

    private val laidOut: Map<String, LaidOutDocument> by lazy { fixtures().mapValues { (_, doc) -> layOut(doc, measurer, fonts::resolve) } }

    private fun texts(name: String, page: Int) = laidOut.getValue(name).pages[page - 1].elements.filterIsInstance<PositionedText>().map { it.text }

    @Test
    fun `layout of totals matches golden dumps`() {
        for ((name, doc) in laidOut) {
            val actual = dump(doc)
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }

    @Test
    fun `group subtotals and the grand total match the hand calculation`() {
        assertEquals(1, laidOut.getValue("totals-group-and-grand").pages.size)
        val t = texts("totals-group-and-grand", 1)
        // rows in table order: Крепёж (source order inside the group), then Подшипники
        val body = t.drop(t.indexOf("Сумма") + 1)
        assertEquals(
            listOf(
                "Крепёж",
                "1", "Болт М6", "4", "3,25", "13,00",
                "2", "Гайка М6", "10", "1,10", "11,00",
                "3", "Шайба 6", "3", "0,34", "1,01",
                "Итого по группе", "25,01",
                "Подшипники",
                "4", "Подшипник 6204", "2", "150,50", "301,00",
                "5", "Подшипник 6000", "1", "99,99", "99,99",
                "6", "Кабель", "5",
                "Итого по группе", "400,99",
                "Всего", "426,00",
                "Позиций", "6"
            ),
            body
        )
    }

    @Test
    fun `a total at a page bottom moves with the last line of its group`() {
        val doc = laidOut.getValue("totals-page-break")
        assertEquals(2, doc.pages.size)
        val page1 = texts("totals-page-break", 1)
        val page2 = texts("totals-page-break", 2)
        assertTrue("Деталь 30" in page1 && "Деталь 31" !in page1, "line 31 left page 1 with its total")
        assertTrue("Итого по группе" !in page1)
        // page 2 after the header: the pulled line 31 (qty 1, price 31.00, cost 31.00), the subtotal, the next group
        val body = page2.drop(page2.indexOf("Сумма") + 1)
        assertEquals(
            listOf(
                "31", "Деталь 31", "1", "31,00", "31,00",
                "Итого по группе", "496,00",
                "Подшипники",
                "32", "Втулка 1", "2", "5,00", "10,00",
                "33", "Втулка 2", "2", "5,00", "10,00",
                "Итого по группе", "20,00",
                "Всего", "516,00",
                "Позиций", "33"
            ),
            body
        )
    }

    @Test
    fun `avg, min, max, count and sum on a flat table match the hand calculation`() {
        val t = texts("totals-aggregates-flat", 1)
        val body = t.drop(t.indexOf("Сумма") + 1)
        assertEquals(
            listOf(
                "1", "Вал", "2", "10,00", "20,00",
                "2", "Ось", "9", "20,00", "180,00",
                "3", "Втулка", "5", "5,00", "25,00",
                "4", "Кабель", "1",
                "Строк", "4",
                "Штук", "17",
                "Строк с ценой", "3",
                "Минимальная цена", "5,00",
                "Максимальная цена", "20,00",
                "Средняя цена", "11,67",
                "Сумма", "225,00"
            ),
            body
        )
    }
}
