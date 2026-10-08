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
    private val style = TextStyle(fontFamily = "PT Sans", fontSizeMm = 3.5)

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

    @Test
    fun `single word wider than maxWidth is hard-broken by characters and never overflows`() {
        val word = "АААА.123456.789-ОченьДлиннаяНеделимаяСтрокаБезПробелов"
        val narrow = 20.mm
        val result = measurer.measure(word, style, maxWidth = narrow, breakLongWords = true)

        assertTrue(result.lineCount > 1)
        assertEquals(word, result.lines.joinToString(""), "hard break must not lose or add characters")
        result.lines.forEach { line ->
            assertTrue(measurer.measure(line, style, maxWidth = 1000.mm).width <= narrow, "line '$line' overflows")
        }
    }

    @Test
    fun `tail of a hard-broken word stays open for the next word`() {
        val result = measurer.measure("ААААААААААААААААААААААААААААААААААААААА б", style, maxWidth = 20.mm, breakLongWords = true)

        assertTrue(result.lines.size > 1)
        assertTrue(result.lines.last().endsWith("б"), "next word joins or follows the broken tail, never lost")
    }

    @Test
    fun `repeated and edge spaces do not produce empty words`() {
        val result = measurer.measure("  Вал   Вал  ", style, maxWidth = 100.mm)

        assertEquals(listOf("Вал Вал"), result.lines)
    }

    @Test
    fun `long word overflows on one line unless breakLongWords is requested`() {
        val result = measurer.measure("НеделимаяСтрокаБезПробеловИТочекВообще", style, maxWidth = 10.mm)

        assertEquals(1, result.lineCount)
        assertTrue(result.width > 10.mm)
    }

    @Test
    fun `newline is a hard break, each segment wraps on its own`() {
        val result = measurer.measure("Вал\nОсь опорная", style, maxWidth = 100.mm)

        assertEquals(listOf("Вал", "Ось опорная"), result.lines)
        assertEquals(2, result.lineCount)
    }

    @Test
    fun `empty segment is an empty line`() {
        assertEquals(listOf("а", "", "б"), measurer.measure("а\n\nб", style, maxWidth = 100.mm).lines)
    }

    @Test
    fun `leading breaks give empty lines, trailing breaks are dropped`() {
        assertEquals(listOf("", "а"), measurer.measure("\nа", style, maxWidth = 100.mm).lines)
        assertEquals(listOf("а"), measurer.measure("а\n", style, maxWidth = 100.mm).lines)
        assertEquals(listOf("а", "", "б"), measurer.measure("а\n\nб\n\n", style, maxWidth = 100.mm).lines)
    }

    @Test
    fun `text of breaks only is empty, CRLF and CR count as a break`() {
        val empty = measurer.measure("\n\n", style, maxWidth = 100.mm)
        assertEquals(0, empty.lineCount)
        assertEquals(Length.ZERO, empty.height)
        assertEquals(listOf("а", "б", "в"), measurer.measure("а\r\nб\rв", style, maxWidth = 100.mm).lines)
    }

    @Test
    fun `a long segment wraps by words and a long word is hard-broken, the break stays`() {
        val narrow = 28.mm
        val result = measurer.measure("Нержавеющая сталь\nААААААААААААААААААААААААААААААААААААААА", style, narrow, breakLongWords = true)

        assertTrue(result.lines.size > 3)
        assertEquals("Нержавеющая", result.lines[0])
        assertEquals("сталь", result.lines[1])
        assertEquals("А".repeat(39), result.lines.drop(2).joinToString(""))
        result.lines.forEach { assertTrue(measurer.measure(it, style, 1000.mm).width <= narrow, "line '$it' overflows") }
    }
}
