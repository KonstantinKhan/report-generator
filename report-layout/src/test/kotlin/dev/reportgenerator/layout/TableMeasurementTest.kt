package dev.reportgenerator.layout

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextStyle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableMeasurementTest {

    private lateinit var textMeasurer: PdfBoxTextMeasurer
    private val style = TextStyle(fontFamily = "PT Sans", fontSizeMm = 3.5)

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val fontBytes = requireNotNull(
            javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")
        ) { "test font resource missing" }.readBytes()
        val ref = registry.register("pt-sans", fontBytes)
        textMeasurer = PdfBoxTextMeasurer(registry) { ref }
    }

    private fun widthOf(text: String) = textMeasurer.measure(text, style, 1000.mm).width

    @Test
    fun `cell text width is column minus padding on both sides`() {
        assertEquals(8.mm, cellTextWidth(10.mm))
    }

    @Test
    fun `text that fits the full column but not the padded width wraps, for LEFT and CENTER`() {
        val text = "Вал Вал"
        // Fits the bare column by 1mm — the old behavior — but not once 1mm is reserved per side.
        val column = IrColumn("name", widthOf(text) + 1.mm)

        listOf(TextAlign.LEFT, TextAlign.CENTER).forEach { align ->
            val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell(text, align = align))), listOf(column), textMeasurer)

            assertEquals(listOf("Вал", "Вал"), physical.map { it[0].text }, "align=$align")
        }
    }

    @Test
    fun `text that fits the padded width stays on one physical row`() {
        val text = "Вал Вал"
        val column = IrColumn("name", widthOf(text) + 2.mm + 0.1.mm)

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell(text))), listOf(column), textMeasurer)

        assertEquals(listOf("Вал Вал"), physical.map { it[0].text })
    }

    @Test
    fun `long unbreakable designation is hard-broken inside the padded width`() {
        val designation = "АБВГ.301234.567-ОченьДлиннаяНеделимаяСтрока"
        val column = IrColumn("designation", 20.mm)

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell(designation))), listOf(column), textMeasurer)

        assertTrue(physical.size > 1)
        assertEquals(designation, physical.joinToString("") { it[0].text })
        physical.forEach { assertTrue(widthOf(it[0].text) <= cellTextWidth(column.width), "'${it[0].text}' overflows") }
    }

    @Test
    fun `double spaces in a cell do not create blank physical rows`() {
        val column = IrColumn("name", 60.mm)

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell("Вал    опорный"))), listOf(column), textMeasurer)

        assertEquals(listOf("Вал опорный"), physical.map { it[0].text })
    }

    @Test
    fun `newline in a cell splits it into physical rows, other columns stay blank on the continuation`() {
        val columns = listOf(IrColumn("a", 20.mm), IrColumn("b", 60.mm))

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell("1"), IrCell("Вал\nОсь\n\nВтулка"))), columns, textMeasurer)

        assertEquals(listOf("Вал", "Ось", "", "Втулка"), physical.map { it[1].text })
        assertEquals(listOf("1", "", "", ""), physical.map { it[0].text })
    }

    @Test
    fun `segments wrap inside the padded width on their own`() {
        val column = IrColumn("name", widthOf("Вал Вал") + 2.mm - 0.1.mm)

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell("Вал Вал\nВал"))), listOf(column), textMeasurer)

        assertEquals(listOf("Вал", "Вал", "Вал"), physical.map { it[0].text })
    }

    @Test
    fun `leading newline gives an empty first row, trailing newline adds no row`() {
        val column = IrColumn("name", 60.mm)

        assertEquals(listOf("", "Вал"), splitRowIntoPhysicalRows(IrRow(listOf(IrCell("\nВал"))), listOf(column), textMeasurer).map { it[0].text })
        assertEquals(listOf("Вал"), splitRowIntoPhysicalRows(IrRow(listOf(IrCell("Вал\n"))), listOf(column), textMeasurer).map { it[0].text })
        // only breaks = an empty cell = one blank row, like an empty text
        assertEquals(listOf(""), splitRowIntoPhysicalRows(IrRow(listOf(IrCell("\n\n"))), listOf(column), textMeasurer).map { it[0].text })
    }

    @Test
    fun `a long word in a segment is hard-broken and the break after it stays`() {
        val column = IrColumn("designation", 20.mm)

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell("АБВГ.301234.567-ОченьДлиннаяНеделимаяСтрока\nконец"))), listOf(column), textMeasurer)

        assertTrue(physical.size > 2)
        assertEquals("конец", physical.last()[0].text)
        assertEquals("АБВГ.301234.567-ОченьДлиннаяНеделимаяСтрока", physical.dropLast(1).joinToString("") { it[0].text })
    }

    @Test
    fun `sticky columns follow the rows a newline stretches`() {
        val columns = listOf(IrColumn("first", 20.mm, stickToFirstRow = true), IrColumn("last", 20.mm, stickToLastRow = true), IrColumn("name", 60.mm))

        val physical = splitRowIntoPhysicalRows(IrRow(listOf(IrCell("1"), IrCell("2"), IrCell("а\nб\nв"))), columns, textMeasurer)

        assertEquals(listOf("1", "", ""), physical.map { it[0].text })
        assertEquals(listOf("", "", "2"), physical.map { it[1].text })
    }
}
