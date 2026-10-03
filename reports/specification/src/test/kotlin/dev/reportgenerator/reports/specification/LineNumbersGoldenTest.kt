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
import kotlin.test.Test
import kotlin.test.assertEquals

// Golden dumps for the numbering of physical lines (`${line.number}`, `lines:`): wrapped records numbered continuously
// across two pages, the page-restart scope, filler rows numbered or not. The same text dump as StaticBlocksGoldenTest
// (every coordinate as raw 1/100 mm); a missing golden file is generated on the first run. The numbers of every fixture
// are also asserted below against values worked out by hand, so the golden cannot silently freeze a wrong number.
class LineNumbersGoldenTest {
    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private val schema = dataSchema { item { string("name"); string("note") } }

    private fun item(name: String, note: String?) = dataContext { item { string("name", name); string("note", note) } }.get("item") as DataValue.Record

    // Columns 10 + 10 + 135 + 30 = 185 mm = A4 content width (left 20, right 5). `pos` = the number of the record,
    // `n` = the number of the physical line. The note column is 30 mm: every word of the notes below is wider than half of
    // it, so a note wraps into one line per word.
    private fun spec(lines: String): String = """
        sheet: {format: A4, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              fill: blank
              columns:
                - {id: n, width: 10, align: center}
                - {id: pos, width: 10, align: center}
                - {id: name, width: 135}
                - {id: note, width: 30}
              header:
                height: 10
                cells: {n: "№ стр.", pos: "№ п/п", name: "Наименование", note: "Примечание"}
              computed:
                pos: {sequence: {}}
              row:
                cells:
                  n: {bind: "${'$'}{line.number}"}
                  pos: {bind: "${'$'}{item.pos}"}
                  name: {bind: "${'$'}{item.name}"}
                  note: {bind: "${'$'}{item.note}", optional: true}
        """.trimIndent() + "\n" + lines.trimIndent().prependIndent("      ")

    private fun document(yaml: String, rows: List<DataValue.Record>): IrDocument {
        val flow = TemplateLoader.load(yaml, Styles.named.keys).blocks.single() as FlowBlock
        val table = FlowTables.build(flow.table!!, schema, rows, "blocks[0].table")
        return IrDocument(PageSetup(PageFormat.A4, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table))
    }

    // Fixture 1 / 2: 40 records, the note of record 4 has 3 words (3 lines), of record 20 two (2 lines), of record 31
    // three (3 lines): 40 + 2 + 1 + 2 = 45 physical lines. A4 holds 34 rows of 8 mm under the 10 mm header (15..292 mm),
    // so page 1 = lines 1..34 and page 2 = lines 35..45; record 31 starts on the last row of page 1 (line 34) and its other
    // two lines (35, 36) open page 2.
    private val wrapped = (1..40).map { k ->
        item(
            "Деталь $k",
            when (k) {
                4 -> "Нержавеющая Шлифованная Оцинкованная"
                20 -> "Нержавеющая Шлифованная"
                31 -> "Нержавеющая Шлифованная Оцинкованная"
                else -> null
            }
        )
    }

    // Fixture 3 / 4: 3 records, the second wraps into 2 lines: 4 physical lines, then 30 filler rows (34 rows per page).
    private val short = listOf(item("Вал", null), item("Ось", "Нержавеющая Шлифованная"), item("Втулка", null))

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "lines-wrapped-table-scope" to document(spec("lines: {scope: table}"), wrapped),
        "lines-wrapped-page-scope" to document(spec("lines: {scope: page}"), wrapped),
        "lines-fill-false" to document(spec("lines: {fill: false}"), short),
        "lines-fill-true" to document(spec("lines: {fill: true}"), short)
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

    // Texts of one table column below the header (the header ends at y = 15 mm), top to bottom: x of the column in mm.
    private fun column(name: String, page: Int, fromMm: Int): List<String> =
        laidOut.getValue(name).pages[page - 1].elements.filterIsInstance<PositionedText>()
            .filter { it.rect.x >= fromMm.mm && it.rect.x < (fromMm + 10).mm && it.rect.y >= 15.mm }
            .sortedBy { it.rect.y.raw }
            .map { it.text }

    private fun numbers(name: String, page: Int) = column(name, page, 20)

    @Test
    fun `layout of line numbers matches golden dumps`() {
        for ((name, doc) in laidOut) {
            val actual = dump(doc)
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }

    @Test
    fun `wrapped records are numbered line by line through both pages`() {
        val doc = laidOut.getValue("lines-wrapped-table-scope")
        assertEquals(2, doc.pages.size)
        // record 4 -> lines 4, 5, 6; record 20 -> 22, 23; record 31 -> 34 | 35, 36 (the break is inside the record);
        // record 40 -> 45; every line has its number, none is skipped or repeated
        assertEquals((1..34).map(Int::toString), numbers("lines-wrapped-table-scope", 1))
        assertEquals((35..45).map(Int::toString), numbers("lines-wrapped-table-scope", 2))
    }

    @Test
    fun `the record number stays per record and is empty on continuation lines`() {
        // pos column (x = 30 mm): 1 per record, only on the first line of a record; page 1 holds records 1..31, page 2 32..40
        assertEquals((1..31).map(Int::toString), column("lines-wrapped-table-scope", 1, 30))
        assertEquals((32..40).map(Int::toString), column("lines-wrapped-table-scope", 2, 30))
    }

    @Test
    fun `the first line of a record carries the expected number`() {
        // first line of record k = k + extra lines of the records before it (record 4 adds 2, record 20 adds 1, record 31 adds 2)
        val firstLine = mapOf(1 to 1, 3 to 3, 4 to 4, 5 to 7, 19 to 21, 20 to 22, 21 to 24, 30 to 33, 31 to 34, 32 to 37, 40 to 45)
        val doc = laidOut.getValue("lines-wrapped-table-scope")
        val rows = doc.pages.flatMap { page ->
            val texts = page.elements.filterIsInstance<PositionedText>().filter { it.rect.y >= 15.mm }
            fun at(x: Int) = texts.filter { it.rect.x >= x.mm && it.rect.x < (x + 10).mm }.associate { it.rect.y.raw to it.text }
            val n = at(20)
            val pos = at(30)
            n.keys.sorted().mapNotNull { y -> pos[y]?.let { it.toInt() to n.getValue(y).toInt() } }
        }.toMap()
        firstLine.forEach { (record, line) -> assertEquals(line, rows[record], "first line of record $record") }
    }

    @Test
    fun `page scope restarts the number on every page`() {
        assertEquals((1..34).map(Int::toString), numbers("lines-wrapped-page-scope", 1))
        assertEquals((1..11).map(Int::toString), numbers("lines-wrapped-page-scope", 2))
    }

    @Test
    fun `filler rows stay empty by default and are numbered with fill true`() {
        // 4 physical lines (Вал, Ось + its second line, Втулка), then 30 filler rows
        assertEquals(listOf("1", "2", "3", "4"), numbers("lines-fill-false", 1))
        assertEquals((1..34).map(Int::toString), numbers("lines-fill-true", 1))
    }
}
