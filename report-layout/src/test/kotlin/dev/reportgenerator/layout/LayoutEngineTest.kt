package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FrameBindings
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameField
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.PositionedText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LayoutEngineTest {

    private lateinit var textMeasurer: PdfBoxTextMeasurer
    private val stubFontResolver: (TextStyle) -> FontRef = { FontRef("stub") }

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val fontBytes = requireNotNull(
            javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")
        ) { "test font resource missing" }.readBytes()
        val ref = registry.register("pt-sans", fontBytes)
        textMeasurer = PdfBoxTextMeasurer(registry) { ref }
    }

    private fun column(id: String, width: Length) = IrColumn(id, width)

    private fun cell(text: String) = IrCell(text)

    private fun defaultMargins() = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 20.mm)

    @Test
    fun `simple table with few rows fits on one page`() {
        val columns = listOf(column("name", 60.mm))
        val table = IrTable(
            columns = columns,
            header = null,
            content = listOf(
                IrGroup("Детали", listOf(IrRow(listOf(cell("Вал"))), IrRow(listOf(cell("Втулка")))))
            )
        )
        val document = IrDocument(
            pageSetup = PageSetup(PageFormat.A4, defaultMargins()),
            elements = listOf(table)
        )

        val result = layOut(document, textMeasurer, stubFontResolver)

        assertEquals(1, result.pages.size)
    }

    @Test
    fun `many rows split across multiple pages without losing any`() {
        val columns = listOf(column("name", 60.mm))
        val rows = (1..40).map { IrRow(listOf(cell("Деталь $it"))) }
        val table = IrTable(columns = columns, header = null, content = listOf(IrGroup("Детали", rows)))

        val tinyFormat = PageFormat("tiny", width = 80.mm, height = 40.mm)
        val document = IrDocument(
            pageSetup = PageSetup(tinyFormat, Insets(2.mm, 2.mm, 2.mm, 2.mm)),
            elements = listOf(table)
        )

        val result = layOut(document, textMeasurer, stubFontResolver)

        assertTrue(result.pages.size > 1)

        val renderedRowTexts = result.pages
            .flatMap { it.elements }
            .filterIsInstance<PositionedText>()
            .map { it.text }
            .filter { it.startsWith("Деталь ") }

        assertEquals(40, renderedRowTexts.size)
        assertEquals((1..40).map { "Деталь $it" }.toSet(), renderedRowTexts.toSet())
    }

    @Test
    fun `wrapped cell produces multiple text lines and taller row`() {
        val narrowColumn = column("name", 10.mm)
        val wideColumn = column("wide", 20.mm)
        val longText = "Очень длинное наименование детали для проверки переноса строк"

        val narrowRow = measureRow(
            IrRow(listOf(cell(longText))),
            listOf(narrowColumn),
            columnOffsets(listOf(narrowColumn), 20.mm),
            textMeasurer
        )
        val wideRow = measureRow(
            IrRow(listOf(cell("Вал"))),
            listOf(wideColumn),
            columnOffsets(listOf(wideColumn), 20.mm),
            textMeasurer
        )

        assertTrue(narrowRow.lines.size > 1)
        assertTrue(narrowRow.height > wideRow.height)
    }

    @Test
    fun `table header repeats on every page`() {
        val columns = listOf(column("name", 60.mm))
        val header = IrTableHeader(listOf(cell("Наименование")))
        val rows = (1..40).map { IrRow(listOf(cell("Деталь $it"))) }
        val table = IrTable(columns = columns, header = header, content = listOf(IrGroup("Детали", rows)))

        val tinyFormat = PageFormat("tiny", width = 80.mm, height = 40.mm)
        val document = IrDocument(
            pageSetup = PageSetup(tinyFormat, Insets(2.mm, 2.mm, 2.mm, 2.mm)),
            elements = listOf(table)
        )

        val result = layOut(document, textMeasurer, stubFontResolver)

        assertTrue(result.pages.size > 1)
        result.pages.forEach { page ->
            val headerPresent = page.elements
                .filterIsInstance<PositionedText>()
                .any { it.text == "Наименование" }
            assertTrue(headerPresent, "header missing on page ${page.number}")
        }
    }

    private fun tinyFrameSpec() = FrameSpec(
        size = Size(20.mm, 10.mm),
        cells = listOf(
            FrameCell.Dynamic(Rect(0.mm, 0.mm, 20.mm, 5.mm), FrameField.DESIGNATION),
            FrameCell.Dynamic(Rect(0.mm, 5.mm, 20.mm, 5.mm), FrameField.SHEETS_TOTAL)
        )
    )

    @Test
    fun `frame appears only on the first page`() {
        val columns = listOf(column("name", 60.mm))
        val rows = (1..40).map { IrRow(listOf(cell("Деталь $it"))) }
        val table = IrTable(columns = columns, header = null, content = listOf(IrGroup("Детали", rows)))

        val tinyFormat = PageFormat("tiny", width = 80.mm, height = 40.mm)
        val document = IrDocument(
            pageSetup = PageSetup(
                tinyFormat,
                Insets(2.mm, 2.mm, 2.mm, 2.mm),
                frame = tinyFrameSpec(),
                frameBindings = FrameBindings(designation = "AAA.001")
            ),
            elements = listOf(table)
        )

        val result = layOut(document, textMeasurer, stubFontResolver)

        assertTrue(result.pages.size > 1)

        val firstPageTexts = result.pages.first().elements.filterIsInstance<PositionedText>()
        assertTrue(firstPageTexts.any { it.text.contains("AAA.001") })

        // Regression test for the two-pass chrome rewrite: SHEETS_TOTAL couldn't be known when
        // page 1's chrome used to be drawn eagerly, before later pages existed.
        assertTrue(firstPageTexts.any { it.text == result.pages.size.toString() })

        val laterPagesHaveFrame = result.pages.drop(1).any { page ->
            page.elements.filterIsInstance<PositionedText>().any { it.text.contains("AAA.001") }
        }
        assertTrue(!laterPagesHaveFrame)
    }

    @Test
    fun `dynamic frame field without a supplied binding fails fast`() {
        val columns = listOf(column("name", 60.mm))
        val table = IrTable(columns = columns, header = null, content = listOf(IrRow(listOf(cell("Вал")))))

        val frameSpec = FrameSpec(
            size = Size(20.mm, 5.mm),
            cells = listOf(FrameCell.Dynamic(Rect(0.mm, 0.mm, 20.mm, 5.mm), FrameField.NAME))
        )
        val document = IrDocument(
            pageSetup = PageSetup(PageFormat.A4, defaultMargins(), frame = frameSpec, frameBindings = null),
            elements = listOf(table)
        )

        assertFailsWith<IllegalStateException> {
            layOut(document, textMeasurer, stubFontResolver)
        }
    }

    @Test
    fun `group header stays with its first row instead of orphaning`() {
        val columns = listOf(column("name", 60.mm))
        val offsets = columnOffsets(columns, 20.mm)

        val fillerRowHeight = measureRow(IrRow(listOf(cell("x"))), columns, offsets, textMeasurer).height
        val headerHeight = measureGroupHeader("Детали", 60.mm, 20.mm, textMeasurer).height
        val firstRowHeight = measureRow(IrRow(listOf(cell("Вал"))), columns, offsets, textMeasurer).height

        val margins = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 20.mm)
        val fillerCount = 10
        val contentHeight = (fillerRowHeight * fillerCount) + headerHeight + (firstRowHeight / 2)
        val tinyFormat = PageFormat("tiny", width = 80.mm, height = margins.top + margins.bottom + contentHeight)

        val fillerRows = (1..fillerCount).map { IrRow(listOf(cell("x"))) }
        val targetGroup = IrGroup(
            "Детали",
            listOf(IrRow(listOf(cell("Вал"))), IrRow(listOf(cell("Втулка")))),
            constraints = LayoutConstraints(keepWithNext = true)
        )

        val table = IrTable(columns = columns, header = null, content = fillerRows + listOf(targetGroup))
        val document = IrDocument(pageSetup = PageSetup(tinyFormat, margins), elements = listOf(table))

        val result = layOut(document, textMeasurer, stubFontResolver)

        val firstPageTexts = result.pages.first().elements.filterIsInstance<PositionedText>().map { it.text }
        assertTrue("Детали" !in firstPageTexts, "group header must not be orphaned alone on page 1")
    }

    @Test
    fun `block taller than any page throws structured overflow error`() {
        val columns = listOf(column("name", 60.mm))
        val table = IrTable(
            columns = columns,
            header = null,
            content = listOf(IrGroup("Детали", listOf(IrRow(listOf(cell("Вал"))))))
        )

        val margins = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 5.mm)
        val impossiblyShortFormat = PageFormat("impossible", width = 80.mm, height = margins.top + margins.bottom + 1.mm)
        val document = IrDocument(pageSetup = PageSetup(impossiblyShortFormat, margins), elements = listOf(table))

        val error = assertFailsWith<LayoutOverflowException> {
            layOut(document, textMeasurer, stubFontResolver)
        }

        assertTrue(error.elementPath.isNotBlank())
        assertTrue(error.pageNumber >= 1)
        assertTrue(error.constraint.isNotBlank())
    }

    @Test
    fun `second column x offset accounts for first column width`() {
        val columns = listOf(column("first", 30.mm), column("second", 40.mm))
        val table = IrTable(
            columns = columns,
            header = null,
            content = listOf(IrRow(listOf(cell("A"), cell("B"))))
        )
        val document = IrDocument(pageSetup = PageSetup(PageFormat.A4, defaultMargins()), elements = listOf(table))

        val result = layOut(document, textMeasurer, stubFontResolver)

        val texts = result.pages.first().elements.filterIsInstance<PositionedText>()
        val firstColumnText = texts.first { it.text == "A" }
        val secondColumnText = texts.first { it.text == "B" }

        assertEquals(defaultMargins().left, firstColumnText.rect.x)
        assertEquals(defaultMargins().left + 30.mm, secondColumnText.rect.x)
    }
}
