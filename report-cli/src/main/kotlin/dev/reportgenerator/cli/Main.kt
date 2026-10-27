package dev.reportgenerator.cli

import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import dev.reportgenerator.reports.specification.AssemblyItem
import dev.reportgenerator.reports.specification.MaterialItem
import dev.reportgenerator.reports.specification.PartItem
import dev.reportgenerator.reports.specification.SpecificationData
import dev.reportgenerator.reports.specification.StandardItem
import dev.reportgenerator.reports.specification.specification
import java.io.File

fun main(args: Array<String>) {
    val outputDir = File(args.getOrElse(0) { "output" })
    outputDir.mkdirs()

    val data = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        documentation = emptyList(),
        assemblies = listOf(
            AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0),
        ),
        parts = listOf(
            PartItem(position = 2, designation = "AAA.02.001", name = "Вал", quantity = 2.0),
            PartItem(position = 3, designation = "AAA.02.002", name = "Втулка", quantity = 4.0),
            PartItem(position = 4, designation = "AAA.02.003", name = "Втулка распределительная консольная весовая", quantity = 5.0),
        ),
        standards = List(18) { index ->
            StandardItem(position = 5 + index, name = "Болт М6 ГОСТ 7798-70", quantity = 8.0)
        },
        others = emptyList(),
        materials = listOf(
            MaterialItem(position = 23, name = "Сталь 45", quantity = 0.35, unit = "кг"),
        ),
    )

    val fonts = DefaultFontRegistry.load()
    val textMeasurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    val document = specification(data)
    val laidOut = layOut(document, textMeasurer, fonts::resolve)

    val svgRendered = render(laidOut)
    svgRendered.forEachIndexed { index, svgContent ->
        val pageNum = index + 1
        val svgFile = File(outputDir, "specification-page-$pageNum.svg")
        svgFile.writeText(svgContent)
        println("wrote ${svgFile.absolutePath}")
    }

    val pdfFile = File(outputDir, "specification.pdf")
    pdfFile.writeBytes(renderToPdf(laidOut, fonts.registry))

    println("pages: ${laidOut.pages.size}")
    println("wrote ${pdfFile.absolutePath}")
}
