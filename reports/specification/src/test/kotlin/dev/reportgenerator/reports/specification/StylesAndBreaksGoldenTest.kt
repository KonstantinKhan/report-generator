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

// Golden dumps for (1) parametrized styles (`styles: {alias: {base, size, ...}}`): a larger font in a narrow column wraps
// into more physical rows than the same text in the base style; (2) forced line breaks (`\n`) in a data cell, combined with
// auto-wrap and the numbering of physical lines. The same text dump as StaticBlocksGoldenTest (raw 1/100 mm); a missing golden
// file is generated on the first run. Every fixture is also asserted below against values worked out by hand.
//
// Geometry of all fixtures: A4, margins left 20 / top 5 / right 5 / bottom 5, header 10 mm (5..15), rows of 8 mm from y = 15 mm
// (row i = 15 + 8 i). Columns n 10 + pos 10 + name 125 + note 40 = 185 mm. A cell wraps against the column minus 1 mm on both
// sides (note: 38 mm). A single line of the font size s mm is 1.2 s high and is centred in its 8 mm row: it starts
// (8 - 1.2 s) / 2 below the row top (s = 3.5: 1.9 mm, s = 5: 1.0 mm).
class StylesAndBreaksGoldenTest {
    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private val schema = dataSchema { item { string("name"); string("note") } }

    private fun item(name: String, note: String?) = dataContext { item { string("name", name); string("note", note) } }.get("item") as DataValue.Record

    private fun spec(styles: String, noteStyle: String, headStyle: String): String = """
        sheet: {format: A4, margins: {left: 20, top: 5, right: 5, bottom: 5}}
        blocks:
          - id: body
            type: flow
            table:
              rowHeight: 8
              styles: $styles
              columns:
                - {id: n, width: 10, align: center}
                - {id: pos, width: 10, align: center}
                - {id: name, width: 125}
                - {id: note, width: 40}
              header:
                height: 10
                cells:
                  n: {text: "Стр.", style: $headStyle}
                  pos: {text: "Поз.", style: $headStyle}
                  name: {text: "Наименование", style: $headStyle}
                  note: {text: "Примечание", style: $headStyle}
              computed:
                pos: {sequence: {}}
              row:
                cells:
                  n: {bind: "${'$'}{line.number}"}
                  pos: {bind: "${'$'}{item.pos}"}
                  name: {bind: "${'$'}{item.name}"}
                  note: {bind: "${'$'}{item.note}", optional: true, style: $noteStyle}
    """.trimIndent()

    private fun document(yaml: String, rows: List<DataValue.Record>): IrDocument {
        val flow = TemplateLoader.load(yaml, Styles.named.keys).blocks.single() as FlowBlock
        val table = FlowTables.build(flow.table!!, schema, rows, "blocks[0].table")
        return IrDocument(PageSetup(PageFormat.A4, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table))
    }

    // Fixture 1: the note column in the 5 mm style `big`, header in the 4.5 mm style `head`. Notes (Type A, widths measured):
    // "Шлифованная сталь" 30.68 mm at 3.5 / 43.84 mm at 5, "Нержавеющая сталь" 31.17 / 44.53, the words of them 30.94 and 31.63 mm
    // at 5. Limit 38 mm -> at 5 mm both notes wrap after the first word, the one-word note stays on one line.
    private val notes = listOf(item("Вал", "Шлифованная сталь"), item("Ось", "сталь"), item("Втулка", "Нержавеющая сталь"))

    // Fixture 2: the text is the same, the aliases are plain strings (3.5 mm everywhere) -> no record wraps.
    private val bigStyles = "{big: {base: tableText, size: 5}, head: {base: tableHeader, size: 4.5}}"
    private val plainStyles = "{big: tableText, head: tableHeader}"

    // Fixture 3: `\n` in the note (column 40 mm, limit 38 mm; "Нержавеющая сталь Оцинкованная" = 31.17 + space + 22.32 > 38).
    //   Вал     null                                   -> 1 line               (line 1)
    //   Ось     "Шлифованная сталь\nОцинкованная"       -> 2 lines              (lines 2, 3)
    //   Втулка  "Нержавеющая сталь Оцинкованная\n\nГОСТ" -> 2 + empty + 1 = 4   (lines 4..7)
    //   Болт    "\nсталь\n"                            -> empty + 1 = 2        (lines 8, 9; the trailing \n is dropped)
    //   Гайка   null                                   -> 1 line               (line 10)
    private val breaks = listOf(
        item("Вал", null),
        item("Ось", "Шлифованная сталь\nОцинкованная"),
        item("Втулка", "Нержавеющая сталь Оцинкованная\n\nГОСТ"),
        item("Болт", "\nсталь\n"),
        item("Гайка", null)
    )

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "styles-object-size" to document(spec(bigStyles, "big", "head"), notes),
        "styles-object-baseline" to document(spec(plainStyles, "big", "head"), notes),
        "breaks-newline-numbered" to document(spec(plainStyles, "tableText", "head"), breaks)
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

