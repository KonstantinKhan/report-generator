package dev.reportgenerator.rendersvg

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.layout.FontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgSnapshotTest {

    private fun buildFixtureSvg(): String {
        val registry = FontRegistry()
        val fontBytes = requireNotNull(
            javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")
        ) { "test font resource missing" }.readBytes()
        val fontRef = registry.register("pt-sans", fontBytes)
        val textMeasurer = PdfBoxTextMeasurer(registry) { fontRef }

        val columns = listOf(IrColumn("name", 40.mm))
        val header = IrTableHeader(listOf(IrCell("Наименование")))
        val table = IrTable(
            columns = columns,
            header = header,
            content = listOf(
                IrGroup("Детали", listOf(IrRow(listOf(IrCell("Вал"))), IrRow(listOf(IrCell("Втулка")))))
            )
        )
        val document = IrDocument(
            pageSetup = PageSetup(
                format = PageFormat("snapshot", width = 100.mm, height = 60.mm),
                margins = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 10.mm)
            ),
            elements = listOf(table)
        )

        val laidOut = layOut(document, textMeasurer) { fontRef }
        val svgPages = render(laidOut)
        return svgPages.single()
    }

    @Test
    fun `simple table snapshot matches golden file`() {
        val svg = buildFixtureSvg()
        val golden = File("src/test/resources/snapshots/simple-table.svg")

        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(svg)
        }

        assertEquals(golden.readText(), svg)
    }

    @Test
    fun `snapshot is well-formed and contains expected content`() {
        val svg = buildFixtureSvg()

        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.trimEnd().endsWith("</svg>"))
        assertTrue(svg.contains("Вал"))
        assertTrue(svg.contains("Втулка"))
        assertTrue(svg.contains("Наименование"))
    }
}
