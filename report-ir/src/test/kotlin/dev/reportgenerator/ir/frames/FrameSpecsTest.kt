package dev.reportgenerator.ir.frames

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameField
import dev.reportgenerator.ir.TextOrientation
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameSpecsTest {

    @Test
    fun `first page stamp is 185 by 40mm`() {
        val spec = FrameSpecs.firstPageStamp

        assertEquals(185.mm, spec.size.width)
        assertEquals(40.mm, spec.size.height)
    }

    @Test
    fun `designation cell spans the full header strip width`() {
        val designation = FrameSpecs.firstPageStamp.cells
            .filterIsInstance<FrameCell.Dynamic>()
            .single { it.field == FrameField.DESIGNATION }

        assertEquals(120.mm, designation.rect.width)
        assertEquals(15.mm, designation.rect.height)
    }

    @Test
    fun `name cell merges the five signature rows`() {
        val name = FrameSpecs.firstPageStamp.cells
            .filterIsInstance<FrameCell.Dynamic>()
            .single { it.field == FrameField.NAME }

        assertEquals(70.mm, name.rect.width)
        assertEquals(25.mm, name.rect.height)
    }

    @Test
    fun `sheet number and sheets total each appear exactly once`() {
        val dynamicFields = FrameSpecs.firstPageStamp.cells
            .filterIsInstance<FrameCell.Dynamic>()
            .map { it.field }

        assertEquals(1, dynamicFields.count { it == FrameField.SHEET_NUMBER })
        assertEquals(1, dynamicFields.count { it == FrameField.SHEETS_TOTAL })
    }

    @Test
    fun `left margin table is a 12 by 135mm vertical strip`() {
        val spec = FrameSpecs.leftMarginTable

        assertEquals(12.mm, spec.size.width)
        assertEquals(135.mm, spec.size.height)
    }

    @Test
    fun `left margin table labels read bottom to top with the 5mm column outermost`() {
        val labeled = FrameSpecs.leftMarginTable.cells
            .filterIsInstance<FrameCell.Constant>()
            .filter { it.text.isNotEmpty() }

        assertEquals(5, labeled.size)
        labeled.forEach {
            assertEquals(TextOrientation.VERTICAL_BOTTOM_TO_TOP, it.orientation)
            assertEquals(5.mm, it.rect.width)
            assertEquals(0.mm, it.rect.x, "labeled column must come first, ahead of the 7mm blank margin")
        }

        // Local space is top-down; "Инв. № подл." is listed last but ends up nearest the page
        // corner once the strip is anchored bottom-up, i.e. at the bottom of local space too.
        val bottomMost = labeled.maxBy { it.rect.y }
        assertEquals("Инв. № подл.", bottomMost.text)
        assertEquals(25.mm, bottomMost.rect.height)
    }
}
