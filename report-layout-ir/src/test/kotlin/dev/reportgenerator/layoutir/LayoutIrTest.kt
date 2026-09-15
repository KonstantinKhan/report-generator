package dev.reportgenerator.layoutir

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.mm
import kotlin.test.Test
import kotlin.test.assertEquals

class PageFormatTest {

    @Test
    fun `A4 dimensions match ISO 216`() {
        assertEquals(210.0, PageFormat.A4.width.toMillimeters())
        assertEquals(297.0, PageFormat.A4.height.toMillimeters())
    }

    @Test
    fun `A3 dimensions match ISO 216`() {
        assertEquals(297.0, PageFormat.A3.width.toMillimeters())
        assertEquals(420.0, PageFormat.A3.height.toMillimeters())
    }
}

class PageElementTest {

    @Test
    fun `page holds heterogeneous elements`() {
        val text = PositionedText(
            text = "Корпус",
            rect = Rect(Length.ZERO, Length.ZERO, 80.mm, 5.mm),
            style = ResolvedTextStyle(FontRef("gost-type-a"), sizePt = 3.5)
        )
        val line = Line(
            from = Point(Length.ZERO, Length.ZERO),
            to = Point(100.mm, Length.ZERO),
            style = LineStyle(width = 0.5.mm)
        )
        val page = Page(number = 1, format = PageFormat.A4, elements = listOf(text, line))

        assertEquals(2, page.elements.size)
    }
}
