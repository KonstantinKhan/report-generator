package dev.reportgenerator.cli

import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.SpecificationData
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import dev.reportgenerator.reports.specification.specification
import java.io.File

fun main(args: Array<String>) {
    val outputDir = File(args.getOrElse(0) { "output" })
    outputDir.mkdirs()

    val data = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        items = listOf(
            SpecificationItem("AAA.01.000", "Корпус", ItemKind.ASSEMBLY, 1.0),
            SpecificationItem("AAA.02.001", "Вал", ItemKind.PART, 2.0),
            SpecificationItem("AAA.02.002", "Втулка", ItemKind.PART, 4.0),
            SpecificationItem("AAA.02.003", "Втулка распределительная консольная весовая", ItemKind.PART, 5.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0),
            SpecificationItem(null, "Сталь 45", ItemKind.MATERIAL, 0.35, "кг"),
        )
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
