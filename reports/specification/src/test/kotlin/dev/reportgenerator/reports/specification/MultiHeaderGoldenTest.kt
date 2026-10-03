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
import dev.reportgenerator.layoutir.TextOrientation
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.dataSchema
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Golden dumps for the multi-level header of a flow table (`header.rows`, `span` / `rowSpan`): the same text dump as
// StaticBlocksGoldenTest (raw 1/100 mm); a missing golden file is generated on the first run. Every fixture is also
// asserted below against rectangles worked out by hand from the column widths and the row heights.
//
// Fixture "header-multi-a3" (owner's header): A3 landscape 420 x 297, margins left 20 / top 5 / right 5 / bottom 5, content
// 395 mm wide. 11 columns [7, 60, 45, 70, 55, 70, 16, 16, 16, 16, 24] -> column left edges 20, 27, 87, 132, 202, 257, 327,
// 343, 359, 375, 391 and the right edge 415. Header rows 9 + 18 = 27 mm (5..14, 14..32), data rows of 8 mm from y = 32.
// "Количество" spans columns 6..9 (x 327, width 64), the other 7 cells span both rows (height 27).
class MultiHeaderGoldenTest {
    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private val schema = dataSchema { item { string("name"); string("qty") } }

    private fun item(name: String, qty: String) =
        dataContext { item { string("name", name); string("qty", qty) } }.get("item") as DataValue.Record

    private fun document(yaml: String, rows: List<DataValue.Record>, format: PageFormat): IrDocument {
        val flow = TemplateLoader.load(yaml, Styles.named.keys).blocks.single() as FlowBlock
        val table = FlowTables.build(flow.table!!, schema, rows, "blocks[0].table")
        return IrDocument(PageSetup(format, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table))
    }

    private val a3Landscape = PageFormat("A3", 420.mm, 297.mm)

    private val owner = """
        sheet: {format: A3, orientation: landscape, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              columns:
                - {id: n, width: 7, align: center}
                - {id: name, width: 60}
                - {id: productCode, width: 45}
                - {id: docDesignation, width: 70}
                - {id: contractor, width: 55}
                - {id: inclusion, width: 70}
                - {id: countPerProduct, width: 16, align: center}
                - {id: countPerSet, width: 16, align: center}
                - {id: countPerSetting, width: 16, align: center}
                - {id: total, width: 16, align: center}
                - {id: note, width: 24}
              header:
                rows:
                  - height: 9
                    cells:
                      - {text: "№ строки", rowSpan: 2, rotate: 90}
                      - {text: "Наименование", rowSpan: 2}
                      - {text: "Код продукции", rowSpan: 2}
                      - {text: "Обозначение документа", rowSpan: 2}
                      - {text: "Подрядчик", rowSpan: 2}
                      - {text: "Входимость", rowSpan: 2}
                      - {text: "Количество", span: 4}
                      - {lines: ["Приме-", "чание"], text: "Примечание", rowSpan: 2}
                  - height: 18
                    cells:
                      - {lines: ["на из-", "делие"], text: "на изделие"}
                      - {lines: ["на", "комплект"], text: "на комплект"}
                      - {lines: ["на регу-", "лиров."], text: "на регулир."}
                      - {text: "всего"}
              row:
                cells:
                  n: {bind: "${'$'}{line.number}"}
                  name: {bind: "${'$'}{item.name}"}
                  productCode: ~
                  docDesignation: ~
                  contractor: ~
                  inclusion: ~
                  countPerProduct: {bind: "${'$'}{item.qty}"}
                  countPerSet: ~
                  countPerSetting: ~
                  total: ~
                  note: ~
              lines: {}
    """.trimIndent()

    // Fixture "header-rotated-rowspan": A4, columns a 10 / b 30 / c 145 (x 20, 30, 60..205), rows 8 + 12 = 20 mm (5..13, 13..25).
    // "Позиция" is rotated and spans both rows: its rectangle is 10 x 20, the text is centred in it.
    private val rotated = """
        sheet: {format: A4, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              columns:
                - {id: a, width: 10}
                - {id: b, width: 30}
                - {id: c, width: 145}
              header:
                rows:
                  - height: 8
                    cells:
                      - {text: "Позиция", rowSpan: 2, rotate: 90}
                      - {text: "Группа", span: 2}
                  - height: 12
                    cells:
                      - {text: "Имя"}
                      - {text: "Кол."}
              row:
                cells:
                  a: ~
                  b: {bind: "${'$'}{item.name}"}
                  c: {bind: "${'$'}{item.qty}"}
    """.trimIndent()

