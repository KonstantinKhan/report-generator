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
}
