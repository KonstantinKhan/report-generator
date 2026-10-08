package dev.reportgenerator.ir.frames

import kotlin.test.Test
import kotlin.test.assertEquals

// FrameSpecs now come from gost-spec.yaml; they must equal the old hardcoded values cell for cell.
class FrameSpecsTemplateParityTest {
    @Test
    fun `first page stamp`() = assertEquals(LegacyFrameSpecs.firstPageStamp, FrameSpecs.firstPageStamp)

    @Test
    fun `continuation page stamp`() = assertEquals(LegacyFrameSpecs.continuationPageStamp, FrameSpecs.continuationPageStamp)

    @Test
    fun `left margin table`() = assertEquals(LegacyFrameSpecs.leftMarginTable, FrameSpecs.leftMarginTable)

    @Test
    fun `spec left table`() = assertEquals(LegacyFrameSpecs.specLeftTable, FrameSpecs.specLeftTable)

    @Test
    fun `main title right table`() = assertEquals(LegacyFrameSpecs.mainTitleRightTable, FrameSpecs.mainTitleRightTable)

    @Test
    fun `below frame notes`() = assertEquals(LegacyFrameSpecs.belowFrameNotes, FrameSpecs.belowFrameNotes)
}
