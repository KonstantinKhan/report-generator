package dev.reportgenerator.cli

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.data.mapToSpecificationData
import dev.reportgenerator.layout.FontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import dev.reportgenerator.reports.specification.specification
import java.io.File

private object Resources

fun main(args: Array<String>) {
    val outputDir = File(args.getOrElse(0) { "output" })
    outputDir.mkdirs()

    val data = mapToSpecificationData(
        dto = SpecificationDto(
            items = listOf(
                ItemDto("AAA.01.000", "Корпус", "ASSEMBLY", 1),
                ItemDto("AAA.02.001", "Вал", "PART", 2),
                ItemDto("AAA.02.002", "Втулка", "PART", 4),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8)
            )
        ),
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие"
    )

    val fontBytes = requireNotNull(Resources.javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")) {
        "font resource missing — expected report-cli/src/main/resources/fonts/PT_Sans-Regular.ttf"
    }.readBytes()

    val registry = FontRegistry()
    val fontRef = registry.register("pt-sans", fontBytes)
    val textMeasurer = PdfBoxTextMeasurer(registry) { fontRef }

    val document = specification(data)
    val laidOut = layOut(document, textMeasurer) { fontRef }

    val svgFile = File(outputDir, "specification.svg")
    svgFile.writeText(render(laidOut).single())

    val pdfFile = File(outputDir, "specification.pdf")
    pdfFile.writeBytes(renderToPdf(laidOut, registry))

    println("pages: ${laidOut.pages.size}")
    println("wrote ${svgFile.absolutePath}")
    println("wrote ${pdfFile.absolutePath}")
}