    // Fixture "header-multi-pages": A4, columns a 20 / b 100 / c 65 (x 20, 40, 140..205), rows 6 + 8 = 14 mm (5..11, 11..19),
    // data rows of 30 mm: (287 - 14) / 30 = 9 per page -> 20 rows = 3 pages (9 + 9 + 2). `repeat` defaults to true.
    private val pages = """
        sheet: {format: A4, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 30
              columns:
                - {id: a, width: 20}
                - {id: b, width: 100}
                - {id: c, width: 65}
              header:
                rows:
                  - height: 6
                    cells:
                      - {text: "Поз.", rowSpan: 2}
                      - {text: "Сведения", span: 2}
                  - height: 8
                    cells:
                      - {text: "Наименование"}
                      - {text: "Количество"}
              row:
                cells:
                  a: ~
                  b: {bind: "${'$'}{item.name}"}
                  c: {bind: "${'$'}{item.qty}"}
    """.trimIndent()

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "header-multi-a3" to document(owner, listOf(item("Вал", "2"), item("Втулка", "10")), a3Landscape),
        "header-rotated-rowspan" to document(rotated, listOf(item("Вал", "2")), PageFormat.A4),
        "header-multi-pages" to document(pages, (1..20).map { item("Деталь $it", "$it") }, PageFormat.A4)
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

    // Thick rectangles of the header (the page frame has a different rectangle and is not among them): (x, y, w, h) in mm
    private fun headerRects(name: String, page: Int = 1, bottomMm: Int): List<List<Int>> =
        laidOut.getValue(name).pages[page - 1].elements.filterIsInstance<Rectangle>()
            .filter { it.rect.y.raw < bottomMm * 100L && it.style.width.raw == 71L && it.rect.height < 100.mm }
            .map { listOf(it.rect.x.raw.toInt(), it.rect.y.raw.toInt(), it.rect.width.raw.toInt(), it.rect.height.raw.toInt()) }

    private fun headerTexts(name: String, page: Int = 1, bottomMm: Int): List<PositionedText> =
        laidOut.getValue(name).pages[page - 1].elements.filterIsInstance<PositionedText>().filter { it.rect.y.raw < bottomMm * 100L }

