package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrFillRemainder
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrGroupTitle
import dev.reportgenerator.ir.IrLineNumbers
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableElement
import dev.reportgenerator.ir.IrTotalRow
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.PositionedText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// IrTable.fillRemainder: STRETCH grows the last blank filler row by the page height left over after the last whole row,
// GAP keeps every filler row at rowHeight and leaves the leftover empty below the last one. The bottom border of the
// last filler row is drawn in both modes.
//
// Fixture: a 100 x 120 mm page, margins top 5 / right 5 / bottom 5 / left 20, no frame, no header, so the table body runs
// from y = 5 to y = 115 (110 mm). Columns a (x 20..40) and b (x 40..80), rowHeight 8. The horizontal lines of column a
// (from x = 20 to x = 40) are the row boundaries: that is what is asserted, worked out by hand in each test.
class FillRemainderLayoutTest {
    private lateinit var textMeasurer: PdfBoxTextMeasurer
    private val fontResolver: (TextStyle) -> FontRef = { FontRef("stub") }

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")) { "test font resource missing" }.readBytes()
        val ref = registry.register("pt-sans", bytes)
        textMeasurer = PdfBoxTextMeasurer(registry) { ref }
    }

    private val columns = listOf(IrColumn("a", 20.mm), IrColumn("b", 40.mm))
    private val small = PageFormat("small", 100.mm, 120.mm)

    private fun rows(count: Int, from: Int = 1): List<IrTableElement> =
        (from until from + count).map { IrRow(listOf(IrCell("x$it"), IrCell("Вал $it"))) }

    private fun table(
        content: List<IrTableElement>,
        remainder: IrFillRemainder,
        rest: IrFillRemainder = remainder,
        rowHeight: Length = 8.mm,
        footer: List<IrTotalRow> = emptyList(),
        lineNumbers: IrLineNumbers? = null
    ) = IrTable(
        columns = columns, header = null, content = content, rowHeight = rowHeight,
        groupTitle = IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0),
        fillBlank = true, fillRemainder = remainder, fillRemainderRest = rest, footer = footer, lineNumbers = lineNumbers
    )

    private fun layOut(table: IrTable): LaidOutDocument =
        layOut(IrDocument(PageSetup(small, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table)), textMeasurer, fontResolver)

    // y (mm) of every distinct horizontal row boundary of column a on the page, ascending
    private fun boundaries(doc: LaidOutDocument, page: Int): List<Double> =
        doc.pages[page - 1].elements.filterIsInstance<Line>()
            .filter { it.from.y == it.to.y && it.from.x == 20.mm && it.to.x == 40.mm }
            .map { it.from.y.toMillimeters() }.distinct().sorted()

    private fun ys(from: Int, step: Int, count: Int) = (0 until count).map { (from + step * it).toDouble() }

    @Test
    fun `gap keeps every filler row at rowHeight and leaves the leftover below the last one`() {
        // 2 data rows end at 5 + 16 = 21; 115 - 21 = 94 = 11 * 8 + 6 -> 11 filler rows, leftover 6
        val doc = layOut(table(rows(2), IrFillRemainder.GAP))

        // boundaries 5, 13, 21, ..., 109 = 5 + 8k for k = 0..13; the last row is 101..109, 6 mm are left to 115
        assertEquals(ys(5, 8, 14), boundaries(doc, 1))
        assertEquals(109.0, boundaries(doc, 1).last())
        assertEquals(6.0, 115.0 - boundaries(doc, 1).last())
    }

    @Test
    fun `stretch grows the last filler row by the leftover and ends on the page bottom`() {
        val doc = layOut(table(rows(2), IrFillRemainder.STRETCH))

        // 5, 13, ..., 101 (k = 0..12), then the last filler row is 101..115: 8 + 6 = 14 mm tall
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(doc, 1))
    }

    @Test
    fun `both modes draw the bottom border of the last filler row`() {
        for (mode in IrFillRemainder.entries) {
            val doc = layOut(table(rows(2), mode))
            val last = boundaries(doc, 1).last()
            val bottoms = doc.pages[0].elements.filterIsInstance<Line>().filter { it.from.y == it.to.y && it.from.y.toMillimeters() == last }
            // one bottom line per column: a (20..40) and b (40..80)
            assertEquals(listOf(20.0 to 40.0, 40.0 to 80.0), bottoms.map { it.from.x.toMillimeters() to it.to.x.toMillimeters() }.sortedBy { it.first }, "$mode")
        }
    }

    @Test
    fun `no leftover means no gap and both modes draw the same page`() {
        // rowHeight 10: 1 data row ends at 15; 115 - 15 = 100 = 10 * 10 + 0
        val gap = layOut(table(rows(1), IrFillRemainder.GAP, rowHeight = 10.mm))
        val stretch = layOut(table(rows(1), IrFillRemainder.STRETCH, rowHeight = 10.mm))

        assertEquals(ys(5, 10, 12), boundaries(gap, 1))
        assertEquals(115.0, boundaries(gap, 1).last())
        assertEquals(gap.pages[0].elements, stretch.pages[0].elements)
    }

    @Test
    fun `a page without data is covered with whole rows in gap and with a taller last row in stretch`() {
        // 110 = 13 * 8 + 6: 13 filler rows from y = 5
        assertEquals(ys(5, 8, 14), boundaries(layOut(table(emptyList(), IrFillRemainder.GAP)), 1))
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(layOut(table(emptyList(), IrFillRemainder.STRETCH)), 1))
    }

    @Test
    fun `continuation page is filled like the first, a full page leaves no filler`() {
        // 20 rows: page 1 holds 13 (5..109, 6 mm left, less than a row -> no filler row); page 2 holds 7 (5..61), 54 = 6 * 8 + 6
        val gap = layOut(table(rows(20), IrFillRemainder.GAP))
        val stretch = layOut(table(rows(20), IrFillRemainder.STRETCH))

        assertEquals(2, gap.pages.size)
        assertEquals(ys(5, 8, 14), boundaries(gap, 1))
        // stretch: no filler fits, so the last data row 101..109 grows by 6 to 101..115
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(stretch, 1))
        // page 2: data 5..61, 6 filler rows 61..109, 6 mm gap; stretch: last filler row 101..115
        assertEquals(ys(5, 8, 14), boundaries(gap, 2))
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(stretch, 2))
    }

    @Test
    fun `first gap and rest stretch leave the gap on page 1 and close page 2 to the bottom`() {
        // same 20 rows as above: page 1 has 6 mm to spare after 13 rows, page 2 data 5..61 then 6 fillers and a 6 mm leftover
        val doc = layOut(table(rows(20), IrFillRemainder.GAP, rest = IrFillRemainder.STRETCH))

        assertEquals(ys(5, 8, 14), boundaries(doc, 1))
        assertEquals(109.0, boundaries(doc, 1).last())
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(doc, 2))
    }

    @Test
    fun `first stretch and rest gap close page 1 to the bottom and leave the gap on page 2`() {
        val doc = layOut(table(rows(20), IrFillRemainder.STRETCH, rest = IrFillRemainder.GAP))

        assertEquals(ys(5, 8, 13) + 115.0, boundaries(doc, 1))
        assertEquals(ys(5, 8, 14), boundaries(doc, 2))
        assertEquals(109.0, boundaries(doc, 2).last())
    }

    @Test
    fun `rest applies to every page after the first, also with no filler row fitting`() {
        // 40 rows: pages of 13 / 13 / 13 / 1; first gap, rest stretch -> pages 2 and 3 end at 115 (last data row grows), page 1 at 109
        val doc = layOut(table(rows(40), IrFillRemainder.GAP, rest = IrFillRemainder.STRETCH))

        assertEquals(109.0, boundaries(doc, 1).last())
        assertEquals(115.0, boundaries(doc, 2).last())
        assertEquals(115.0, boundaries(doc, 3).last())
        assertEquals(115.0, boundaries(doc, 4).last())
    }

    @Test
    fun `a kept-together title chain pushed to the next page leaves a whole filler row and the gap`() {
        // 12 rows end at 101; the title (keepWithNext) + its first row need 16 mm, 14 are left -> both go to page 2.
        // Page 1: one filler row 101..109 (14 = 8 + 6) -> gap mode ends at 109, stretch mode at 115.
        val content = rows(12) + IrGroup("Детали", rows(2, from = 13).map { it as IrRow })
        val gap = layOut(table(content, IrFillRemainder.GAP))
        val stretch = layOut(table(content, IrFillRemainder.STRETCH))

        assertEquals(ys(5, 8, 14), boundaries(gap, 1))
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(stretch, 1))
        // page 2 starts at the top margin with the title and carries the group's rows
        assertTrue(gap.pages[1].elements.filterIsInstance<PositionedText>().any { it.text == "Детали" && it.rect.y < 13.mm })
    }

    @Test
    fun `footer rows are part of the table and the gap follows them`() {
        // 2 data rows + 1 total row end at 29; 115 - 29 = 86 = 10 * 8 + 6 -> 10 filler rows 29..109, 6 mm left to 115
        val total = IrTotalRow(listOf(IrCell("Итого", Styles.totalText), IrCell("2", Styles.totalText)))
        val gap = layOut(table(rows(2), IrFillRemainder.GAP, footer = listOf(total)))
        val stretch = layOut(table(rows(2), IrFillRemainder.STRETCH, footer = listOf(total)))

        assertEquals(ys(5, 8, 14), boundaries(gap, 1))
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(stretch, 1))
    }

    @Test
    fun `numbered filler rows keep their order and in gap the last one is a normal row`() {
        val numbers = IrLineNumbers(column = "a", fillBlank = true)
        val doc = layOut(table(rows(2), IrFillRemainder.GAP, lineNumbers = numbers))

        // 2 data rows are numbered 1, 2 (the number replaces the cell text), 11 filler rows 3..13
        val numbered = doc.pages[0].elements.filterIsInstance<PositionedText>().filter { it.text.all(Char::isDigit) && it.text.isNotEmpty() }
        assertEquals((1..13).map { it.toString() }, numbered.map { it.text })
        assertEquals(ys(5, 8, 14), boundaries(doc, 1))
        // number 13 sits in the last filler row 101..109, which is exactly rowHeight tall
        assertTrue(numbered.last().rect.y >= 101.mm && numbered.last().rect.y + numbered.last().rect.height <= 109.mm)
    }

    @Test
    fun `stretch without a whole filler row grows the last data row to the page bottom, gap leaves the leftover`() {
        // 13 rows end at 109, 6 mm left (< 8) -> no filler row; the 14th row goes to page 2
        val gap = layOut(table(rows(14), IrFillRemainder.GAP))
        val stretch = layOut(table(rows(14), IrFillRemainder.STRETCH))

        assertEquals(ys(5, 8, 14), boundaries(gap, 1))
        assertEquals(109.0, boundaries(gap, 1).last())
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(stretch, 1))
        // the next page is untouched by the rule
        assertEquals(boundaries(gap, 2).take(2), boundaries(stretch, 2).take(2))
        assertEquals(2, stretch.pages.size)
    }

    @Test
    fun `stretched last row keeps its right and left borders to the new bottom and the number text inside`() {
        val numbers = IrLineNumbers(column = "a", fillBlank = true)
        val doc = layOut(table(rows(14), IrFillRemainder.STRETCH, lineNumbers = numbers))

        val verticals = doc.pages[0].elements.filterIsInstance<Line>().filter { it.from.x == it.to.x && it.from.y == 101.mm }
        assertTrue(verticals.isNotEmpty() && verticals.all { it.to.y == 115.mm })
        val n13 = doc.pages[0].elements.filterIsInstance<PositionedText>().single { it.text == "13" }
        assertTrue(n13.rect.y >= 101.mm && n13.rect.y + n13.rect.height <= 115.mm)
    }

    @Test
    fun `stretch grows the last row when a title chain was pushed to the next page`() {
        // 12 rows end at 101; title + first row (keepWithNext chain) need 16, 14 left -> page 2; page 1 gets a filler row.
        // 13 rows then a title row alone: title 109..117 does not fit -> moves; last row on page 1 is data 101..109, 6 left
        val doc = layOut(table(rows(13) + IrGroup("Детали", rows(1, from = 14).map { it as IrRow }), IrFillRemainder.STRETCH))
        assertEquals(ys(5, 8, 13) + 115.0, boundaries(doc, 1))
    }
}
