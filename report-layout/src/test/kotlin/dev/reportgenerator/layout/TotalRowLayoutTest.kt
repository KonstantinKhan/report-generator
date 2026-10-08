package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrGroupTitle
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTotalRow
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.PositionedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Total rows (IrGroup.footer, IrTable.footer): they chain to the line they total (keepWithNext), so a total never
// starts a page alone; blank fill goes after the table footer; the longer chain does not break pages that fit.
class TotalRowLayoutTest {
    // Fixed-pitch stand-in (2 mm per character, 4 mm lines): the real measurer parses the font on every call, which
    // is too heavy for page-filling tables; the rows and the chains are what is tested here, not glyph widths.
    private val textMeasurer = object : TextMeasurer {
        override fun measure(text: String, style: TextStyle, maxWidth: Length, breakLongWords: Boolean): TextMeasurement {
            if (text.isEmpty()) return TextMeasurement(Length.ZERO, Length.ZERO, 0, emptyList())
            val perLine = (maxWidth.raw / 200).toInt().coerceAtLeast(1)
            val lines = ArrayList<String>()
            var current = ""
            for (word in text.split(' ').filter { it.isNotEmpty() }.flatMap { if (breakLongWords) it.chunked(perLine) else listOf(it) }) {
                val joined = if (current.isEmpty()) word else "$current $word"
                if (joined.length <= perLine || current.isEmpty()) current = joined else { lines += current; current = word }
            }
            lines += current
            return TextMeasurement(Length.ofMillimeters(lines.maxOf { it.length } * 2.0), Length.ofMillimeters(4.0 * lines.size), lines.size, lines)
        }
    }
    private val fontResolver: (TextStyle) -> FontRef = { FontRef("stub") }

