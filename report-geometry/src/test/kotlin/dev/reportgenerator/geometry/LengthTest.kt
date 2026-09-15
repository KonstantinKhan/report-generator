package dev.reportgenerator.geometry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LengthTest {

    @Test
    fun `mm round-trips through raw hundredths`() {
        val length = 12.34.mm
        assertEquals(12.34, length.toMillimeters(), 0.0001)
    }

    @Test
    fun `addition is exact for typical values`() {
        val a = 10.mm
        val b = 5.mm
        assertEquals(15.mm, a + b)
    }

    @Test
    fun `subtraction can go negative`() {
        val a = 5.mm
        val b = 10.mm
        assertEquals((-5).mm, a - b)
    }

    @Test
    fun `times by int scales exactly`() {
        assertEquals(30.mm, 10.mm * 3)
    }

    @Test
    fun `comparison orders by raw value`() {
        assertTrue(5.mm < 10.mm)
        assertTrue(10.mm > 5.mm)
    }

    @Test
    fun `zero is identity for addition`() {
        val a = 7.mm
        assertEquals(a, a + Length.ZERO)
    }
}