    @Test
    fun `layout of multi-level headers matches golden dumps`() {
        for ((name, doc) in laidOut) {
            val actual = dump(doc)
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }

    @Test
    fun `owner header - merged rectangles from column widths and row heights`() {
        // x, y, w, h in 1/100 mm: seven cells over both rows (y 5, h 27), "Количество" over 4 columns (w 4 x 16), 4 cells in row 2
        val expected = listOf(
            listOf(2000, 500, 700, 2700),     // n: x 20, w 7
            listOf(2700, 500, 6000, 2700),    // name: x 27, w 60
            listOf(8700, 500, 4500, 2700),    // productCode: x 87, w 45
            listOf(13200, 500, 7000, 2700),   // docDesignation: x 132, w 70
            listOf(20200, 500, 5500, 2700),   // contractor: x 202, w 55
            listOf(25700, 500, 7000, 2700),   // inclusion: x 257, w 70
            listOf(32700, 500, 6400, 900),    // Количество: x 327, w 64, h 9
            listOf(32700, 1400, 1600, 1800),  // row 2: x 327, y 14, w 16, h 18
            listOf(34300, 1400, 1600, 1800),
            listOf(35900, 1400, 1600, 1800),
            listOf(37500, 1400, 1600, 1800),
            listOf(39100, 500, 2400, 2700)    // note: x 391, w 24
        )
        assertEquals(expected.sortedBy { it[0] * 100000 + it[1] }, headerRects("header-multi-a3", bottomMm = 32).sortedBy { it[0] * 100000 + it[1] })
        // the last right edge is the content right edge (415 mm), the header bottom is 32 mm = the first data row top
        assertEquals(41500, 39100 + 2400)
        val doc = laidOut.getValue("header-multi-a3").pages.single()
        val firstRowTop = doc.elements.filterIsInstance<Line>().first { it.from.y.raw >= 3200 }.from.y.raw
        assertEquals(3200L, firstRowTop)
    }

    @Test
    fun `owner header - texts are centred in the merged rectangles`() {
        val texts = headerTexts("header-multi-a3", bottomMm = 32).associateBy { it.text }
        fun centered(text: String, x: Int, y: Int, w: Int, h: Int) {
            val r = texts.getValue(text).rect
            assertEquals((x + w / 2).toDouble(), r.x.raw + r.width.raw / 2.0, 1.0, "$text centre x")
            assertEquals((y + h / 2).toDouble(), r.y.raw + r.height.raw / 2.0, 1.0, "$text centre y")
        }
        centered("Наименование", 2700, 500, 6000, 2700)
        centered("Количество", 32700, 500, 6400, 900)
        // lines of a manual break: the block of two lines is centred in 16 x 18 mm, each line in the width
        listOf("на из-", "делие").let { l ->
            val a = texts.getValue(l[0]).rect
            val b = texts.getValue(l[1]).rect
            assertEquals(a.y + a.height, b.y)
            assertEquals(1400 + 900.0, a.y.raw + (a.height.raw + b.height.raw) / 2.0, 1.0)
            assertEquals(32700 + 800.0, a.x.raw + a.width.raw / 2.0, 1.0)
        }
        // rotated "№ строки": text box centred in 7 x 27 mm (x 20..27, y 5..32), reads bottom to top
        val rot = texts.getValue("№ строки")
        assertEquals(TextOrientation.VERTICAL_BOTTOM_TO_TOP, rot.orientation)
        assertEquals(2000 + 350.0, rot.rect.x.raw + rot.rect.width.raw / 2.0, 1.0)
        assertEquals(500 + 1350.0, rot.rect.y.raw + rot.rect.height.raw / 2.0, 1.0)
    }

    @Test
    fun `owner header - data rows start under the total height, the table has the 2 rows`() {
        val doc = laidOut.getValue("header-multi-a3").pages.single()
        val data = doc.elements.filterIsInstance<PositionedText>().filter { it.rect.y.raw >= 3200 }
        // line numbers 1 and 2 at x 20..27, the rows are 32..40 and 40..48 mm; a 3.5 mm line is 4.2 high, 1.9 mm from the row top
        val numbers = data.filter { it.rect.x.raw in 2000..2700 }
        assertEquals(listOf("1", "2"), numbers.map { it.text })
        assertEquals(listOf(3200 + 190, 4000 + 190), numbers.map { it.rect.y.raw.toInt() })
    }

    @Test
    fun `rotated text in a rowSpan cell is centred in the merged rectangle`() {
        // rows 8 + 12: "Позиция" rectangle x 20, y 5, w 10, h 20; "Группа" x 30, y 5, w 175, h 8; Имя / Кол. y 13, h 12 (x 30 w 30, x 60 w 145)
        assertEquals(
            listOf(listOf(2000, 500, 1000, 2000), listOf(3000, 500, 17500, 800), listOf(3000, 1300, 3000, 1200), listOf(6000, 1300, 14500, 1200)),
            headerRects("header-rotated-rowspan", bottomMm = 25).sortedWith(compareBy({ it[1] }, { it[0] }))
        )
        val rot = headerTexts("header-rotated-rowspan", bottomMm = 25).single { it.text == "Позиция" }
        assertEquals(TextOrientation.VERTICAL_BOTTOM_TO_TOP, rot.orientation)
        // centre of the 10 x 20 rectangle: (2500, 1500)
        assertEquals(2500.0, rot.rect.x.raw + rot.rect.width.raw / 2.0, 1.0)
        assertEquals(1500.0, rot.rect.y.raw + rot.rect.height.raw / 2.0, 1.0)
        // the run length of the text is measured against the merged height (20 mm), so it fits there
        assertTrue(rot.rect.height < 20.mm)
    }

    @Test
    fun `header repeats on every page with the same geometry and rows start under it`() {
        val doc = laidOut.getValue("header-multi-pages")
        assertEquals(3, doc.pages.size)
        val expected = listOf(listOf(2000, 500, 2000, 1400), listOf(4000, 500, 16500, 600), listOf(4000, 1100, 10000, 800), listOf(14000, 1100, 6500, 800))
        for (p in 1..3) {
            assertEquals(expected.sortedBy { it[0] * 100000 + it[1] }, headerRects("header-multi-pages", p, bottomMm = 19).sortedBy { it[0] * 100000 + it[1] }, "page $p")
        }
        // first data row (30 mm) of every page starts at 5 + 14 = 19 mm: the name text is 1.9 + (30 - 8)/2... centred vertically in the row
        for (p in 1..3) {
            val first = doc.pages[p - 1].elements.filterIsInstance<PositionedText>().filter { it.rect.y.raw >= 1900 }.minBy { it.rect.y.raw }
            assertEquals(1900 + 1500.0, first.rect.y.raw + first.rect.height.raw / 2.0, 1.0, "page $p first row centre")
        }
        assertEquals(listOf(9, 9, 2), doc.pages.map { page -> page.elements.filterIsInstance<PositionedText>().count { it.text.startsWith("Деталь") } })
    }
}