    private val columns = listOf(IrColumn("a", 20.mm), IrColumn("b", 40.mm))
    private val title = IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0)

    private fun total(label: String, value: String) = IrTotalRow(listOf(IrCell(label, Styles.totalText), IrCell(value, Styles.totalText)))

    private fun group(name: String, rows: Int, footer: List<IrTotalRow> = emptyList()) =
        IrGroup(name, (1..rows).map { IrRow(listOf(IrCell("x$it"), IrCell("Вал $it"))) }, footer = footer)

    private fun table(vararg content: IrGroup, footer: List<IrTotalRow> = emptyList(), fillBlank: Boolean = true, rowHeight: Length? = 8.mm) = IrTable(
        columns = columns, header = null, content = content.toList(), rowHeight = rowHeight, groupTitle = title, fillBlank = fillBlank, footer = footer
    )

    private fun blocks(table: IrTable) = buildBlocks(table, columnOffsets(columns, 20.mm), 60.mm, 20.mm, textMeasurer, "T")

    private fun layOut(table: IrTable): LaidOutDocument =
        layOut(IrDocument(PageSetup(PageFormat.A4, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table)), textMeasurer, fontResolver)

    private fun texts(doc: LaidOutDocument, page: Int) = doc.pages[page - 1].elements.filterIsInstance<PositionedText>().filter { it.text.isNotBlank() }

    @Test
    fun `footer binds to the last data line and its own rows to each other`() {
        val bs = blocks(table(group("G", 3, listOf(total("Итого", "6"), total("Всего", "9")))))
        // title, x1, x2, x3, total, total
        assertEquals(6, bs.size)
        assertEquals(listOf(true, false, false, true, true, false), bs.map { it.constraints.keepWithNext })
    }

    @Test
    fun `a group without data rows binds its footer to the title`() {
        val bs = blocks(table(group("G", 0, listOf(total("Итого", "0")))))
        assertEquals(listOf(true, false), bs.map { it.constraints.keepWithNext })
    }

    @Test
    fun `table footer binds to the last block before it, also across a group footer`() {
        val bs = blocks(table(group("G", 2, listOf(total("Итого", "3"))), footer = listOf(total("Всего", "3"), total("Позиций", "2"))))
        // title, x1, x2, group total, table total, table total
        assertEquals(listOf(true, false, true, true, true, false), bs.map { it.constraints.keepWithNext })
        assertEquals(2, groupIntoUnits(bs).size, "title + x1 | x2 + all the totals")
    }

    @Test
    fun `no footer leaves the chains as before`() {
        val bs = blocks(table(group("G", 3)))
        assertEquals(listOf(true, false, false, false), bs.map { it.constraints.keepWithNext })
    }

    @Test
    fun `a total row is bordered like a data row, label and value sit in their cells`() {
        val bs = blocks(table(group("G", 1), footer = listOf(total("Всего", "42"))))
        val last = bs.last() as BorderedRowBlock
        assertEquals(listOf("Всего", "42"), last.cells.map { it.text })
        assertEquals(8.mm, last.height)
        assertEquals(Styles.totalText, last.cells[0].style)
    }

    @Test
    fun `a long label wraps into more physical rows that stay chained`() {
        val bs = blocks(table(group("G", 1), footer = listOf(IrTotalRow(listOf(IrCell("Итого по всем группам вместе взятым"), IrCell("1"))))))
        // the label is wider than its 20 mm column: several lines, all bound but the last
        val footerRows = bs.drop(2)
        assertTrue(footerRows.size > 1)
        assertEquals(List(footerRows.size) { it < footerRows.size - 1 }, footerRows.map { it.constraints.keepWithNext })
    }

    // A4, margins 5 / 5: the content column is 287 mm = 35 rows of 8 mm (+ 7 mm). Title + 34 rows fill page 1 exactly.
    @Test
    fun `a total that does not fit pulls the last data row to the next page with it`() {
        val without = layOut(table(group("G", 34)))
        assertEquals(1, without.pages.size, "title + 34 lines fill page 1 exactly")

        val with = layOut(table(group("G", 34, listOf(total("Итого", "595")))))
        assertEquals(2, with.pages.size)
        val page1 = texts(with, 1).map { it.text }
        val page2 = texts(with, 2)
        assertTrue("x33" in page1 && "x34" !in page1, "x34 moved to the next page")
        assertEquals(listOf("x34", "Вал 34", "Итого", "595"), page2.map { it.text })
        assertEquals(page2.first().rect.y, page2[1].rect.y, "x34 starts the page")
        assertTrue(page2[2].rect.y > page2[0].rect.y, "the total follows its last data row")
    }

    @Test
    fun `a total that fits stays on the page below its last data row`() {
        val doc = layOut(table(group("G", 30, listOf(total("Итого", "465")))))
        assertEquals(1, doc.pages.size)
        val t = texts(doc, 1)
        val last = t.first { it.text == "x30" }
        val sum = t.first { it.text == "Итого" }
        assertEquals(last.rect.y + 8.mm, sum.rect.y, "the very next row")
    }

    @Test
    fun `table footer follows the last group and the blank fill starts below it`() {
        val doc = layOut(table(group("G", 2, listOf(total("Итого", "3"))), footer = listOf(total("Всего", "3"), total("Позиций", "2"))))
        val t = texts(doc, 1)
        val rows = listOf("Вал 2", "Итого", "Всего", "Позиций").map { text -> t.first { it.text == text }.rect.y }
        assertEquals(listOf(0.mm, 8.mm, 16.mm, 24.mm), rows.map { it - rows[0] })
        // title(0) x1(1) x2(2) group total(3) table totals(4, 5): filler rows continue from row 6 to the page bottom
        val firstFiller = Length.ofMillimeters(5.0) + 8.mm * 6
        val tops = doc.pages[0].elements.filterIsInstance<Line>().filter { it.from.y == it.to.y }.map { it.from.y }.toSet()
        assertTrue(firstFiller in tops && firstFiller + 8.mm in tops, "bordered filler rows start right after the table footer")
    }

    @Test
    fun `without fillBlank the page ends at the table footer`() {
        val doc = layOut(table(group("G", 2), footer = listOf(total("Всего", "2")), fillBlank = false))
        val bottoms = doc.pages[0].elements.filterIsInstance<Line>().filter { it.from.y == it.to.y }.maxOf { it.from.y }
        assertEquals(Length.ofMillimeters(5.0) + 8.mm * 4, bottoms, "title + 2 lines + total = 4 rows")
    }

    @Test
    fun `footers do not break pages that fit, a many page table with totals lays out`() {
        // 5 groups of 25 rows: no total is ever taller than the free space of an empty page
        val groups = (1..5).map { group("G$it", 25, listOf(total("Итого $it", "x"))) }.toTypedArray()
        val doc = layOut(table(*groups, footer = listOf(total("Всего", "y"), total("Позиций", "125"))))
        val all = doc.pages.indices.flatMap { texts(doc, it + 1) }.map { it.text }
        assertTrue("Всего" in all && "Позиций" in all && "Итого 5" in all)
        // every total row has its last data line on the same page
        for (p in 1..doc.pages.size) {
            val page = texts(doc, p)
            for (t in page.filter { it.text.startsWith("Итого") }) {
                assertTrue(page.any { it.text.startsWith("x") && it.rect.y < t.rect.y }, "page $p: '${t.text}' has no data row above it")
            }
        }
    }

    @Test
    fun `a footer chain taller than a page is an overflow`() {
        val footer = (1..40).map { total("Итого $it", "$it") }
        assertFailsWith<LayoutOverflowException> { layOut(table(group("G", 2, footer))) }
    }

    @Test
    fun `total rows need a fixed row height`() {
        assertFailsWith<IllegalArgumentException> { blocks(table(group("G", 1), footer = listOf(total("Всего", "1")), rowHeight = null)) }
    }
}
