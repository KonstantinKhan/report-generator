package dev.reportgenerator.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextTest {

    @Test
    fun `name value returns the text`() {
        assertEquals("Болт", Name.Value("Болт").orNull())
    }

    @Test
    fun `name missing returns null`() {
        assertNull(Name.Missing.orNull())
    }

    @Test
    fun `name not loaded returns null`() {
        assertNull(Name.NotLoaded.orNull())
    }

    @Test
    fun `designation value returns the text`() {
        assertEquals("АБВГ.123456.001", Designation.Value("АБВГ.123456.001").orNull())
    }

    @Test
    fun `designation missing returns null`() {
        assertNull(Designation.Missing.orNull())
    }

    @Test
    fun `designation not loaded returns null`() {
        assertNull(Designation.NotLoaded.orNull())
    }
}
