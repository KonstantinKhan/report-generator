package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrGroupTitle
import dev.reportgenerator.ir.IrHeaderCell
import dev.reportgenerator.ir.IrHeaderGrid
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Multi-level header (IrTableHeader.grid): one rectangle per cell over its grid area, the total height drives the
// content top, the header repeats like a single-row one. Columns 20 / 40 / 30 / 30 at x = 20, 40, 80, 110..140.
class MultiHeaderLayoutTest {
    private lateinit var textMeasurer: PdfBoxTextMeasurer
    private val fontResolver: (TextStyle) -> FontRef = { FontRef("stub") }

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")) { "test font resource missing" }.readBytes()
        val ref = registry.register("pt-sans", bytes)
        textMeasurer = PdfBoxTextMeasurer(registry) { ref }
    }

    private val columns = listOf(IrColumn("a", 20.mm), IrColumn("b", 40.mm), IrColumn("c", 30.mm), IrColumn("d", 30.mm))

    // rows 7 + 11 = 18 mm: A spans both rows (col 0), B spans cols 1..2 in row 0, C / D / E below
    private val grid = IrHeaderGrid(
        listOf(7.mm, 11.mm),
        listOf(
            IrHeaderCell(IrCell("A", align = TextAlign.CENTER), 0, 0, rowSpan = 2),
            IrHeaderCell(IrCell("B", align = TextAlign.CENTER), 0, 1, span = 2),
            IrHeaderCell(IrCell("E", align = TextAlign.CENTER), 0, 3, rowSpan = 2),
            IrHeaderCell(IrCell("C", align = TextAlign.CENTER), 1, 1),
            IrHeaderCell(IrCell("D", align = TextAlign.CENTER), 1, 2)
        )
    )

    private fun table(header: IrTableHeader, rows: Int = 2) = IrTable(
        columns = columns,
        header = header,
        content = listOf(IrGroup("Детали", (1..rows).map { IrRow(listOf(IrCell("x$it"), IrCell("v"), IrCell(""), IrCell(""))) })),
        rowHeight = 8.mm,
        groupTitle = IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0),
        fillBlank = false
    )

    private fun layOut(table: IrTable, format: PageFormat = PageFormat.A4) =
        layOut(IrDocument(PageSetup(format, Insets(5.mm, 5.mm, 5.mm, 20.mm)), listOf(table)), textMeasurer, fontResolver)

    private fun IrTableHeader.rects(page: dev.reportgenerator.layoutir.Page) =
        page.elements.filterIsInstance<Rectangle>().filter { it.rect.y >= 5.mm && it.rect.y < 5.mm + 18.mm && it.rect.height <= 18.mm }
            .map { listOf(it.rect.x.raw.toInt(), it.rect.y.raw.toInt(), it.rect.width.raw.toInt(), it.rect.height.raw.toInt()) }

    @Test
    fun `merged cells are rectangles over the spanned columns and rows`() {
        val header = IrTableHeader(emptyList(), height = 18.mm, grid = grid)
        val page = layOut(table(header)).pages.single()
        val rects = header.rects(page)

        assertEquals(
            listOf(
                listOf(2000, 500, 2000, 1800),  // A: x 20, w 20, y 5, h 7 + 11
                listOf(4000, 500, 7000, 700),   // B: x 40, w 40 + 30, h 7
                listOf(11000, 500, 3000, 1800), // E: x 110, w 30
                listOf(4000, 1200, 4000, 1100), // C: y 5 + 7 = 12, h 11
                listOf(8000, 1200, 3000, 1100)  // D: x 80
            ).sortedBy { it[0] * 100000 + it[1] },
            rects.sortedBy { it[0] * 100000 + it[1] }
        )
    }

    @Test
    fun `text is centred in the merged rectangle`() {
        val header = IrTableHeader(emptyList(), height = 18.mm, grid = grid)
        val texts = layOut(table(header)).pages.single().elements.filterIsInstance<PositionedText>().associateBy { it.text }

        fun centre(text: String) = texts.getValue(text).rect.let { (it.x.raw + it.width.raw / 2.0) to (it.y.raw + it.height.raw / 2.0) }
        // A: centre of x 20..40, y 5..23; B: x 40..110, y 5..12; D: x 80..110, y 12..23
        assertEquals(3000.0, centre("A").first, 1.0)
        assertEquals(1400.0, centre("A").second, 1.0)
        assertEquals(7500.0, centre("B").first, 1.0)
        assertEquals(850.0, centre("B").second, 1.0)
        assertEquals(9500.0, centre("D").first, 1.0)
        assertEquals(1750.0, centre("D").second, 1.0)
    }

    @Test
    fun `the total height moves the content down and the header repeats on every page`() {
        val header = IrTableHeader(emptyList(), height = 18.mm, grid = grid)
        val small = PageFormat("small", 150.mm, 100.mm)
        val result = layOut(table(header, rows = 20), small)

        assertTrue(result.pages.size > 1)
        for (page in result.pages) {
            assertEquals(5, header.rects(page).size, "header cells on page ${page.number}")
            // the first body text (group title on page 1, a data row later) is in the first 8 mm row under the header
            val firstRow = page.elements.filterIsInstance<PositionedText>().filter { it.text !in setOf("A", "B", "C", "D", "E") }.minOf { it.rect.y }
            assertTrue(firstRow >= 5.mm + 18.mm && firstRow < 5.mm + 18.mm + 8.mm, "first row below the 18 mm header: $firstRow")
        }
    }

    @Test
    fun `repeat false draws the grid on the first page only`() {
        val header = IrTableHeader(emptyList(), height = 18.mm, repeat = false, grid = grid)
        val small = PageFormat("small", 150.mm, 100.mm)
        val result = layOut(table(header, rows = 20), small)

        assertTrue(result.pages.size > 1)
        assertEquals(5, header.rects(result.pages[0]).size)
        assertTrue(result.pages.drop(1).all { header.rects(it).isEmpty() })
        val firstRow = result.pages[1].elements.filterIsInstance<PositionedText>().minOf { it.rect.y }
        assertTrue(firstRow < 5.mm + 18.mm, "page 2 starts at the top margin: $firstRow")
    }

    @Test
    fun `a single-row header draws the same as its one-row grid`() {
        val single = IrTableHeader(listOf(IrCell("A", align = TextAlign.CENTER), IrCell("B", align = TextAlign.CENTER), IrCell("C", align = TextAlign.CENTER), IrCell("D", align = TextAlign.CENTER)), height = 12.mm)
        val asGrid = IrTableHeader(emptyList(), height = 12.mm, grid = single.asGrid())

        assertEquals(layOut(table(single)), layOut(table(asGrid)))
    }
}
