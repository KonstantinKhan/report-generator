package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.TextStyle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfBoxTextMeasurerTest {

    private lateinit var measurer: PdfBoxTextMeasurer
    private val style = TextStyle(fontFamily = "PT Sans", fontSizePt = 10.0)

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val fontBytes = requireNotNull(
            javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")
        ) { "test font resource missing" }.readBytes()
        val ref = registry.register("pt-sans", fontBytes)
        measurer = PdfBoxTextMeasurer(registry) { ref }
    }

    @Test
    fun `empty string measures to zero`() {
        val result = measurer.measure("", style, maxWidth = 100.mm)
        assertEquals(0, result.lineCount)
        assertEquals(Length.ZERO, result.width)
        assertEquals(Length.ZERO, result.height)
    }

    @Test
    fun `short text within maxWidth stays on one line`() {
        val result = measurer.measure("Корпус", style, maxWidth = 100.mm)
        assertEquals(1, result.lineCount)
        assertTrue(result.width.toMillimeters() > 0.0)
        assertTrue(result.width.toMillimeters() < 100.0)
    }

    @Test
    fun `long text wraps into multiple lines when narrower than content`() {
        val text = "Настоящая спецификация распространяется на изделие целиком"
        val result = measurer.measure(text, style, maxWidth = 30.mm)

        assertTrue(result.lineCount > 1)
    }

    @Test
    fun `wrapped width never exceeds maxWidth for wrappable text`() {
        val text = "Один два три четыре пять шесть семь восемь девять десять"
        val narrow = 25.mm
        val result = measurer.measure(text, style, maxWidth = narrow)

        assertTrue(result.lineCount > 1)
        assertTrue(result.width <= narrow)
    }
}
