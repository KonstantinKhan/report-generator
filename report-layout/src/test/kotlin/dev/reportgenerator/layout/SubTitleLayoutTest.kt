package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrGroupTitle
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrSubTitle
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.PositionedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Sub-title rows (IrRow.subTitle, IrTable.subTitle): one bordered row in the sub-title column before the data row,
// not numbered, chained to the data row so it never ends a page alone.
class SubTitleLayoutTest {
    // Fixed-pitch stand-in, see TotalRowLayoutTest: page-filling tables are too heavy for the real measurer.
    private val textMeasurer = object : TextMeasurer {
        override fun measure(text: String, style: TextStyle, maxWidth: Length, breakLongWords: Boolean): TextMeasurement {
            if (text.isEmpty()) return TextMeasurement(Length.ZERO, Length.ZERO, 0, emptyList())
            return TextMeasurement(Length.ofMillimeters(text.length * 2.0), Length.ofMillimeters(4.0), 1, listOf(text))
        }
    }
    private val fontResolver: (TextStyle) -> FontRef = { FontRef("stub") }

    private val columns = listOf(IrColumn("a", 20.mm), IrColumn("b", 40.mm))
    private val title = IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0)

    private fun row(i: Int, subTitle: String? = null) = IrRow(listOf(IrCell("x$i"), IrCell("Вал $i")), subTitle = subTitle)

    private fun table(rows: List<IrRow>, subTitle: IrSubTitle? = IrSubTitle("b")) = IrTable(
        columns = columns, header = null, content = listOf(IrGroup("G", rows)), rowHeight = 8.mm,
        groupTitle = title, subTitle = subTitle, fillBlank = false
    )

    private fun blocks(table: IrTable) =
        buildBlocks(table, columnOffsets(columns, 20.mm), 60.mm, 20.mm, textMeasurer, "T").map { it as BorderedRowBlock }

    private fun layOut(table: IrTable): LaidOutDocument =
        layOut(IrDocument(PageSetup(PageFormat.A4, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table)), textMeasurer, fontResolver)

    private fun texts(doc: LaidOutDocument, page: Int) = doc.pages[page - 1].elements.filterIsInstance<PositionedText>().filter { it.text.isNotBlank() }

    @Test
    fun `a sub-title is a row in its column before the data row`() {
        val blocks = blocks(table(listOf(row(1, "Болты"), row(2), row(3, "Винты"))))

        assertEquals(1 + 5, blocks.size, "group title + 3 data rows + 2 sub-titles")
        assertEquals(listOf("", "G"), blocks[0].cells.map { it.text })
        assertEquals(listOf("", "Болты"), blocks[1].cells.map { it.text })
        assertEquals(listOf("x1", "Вал 1"), blocks[2].cells.map { it.text })
        assertEquals(listOf("", "Винты"), blocks[4].cells.map { it.text })
        assertEquals(listOf("x3", "Вал 3"), blocks[5].cells.map { it.text })
    }

    @Test
    fun `a sub-title row is not numbered and chains to its data row`() {
        val blocks = blocks(table(listOf(row(1, "Болты"), row(2))))

        assertTrue(!blocks[1].numbered && blocks[2].numbered && blocks[3].numbered)
        assertTrue(blocks[1].constraints.keepWithNext, "sub-title binds to the data row")
        assertTrue(!blocks[3].constraints.keepWithNext)
    }

    @Test
    fun `keepWithRows false drops the sub-title chain`() {
        val t = table(listOf(row(1, "Болты"))).copy(groupTitle = title.copy(keepWithRows = false))

        assertTrue(blocks(t).none { it.constraints.keepWithNext })
    }

    @Test
    fun `without sub-titles the blocks are as before`() {
        val plain = blocks(table(listOf(row(1), row(2)), subTitle = null))

        assertEquals(3, plain.size)
    }

    @Test
    fun `a sub-title without the table's sub-title look is rejected`() {
        assertFailsWith<IllegalArgumentException> { blocks(table(listOf(row(1, "Болты")), subTitle = null)) }
    }

    @Test
    fun `an unknown sub-title column is rejected`() {
        assertFailsWith<IllegalArgumentException> { blocks(table(listOf(row(1, "Болты")), subTitle = IrSubTitle("zzz"))) }
    }

    // A4, margins 5 / 5: the content column is 287 mm = 35 rows of 8 mm. Title + 33 rows = 34 rows, so the sub-title
    // would be the very last row of page 1 and its data row the first of page 2.
    @Test
    fun `a sub-title is not left alone at the bottom of a page`() {
        val rows = (1..33).map { row(it) } + row(34, "Болты") + row(35)
        val doc = layOut(table(rows))

        assertEquals(2, doc.pages.size)
        val page1 = texts(doc, 1).map { it.text }
        assertTrue("x33" in page1 && "Болты" !in page1, "the sub-title moved off page 1")
        val page2 = texts(doc, 2)
        assertEquals(listOf("Болты", "x34", "Вал 34", "x35", "Вал 35"), page2.map { it.text })
        assertEquals(page2[0].rect.y + 8.mm, page2[1].rect.y, "the sub-title row sits right above its data row")
    }

    @Test
    fun `a sub-title that fits stays on the page`() {
        val rows = (1..32).map { row(it) } + row(33, "Болты")
        val doc = layOut(table(rows))

        assertEquals(1, doc.pages.size)
        assertTrue("Болты" in texts(doc, 1).map { it.text })
    }
}
