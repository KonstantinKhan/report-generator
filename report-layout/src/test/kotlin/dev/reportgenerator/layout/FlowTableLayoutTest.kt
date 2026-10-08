package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrGroupTitle
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The flow table knobs that come from the YAML `table:` section: group title (spacers, chain, style, align),
// fillBlank and header.repeat.
class FlowTableLayoutTest {
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

    private fun table(groupTitle: IrGroupTitle, rows: Int = 2, header: IrTableHeader? = null, fillBlank: Boolean = true) = IrTable(
        columns = columns,
        header = header,
        content = listOf(IrGroup("Детали", (1..rows).map { IrRow(listOf(IrCell("x$it"), IrCell("Вал $it"))) })),
        rowHeight = 8.mm,
        groupTitle = groupTitle,
        fillBlank = fillBlank
    )

    private fun blocks(table: IrTable) =
        buildBlocks(table, columnOffsets(columns, 20.mm), 60.mm, 20.mm, textMeasurer, "T")

    private fun layOut(table: IrTable, format: PageFormat = PageFormat.A4, margins: Insets = Insets(5.mm, 5.mm, 5.mm, 20.mm)) =
        layOut(IrDocument(PageSetup(format, margins), listOf(table)), textMeasurer, fontResolver)

    @Test
    fun `spacer counts come from the group title`() {
        val blocks = blocks(table(IrGroupTitle(column = "b", spacerBefore = 3, spacerAfter = 2), rows = 1))

        assertEquals(3 + 1 + 2 + 1, blocks.size)
        assertEquals(
            listOf("Blank[0]", "Blank[1]", "Blank[2]", "Blank[3]", "Blank[4]"),
            blocks.filter { "/Blank[" in it.path }.map { it.path.substringAfterLast('/') }
        )
        val title = blocks[3] as BorderedRowBlock
        assertEquals(listOf("", "Детали"), title.cells.map { it.text })
    }

    @Test
    fun `no spacers leave the title as the only row before data`() {
        val blocks = blocks(table(IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0), rows = 2))

        assertEquals(3, blocks.size)
        assertTrue(blocks[0].constraints.keepWithNext, "title still binds to the first data line")
        assertTrue(!blocks[1].constraints.keepWithNext)
    }

    @Test
    fun `keepWithRows false drops the title chain`() {
        val blocks = blocks(table(IrGroupTitle(column = "b", keepWithRows = false), rows = 2))

        assertTrue(blocks.none { it.constraints.keepWithNext })
        assertEquals(1, groupIntoUnits(blocks).first().size)
    }

    @Test
    fun `title style and align come from the group title`() {
        val style = Styles.tableText
        val blocks = blocks(table(IrGroupTitle(column = "a", style = style, align = TextAlign.LEFT), rows = 1))

        val title = blocks[2] as BorderedRowBlock
        assertEquals("Детали", title.cells[0].text)
        assertEquals(style, title.cells[0].style)
        assertEquals(TextAlign.LEFT, title.cells[0].align)
    }

    @Test
    fun `fillBlank false leaves the page below the last row uncovered`() {
        val small = PageFormat("small", 100.mm, 120.mm)
        val margins = Insets(5.mm, 5.mm, 5.mm, 20.mm)
        val bare = layOut(table(IrGroupTitle(column = "b"), fillBlank = false), small, margins).pages.single().elements.size
        val filled = layOut(table(IrGroupTitle(column = "b")), small, margins).pages.single().elements.size

        assertTrue(bare < filled, "blank filler rows add elements: $bare vs $filled")
    }

    @Test
    fun `header repeat false draws it on the first page only and later pages start at the top margin`() {
        val header = IrTableHeader(listOf(IrCell("Поз"), IrCell("Наименование")), height = 15.mm, repeat = false)
        val small = PageFormat("small", 100.mm, 100.mm)
        val margins = Insets(5.mm, 5.mm, 5.mm, 20.mm)
        val result = layOut(table(IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0), rows = 20, header = header, fillBlank = false), small, margins)

        assertTrue(result.pages.size > 1)
        val headerRects = result.pages.map { page -> page.elements.filterIsInstance<Rectangle>().count { it.rect.height == 15.mm } }
        assertEquals(2, headerRects[0], "header cells of page 1")
        assertTrue(headerRects.drop(1).all { it == 0 }, "no header on later pages: $headerRects")
        val firstRowTop = { page: Int -> result.pages[page].elements.filterIsInstance<PositionedText>().filter { it.text.startsWith("x") }.minOf { it.rect.y } }
        assertTrue(firstRowTop(0) >= 5.mm + 15.mm, "page 1 data starts below the header")
        assertTrue(firstRowTop(1) < 5.mm + 15.mm, "page 2 data starts at the top margin")
    }

    @Test
    fun `header repeat true keeps the header on every page`() {
        val header = IrTableHeader(listOf(IrCell("Поз"), IrCell("Наименование")), height = 15.mm)
        val small = PageFormat("small", 100.mm, 100.mm)
        val margins = Insets(5.mm, 5.mm, 5.mm, 20.mm)
        val result = layOut(table(IrGroupTitle(column = "b", spacerBefore = 0, spacerAfter = 0), rows = 20, header = header, fillBlank = false), small, margins)

        assertTrue(result.pages.size > 1)
        assertTrue(result.pages.all { page -> page.elements.filterIsInstance<Rectangle>().count { it.rect.height == 15.mm } == 2 })
    }
}
