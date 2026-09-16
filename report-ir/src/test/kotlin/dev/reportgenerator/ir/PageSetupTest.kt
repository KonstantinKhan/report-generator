package dev.reportgenerator.ir

import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.ir.frames.FrameSpecs
import kotlin.test.Test
import kotlin.test.assertEquals

class PageSetupTest {

    @Test
    fun `document defaults to A4 with ESKD-style margins`() {
        val doc = document {
            table { columns { } }
        }

        assertEquals(PageFormat.A4, doc.pageSetup.format)
        assertEquals(20.0, doc.pageSetup.margins.left.toMillimeters())
        assertEquals(5.0, doc.pageSetup.margins.top.toMillimeters())
    }

    @Test
    fun `pageSetup block overrides format and frame`() {
        val doc = document {
            pageSetup(
                format = PageFormat.A3,
                frame = FrameSpecs.firstPageStamp,
                frameBindings = FrameBindings(designation = "X.001", name = "Test")
            )
            table { columns { } }
        }

        assertEquals(PageFormat.A3, doc.pageSetup.format)
        assertEquals("X.001", doc.pageSetup.frameBindings?.designation)
    }
}
