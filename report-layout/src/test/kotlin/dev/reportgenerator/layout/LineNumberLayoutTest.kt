package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrGroupTitle
import dev.reportgenerator.ir.IrLineNumbers
import dev.reportgenerator.ir.IrLineScope
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTotalRow
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.PositionedText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// IrTable.lineNumbers: the number of a PHYSICAL row, assigned when the pages are drawn (document order, after the
// units are placed). The page is 100 x 100 mm with 5 mm margins and no table header: 90 mm of content = 11 rows of 8 mm
// (+ 2 mm remainder, which the last filler row takes). Column `n` (x 20..40 mm) is numbered, column `name` carries text.
class LineNumberLayoutTest {
    private lateinit var textMeasurer: PdfBoxTextMeasurer
    private val fontResolver: (TextStyle) -> FontRef = { FontRef("stub") }

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")) { "test font resource missing" }.readBytes()
        val ref = registry.register("pt-sans", bytes)
        textMeasurer = PdfBoxTextMeasurer(registry) { ref }
    }

    private val columns = listOf(IrColumn("n", 20.mm), IrColumn("name", 40.mm))
    private val page = PageFormat("small", 100.mm, 100.mm)
    private val margins = Insets(5.mm, 5.mm, 5.mm, 20.mm)

    private fun row(name: String) = IrRow(listOf(IrCell("", align = TextAlign.CENTER), IrCell(name)))

    // a long name wraps into 3 physical lines in the 40 mm column
    private val long = "Нержавеющая Шлифованная Оцинкованная"

    private fun total(label: String) = IrTotalRow(listOf(IrCell(""), IrCell(label)))

    private fun table(
        content: List<dev.reportgenerator.ir.IrTableElement>,
        numbers: IrLineNumbers? = IrLineNumbers("n"),
        fillBlank: Boolean = false,
        footer: List<IrTotalRow> = emptyList(),
        spacers: Int = 1
    ) = IrTable(
        columns = columns, header = null, content = content, rowHeight = 8.mm,
        groupTitle = IrGroupTitle(column = "name", spacerBefore = spacers, spacerAfter = spacers),
        fillBlank = fillBlank, footer = footer, lineNumbers = numbers
    )

    private fun layOut(table: IrTable): LaidOutDocument =
        layOut(IrDocument(PageSetup(page, margins), listOf(table)), textMeasurer, fontResolver)

    // texts of the `n` column of a page, top to bottom, with the text of the `name` column of the same row ("" = none)
    private fun numbers(doc: LaidOutDocument, pageNumber: Int): List<String> =
        doc.pages[pageNumber - 1].elements.filterIsInstance<PositionedText>()
            .filter { it.rect.x >= 20.mm && it.rect.x < 40.mm }
            .sortedBy { it.rect.y.raw }
            .map { it.text }

    private fun names(doc: LaidOutDocument, pageNumber: Int): List<String> =
        doc.pages[pageNumber - 1].elements.filterIsInstance<PositionedText>()
            .filter { it.rect.x >= 40.mm }
            .sortedWith(compareBy({ it.rect.y.raw }, { it.rect.x.raw }))
            .map { it.text }

    @Test
    fun `every physical line of a wrapped record is numbered`() {
        val doc = layOut(table(listOf(row("a"), row(long), row("b"))))

        assertEquals(listOf("1", "2", "3", "4", "5"), numbers(doc, 1))
        assertEquals(listOf("a", "Нержавеющая", "Шлифованная", "Оцинкованная", "b"), names(doc, 1))
    }

    @Test
    fun `group titles, spacers and group and table totals are not numbered and do not advance the counter`() {
        val groups = listOf(
            IrGroup("Детали", listOf(row("a"), row("b")), footer = listOf(total("Итого А"))),
            IrGroup("Крепёж", listOf(row("c")), footer = listOf(total("Итого Б")))
        )
        val doc = layOut(table(groups, footer = listOf(total("Всего")), spacers = 0))

        // rows: title, a, b, Итого А, title, c, Итого Б, Всего: 8 rows, one page, 3 numbers
        assertEquals(listOf("1", "2", "3"), numbers(doc, 1))
        assertEquals(
            listOf("Детали", "a", "b", "Итого А", "Крепёж", "c", "Итого Б", "Всего"),
            names(doc, 1)
        )
    }

    @Test
    fun `start sets the first number`() {
        val doc = layOut(table(listOf(row("a"), row("b")), numbers = IrLineNumbers("n", start = 5)))

        assertEquals(listOf("5", "6"), numbers(doc, 1))
    }

    @Test
    fun `numbers continue across pages when a title chain moves to the next page`() {
        // page 1: blank, title, blank, 7 lines = 10 rows, 1 free; the next group needs blank, title, blank, first line = 4 rows
        // and moves to page 2 as a unit: numbering goes on without a gap
        val groups = listOf(
            IrGroup("Детали", (1..7).map { row("a$it") }),
            IrGroup("Крепёж", (1..3).map { row("b$it") })
        )
        val doc = layOut(table(groups))

        assertEquals(2, doc.pages.size)
        assertEquals((1..7).map(Int::toString), numbers(doc, 1))
        assertEquals(listOf("8", "9", "10"), numbers(doc, 2))
        assertEquals(listOf("Крепёж", "b1", "b2", "b3"), names(doc, 2))
    }

    @Test
    fun `a total that pulls the last line to the next page keeps the numbers in order and stays unnumbered`() {
        // 10 lines + 2 total rows: the footer chain (last line + totals = 3 rows) does not fit the 1 row left after 9 -> page 2
        val doc = layOut(table((1..10).map { row("a$it") }, footer = listOf(total("Всего"), total("Позиций"))))

        assertEquals(2, doc.pages.size)
        assertEquals((1..9).map(Int::toString), numbers(doc, 1))
        assertEquals(listOf("10"), numbers(doc, 2))
        assertEquals(listOf("a10", "Всего", "Позиций"), names(doc, 2))
    }

    @Test
    fun `a record may break across pages and its lines keep counting`() {
        // 10 single lines, then a 3-line record: its first line is the 11th row of page 1, the rest open page 2
        val doc = layOut(table((1..10).map { row("a$it") } + row(long)))

        assertEquals((1..11).map(Int::toString), numbers(doc, 1))
        assertEquals(listOf("12", "13"), numbers(doc, 2))
        assertEquals(listOf("Шлифованная", "Оцинкованная"), names(doc, 2))
    }

    @Test
    fun `table scope numbers the filler rows only with fill, page scope restarts on every page`() {
        // page 1 = 10 rows of data (blank, title, blank, 7 lines) + 1 filler row; page 2 = blank, title, blank, 3 lines + 5 fillers
        val groups = listOf(
            IrGroup("Детали", (1..7).map { row("a$it") }),
            IrGroup("Крепёж", (1..3).map { row("b$it") })
        )

        val plain = layOut(table(groups, fillBlank = true))
        assertEquals((1..7).map(Int::toString), numbers(plain, 1), "fillers stay empty by default")
        assertEquals(listOf("8", "9", "10"), numbers(plain, 2))

        // with fill the filler of page 1 takes 8, page 2 goes on with 9 .. 11 and its 5 fillers (11 - 6 rows) 12 .. 16
        val filled = layOut(table(groups, numbers = IrLineNumbers("n", fillBlank = true), fillBlank = true))
        assertEquals((1..8).map(Int::toString), numbers(filled, 1))
        assertEquals((9..16).map(Int::toString), numbers(filled, 2))

        val perPage = layOut(table(groups, numbers = IrLineNumbers("n", scope = IrLineScope.PAGE, fillBlank = true), fillBlank = true))
        assertEquals((1..8).map(Int::toString), numbers(perPage, 1))
        assertEquals((1..8).map(Int::toString), numbers(perPage, 2))
    }

    @Test
    fun `the number cell keeps its style and alignment on a filler row`() {
        val numbers = IrLineNumbers("n", fillBlank = true, style = Styles.tableText, align = TextAlign.CENTER)
        val doc = layOut(table(listOf(row("a")), numbers = numbers, fillBlank = true))

        val filler = doc.pages[0].elements.filterIsInstance<PositionedText>().first { it.text == "2" }
        val data = doc.pages[0].elements.filterIsInstance<PositionedText>().first { it.text == "1" }
        // centred in the 20 mm column: the same x for a one-digit number on a data row and on a filler
        assertEquals(data.rect.x, filler.rect.x)
        assertEquals(data.style, filler.style)
    }

    @Test
    fun `line numbers need a fixed row height and an existing column`() {
        val legacy = IrTable(columns, null, listOf(row("a")), rowHeight = null, lineNumbers = IrLineNumbers("n"))
        assertTrue(assertFailsWith<IllegalArgumentException> { layOut(legacy) }.message!!.contains("fixed IrTable.rowHeight"))

        val unknown = table(listOf(row("a")), numbers = IrLineNumbers("zz"))
        assertTrue(assertFailsWith<IllegalArgumentException> { layOut(unknown) }.message!!.contains("'zz'"))
    }

    @Test
    fun `lines of a newline record are numbered and the record may break across pages`() {
        // 9 single lines, then a record of 4 lines (a forced break, an empty segment): lines 10, 11 on page 1 (rows 10, 11), 12, 13 on page 2
        val doc = layOut(table((1..9).map { row("a$it") } + row("Вал\nОсь\n\nВтулка") + row("b")))

        assertEquals((1..11).map(Int::toString), numbers(doc, 1))
        assertEquals(listOf("12", "13", "14"), numbers(doc, 2))
        assertEquals(listOf("Вал", "Ось"), names(doc, 1).takeLast(2))
        // the empty line is a numbered row without text, its text is not drawn
        assertEquals(listOf("Втулка", "b"), names(doc, 2))
    }
}
