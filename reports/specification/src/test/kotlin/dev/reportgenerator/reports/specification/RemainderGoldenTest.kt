package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FrameBindings
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameSpec
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
import kotlin.test.Test
import kotlin.test.assertEquals

// Golden dumps for `remainder: gap | stretch` of a `fill: blank` flow table that ends above a stamp: the same text dump as
// StaticBlocksGoldenTest (raw 1/100 mm); a missing golden file is generated on the first run. The geometry of every
// fixture is also asserted below against numbers worked out by hand, so the golden cannot freeze a wrong one.
//
// A4 portrait 210 x 297, margins left 20 / top 5 / right 5 / bottom 5: frame 20..205 x 5..292, content 185 mm wide. The
// stamp (FrameSpec 185 x 17 mm, on every page) is bottom-right and reserves its height: the flow region is y = 5..275
// (270 mm). One column of 185 mm, rowHeight 8 mm, no header.
//
//  - "one page": 3 records end at 5 + 3 * 8 = 29. 275 - 29 = 246 = 30 * 8 + 6: 30 filler rows, leftover 6 mm.
//      gap:     rows are exactly 8 mm, the last one is 261..269, a 6 mm gap 269..275 stays above the stamp.
//      stretch: the last filler row is 261..275 = 8 + 6 = 14 mm tall and touches the stamp.
//  - "two pages": 40 records. Page 1 holds 270 / 8 = 33 rows (5..269), 6 mm are less than a row -> no filler. Page 2 holds
//    the remaining 7 records (5..61), 275 - 61 = 214 = 26 * 8 + 6: 26 filler rows, the last one 261..269 (gap) /
//    261..275 (stretch), the same 6 mm.
class RemainderGoldenTest {
    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private val schema = dataSchema { item { string("name") } }

    private fun item(name: String) = dataContext { item { string("name", name) } }.get("item") as DataValue.Record

    private fun spec(remainder: String) = """
        sheet: {format: A4, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              fill: blank
              remainder: $remainder
              columns:
                - {id: name, width: 185}
              row:
                cells:
                  name: {bind: "${'$'}{item.name}"}
    """.trimIndent()

    private val stamp = FrameSpec(Size(185.mm, 17.mm), listOf(FrameCell.Constant(Rect(0.mm, 0.mm, 185.mm, 17.mm), "Штамп")))

    private fun document(remainder: String, records: Int): IrDocument {
        val flow = TemplateLoader.load(spec(remainder), Styles.named.keys).blocks.single() as FlowBlock
        val table = FlowTables.build(flow.table!!, schema, (1..records).map { item("Деталь $it") }, "blocks[0].table")
        val setup = PageSetup(
            PageFormat.A4, Insets(5.mm, 5.mm, 5.mm, 20.mm),
            frame = stamp, continuationFrame = stamp, dataContext = FrameBindings("XXX.1", "Имя")
        )
        return IrDocument(setup, listOf(table))
    }

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "remainder-gap-one-page" to document("gap", 3),
        "remainder-stretch-one-page" to document("stretch", 3),
        "remainder-gap-two-pages" to document("gap", 40),
        "remainder-stretch-two-pages" to document("stretch", 40)
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

    // y (1/100 mm) of every distinct horizontal row boundary of the single column (x 2000..20500), ascending
    private fun boundaries(name: String, page: Int): List<Long> =
        laidOut.getValue(name).pages[page - 1].elements.filterIsInstance<Line>()
            .filter { it.from.y == it.to.y && it.from.x == 20.mm && it.to.x == 205.mm }
            .map { it.from.y.raw }.distinct().sorted()

    private fun ys(count: Int) = (0 until count).map { 500L + 800L * it }

    @Test
    fun `layout of remainder modes matches golden dumps`() {
        for ((name, doc) in fixtures()) {
            val actual = dump(laidOut.getValue(name))
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }

    @Test
    fun `one page gap keeps 30 rows of exactly 8 mm, last bottom border at 269 mm, 6 mm gap above the stamp`() {
        // 3 data rows + 30 filler rows = 33 rows: boundaries 5, 13, ..., 269 = 5 + 8k, k = 0..33
        assertEquals(ys(34), boundaries("remainder-gap-one-page", 1))
        assertEquals(26900L, boundaries("remainder-gap-one-page", 1).last())
        assertEquals(600L, 27500L - 26900L)
    }

    @Test
    fun `one page stretch makes the last filler row 14 mm tall and ends at the stamp, 275 mm`() {
        // 5, 13, ..., 261 (k = 0..32), then 275
        assertEquals(ys(33) + 27500L, boundaries("remainder-stretch-one-page", 1))
    }

    @Test
    fun `two pages - page 1 is full without filler, page 2 ends at 269 mm in gap or 275 mm in stretch`() {
        // page 1: 33 records, boundaries 5..269; the 6 mm left are less than a row, so there is no filler in either mode
        assertEquals(ys(34), boundaries("remainder-gap-two-pages", 1))
        assertEquals(ys(34), boundaries("remainder-stretch-two-pages", 1))
        // page 2: 7 records (5..61) + 26 filler rows -> 5 + 8k, k = 0..33
        assertEquals(ys(34), boundaries("remainder-gap-two-pages", 2))
        assertEquals(ys(33) + 27500L, boundaries("remainder-stretch-two-pages", 2))
    }

    @Test
    fun `the bottom border is drawn in both modes`() {
        for ((name, y) in listOf("remainder-gap-one-page" to 26900L, "remainder-stretch-one-page" to 27500L)) {
            val bottom = laidOut.getValue(name).pages[0].elements.filterIsInstance<Line>()
                .filter { it.from.y.raw == y && it.to.y.raw == y && it.from.x == 20.mm && it.to.x == 205.mm }
            assertEquals(1, bottom.size, name)
        }
    }
}
