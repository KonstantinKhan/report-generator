package dev.reportgenerator.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class RectTest {

    @Test
    fun `right and bottom derive from x-y-width-height`() {
        val rect = Rect(x = 10.mm, y = 20.mm, width = 30.mm, height = 40.mm)
        assertEquals(40.mm, rect.right)
        assertEquals(60.mm, rect.bottom)
    }
}

class InsetsTest {

    @Test
    fun `zero insets are all zero length`() {
        assertEquals(Length.ZERO, Insets.ZERO.top)
        assertEquals(Length.ZERO, Insets.ZERO.left)
    }
}
