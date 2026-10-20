package dev.reportgenerator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextTest {

    @Test
    fun `name value returns the text`() {
        assertEquals("Болт", Name.Value("Болт").orElse(""))
    }

    @Test
    fun `name missing returns the default`() {
        assertEquals("—", Name.Missing.orElse("—"))
    }

    @Test
    fun `name not loaded throws`() {
        assertFailsWith<IllegalStateException> { Name.NotLoaded.orElse("") }
    }

    @Test
    fun `designation value returns the text`() {
        assertEquals("АБВГ.123456.001", Designation.Value("АБВГ.123456.001").orElse(""))
    }

    @Test
    fun `designation missing returns the default`() {
        assertEquals("—", Designation.Missing.orElse("—"))
    }

    @Test
    fun `designation not loaded throws`() {
        assertFailsWith<IllegalStateException> { Designation.NotLoaded.orElse("") }
    }
}