    // Texts of the table column starting at x mm, below the header (which ends at 15 mm), top to bottom: (y raw, text).
    private fun column(name: String, xMm: Int, widthMm: Int): List<PositionedText> =
        laidOut.getValue(name).pages.single().elements.filterIsInstance<PositionedText>()
            .filter { it.rect.x >= xMm.mm && it.rect.x < (xMm + widthMm).mm && it.rect.y >= 15.mm }
            .sortedBy { it.rect.y.raw }

    private fun note(name: String) = column(name, 165, 40)
    private fun numbers(name: String) = column(name, 20, 10)

    @Test
    fun `layout of styles and breaks matches golden dumps`() {
        for ((name, doc) in laidOut) {
            val actual = dump(doc)
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }

    @Test
    fun `a larger font wraps the narrow column into more rows`() {
        val doc = laidOut.getValue("styles-object-size")
        // 3 records, 2 + 1 + 2 = 5 physical rows (15, 23, 31, 39, 47 mm); the note lines start 1.0 mm below the row top
        assertEquals(listOf("Шлифованная", "сталь", "сталь", "Нержавеющая", "сталь"), note("styles-object-size").map { it.text })
        assertEquals(listOf(1600, 2400, 3200, 4000, 4800), note("styles-object-size").map { it.rect.y.raw.toInt() })
        // 5 mm = 14.1732 pt in the notes; the names stay in 3.5 mm (9.9213 pt), the header is 4.5 mm (12.7559 pt)
        val pt5 = 5.0 / (25.4 / 72.0)
        val pt35 = 3.5 / (25.4 / 72.0)
        val pt45 = 4.5 / (25.4 / 72.0)
        note("styles-object-size").forEach { assertEquals(pt5, it.style.sizePt, 1e-9) }
        // the names: first line of each record = rows 0, 2, 3 -> 15, 31, 39 mm + 1.9 mm
        val names = column("styles-object-size", 40, 125)
        assertEquals(listOf("Вал", "Ось", "Втулка"), names.map { it.text })
        assertEquals(listOf(1690, 3290, 4090), names.map { it.rect.y.raw.toInt() })
        names.forEach { assertEquals(pt35, it.style.sizePt, 1e-9) }
        // the header: 4.5 mm line = 5.4 mm high, centred in 10 mm: 5 + 2.3 = 7.3 mm
        val header = doc.pages.single().elements.filterIsInstance<PositionedText>().filter { it.rect.y < 15.mm }
        assertEquals(listOf("Стр.", "Поз.", "Наименование", "Примечание"), header.sortedBy { it.rect.x.raw }.map { it.text })
        header.forEach {
            assertEquals(pt45, it.style.sizePt, 1e-9)
            assertEquals(730, it.rect.y.raw.toInt())
        }
    }

    @Test
    fun `the same text in the base style does not wrap`() {
        // 3 records = 3 rows (15, 23, 31 mm), the notes start 1.9 mm below the row top
        assertEquals(listOf("Шлифованная сталь", "сталь", "Нержавеющая сталь"), note("styles-object-baseline").map { it.text })
        assertEquals(listOf(1690, 2490, 3290), note("styles-object-baseline").map { it.rect.y.raw.toInt() })
        assertEquals(listOf("1", "2", "3"), numbers("styles-object-baseline").map { it.text })
    }

    @Test
    fun `newline breaks a note into lines, every line is wrapped and numbered`() {
        val name = "breaks-newline-numbered"
        // 10 physical rows, the empty lines (rows 5 and 7) draw no text
        val rows = note(name).map { (it.rect.y.raw.toInt() - 190 - 1500) / 800 to it.text }
        assertEquals(
            listOf(1 to "Шлифованная сталь", 2 to "Оцинкованная", 3 to "Нержавеющая сталь", 4 to "Оцинкованная", 6 to "ГОСТ", 8 to "сталь"),
            rows
        )
        // every physical row has its number 1..10, the empty ones too
        assertEquals((1..10).map(Int::toString), numbers(name).map { it.text })
        assertEquals((0..9).map { 1690 + 800 * it }, numbers(name).map { it.rect.y.raw.toInt() })
        // the record number (pos column, x 30 mm) only on the first line of a record: rows 0, 1, 3, 7, 9
        val pos = column(name, 30, 10)
        assertEquals(listOf("1", "2", "3", "4", "5"), pos.map { it.text })
        assertEquals(listOf(0, 1, 3, 7, 9).map { 1690 + 800 * it }, pos.map { it.rect.y.raw.toInt() })
    }
}
