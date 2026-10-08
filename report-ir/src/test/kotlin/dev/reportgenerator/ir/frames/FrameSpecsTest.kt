package dev.reportgenerator.ir.frames

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.BorderWeight
import dev.reportgenerator.ir.FontFamilies
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.Styles
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
            .single { it.path == "doc.designation" }

        assertEquals(120.mm, designation.rect.width)
        assertEquals(15.mm, designation.rect.height)
    }

    @Test
    fun `name cell merges the five signature rows`() {
        val name = FrameSpecs.firstPageStamp.cells
            .filterIsInstance<FrameCell.Dynamic>()
            .single { it.path == "doc.name" }

        assertEquals(70.mm, name.rect.width)
        assertEquals(25.mm, name.rect.height)
    }

    @Test
    fun `sheet number and sheets total each appear exactly once`() {
        val dynamicFields = FrameSpecs.firstPageStamp.cells
            .filterIsInstance<FrameCell.Dynamic>()
            .map { it.path }

        assertEquals(1, dynamicFields.count { it == "page.number" })
        assertEquals(1, dynamicFields.count { it == "page.total" })
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

    @Test
    fun `below-frame notes are borderless and centered at the requested distance from the sheet edge`() {
        val cells = FrameSpecs.belowFrameNotes.cells.filterIsInstance<FrameCell.Constant>()
        assertEquals(2, cells.size)
        cells.forEach {
            assertEquals(BorderWeight.NONE, it.borders.top)
            assertEquals(BorderWeight.NONE, it.borders.right)
            assertEquals(BorderWeight.NONE, it.borders.bottom)
            assertEquals(BorderWeight.NONE, it.borders.left)
        }

        // belowFrameOrigin anchors the block's right edge at the sheet's right edge, so a cell's
        // distance from that edge is (spec.width - cell.rect.x - cell.rect.width/2).
        val specWidth = FrameSpecs.belowFrameNotes.size.width
        fun distanceFromSheetEdge(cell: FrameCell.Constant) =
            specWidth - cell.rect.x - cell.rect.width / 2

        val kopirovan = cells.single { it.text == "Копировал" }
        val format = cells.single { it.text == "Формат" }
        assertEquals(90.mm, distanceFromSheetEdge(kopirovan))
        assertEquals(30.mm, distanceFromSheetEdge(format))
    }

    @Test
    fun `designation and name cells are Type A Italic 7mm via fontSize override`() {
        val dynamic = FrameSpecs.firstPageStamp.cells.filterIsInstance<FrameCell.Dynamic>()
        for (path in listOf("doc.designation", "doc.name")) {
            val style = dynamic.single { it.path == path }.style
            assertEquals(Styles.frameText.copy(fontSizeMm = 7.0), style)
            assertEquals(FontFamilies.GOST_TYPE_A_ITALIC, style.fontFamily)
            assertEquals(7.0, style.fontSizeMm)
        }
    }
}
