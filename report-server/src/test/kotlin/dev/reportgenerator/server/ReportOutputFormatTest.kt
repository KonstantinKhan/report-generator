package dev.reportgenerator.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportOutputFormatTest {
    @Test fun `default remains pdf for backward compatibility`() =
        assertEquals(ReportOutputFormat.PDF, reportOutputFormat(null))

    @Test fun `all supported output formats are parsed case-insensitively`() {
        assertEquals(ReportOutputFormat.PDF, reportOutputFormat("PDF"))
        assertEquals(ReportOutputFormat.SVG, reportOutputFormat("svg"))
        assertEquals(ReportOutputFormat.BOTH, reportOutputFormat("Both"))
    }

    @Test fun `unknown output format is rejected`() = assertNull(reportOutputFormat("png"))
}
