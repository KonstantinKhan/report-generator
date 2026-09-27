package dev.reportgenerator.cli

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.data.mapToSpecificationData
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

    val data = mapToSpecificationData(
        SpecificationDto(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            items = listOf(
                ItemDto("AAA.01.000", "Корпус", "ASSEMBLY", 1),
                ItemDto("AAA.02.001", "Вал", "PART", 2),
                ItemDto("AAA.02.002", "Втулка", "PART", 4),
                ItemDto("AAA.02.003", "Втулка распределительная консольная весовая", "PART", 5),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8),
            )
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
