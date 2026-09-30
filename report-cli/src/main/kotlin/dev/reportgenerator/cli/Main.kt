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
                ItemDto("AAA.01.000", "Корпус", "ASSEMBLY", 1.0),
                ItemDto("AAA.02.001", "Вал", "PART", 2.0),
                ItemDto("AAA.02.002", "Втулка", "PART", 4.0),
                ItemDto("AAA.02.003", "Втулка распределительная консольная весовая", "PART", 5.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0),
                ItemDto(null, "Сталь 45", "MATERIAL", 0.35, "кг"),
            )
        )
    )

    val fonts = DefaultFontRegistry.load()
    val textMeasurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    // С ПЗ (с перечнем замен)
    println("DEBUG: Creating specification WITH change log...")
    val documentWithPZ = specification(data, withChangeLog = true)
    val laidOutWithPZ = layOut(documentWithPZ, textMeasurer, fonts::resolve)

    val svgRenderedWithPZ = render(laidOutWithPZ)
    svgRenderedWithPZ.forEachIndexed { index, svgContent ->
        val pageNum = index + 1
        val svgFile = File(outputDir, "specification-with-pz-page-$pageNum.svg")
        svgFile.writeText(svgContent)
        println("wrote ${svgFile.absolutePath}")
    }

    val pdfFileWithPZ = File(outputDir, "specification-with-pz.pdf")
    pdfFileWithPZ.writeBytes(renderToPdf(laidOutWithPZ, fonts.registry))
    println("pages: ${laidOutWithPZ.pages.size}")
    println("wrote ${pdfFileWithPZ.absolutePath}")

    // Без ПЗ (без перечня замен)
    println("DEBUG: Creating specification WITHOUT change log...")
    val documentWithoutPZ = specification(data, withChangeLog = false)
    val laidOutWithoutPZ = layOut(documentWithoutPZ, textMeasurer, fonts::resolve)

    val svgRenderedWithoutPZ = render(laidOutWithoutPZ)
    svgRenderedWithoutPZ.forEachIndexed { index, svgContent ->
        val pageNum = index + 1
        val svgFile = File(outputDir, "specification-without-pz-page-$pageNum.svg")
        svgFile.writeText(svgContent)
        println("wrote ${svgFile.absolutePath}")
    }

    val pdfFileWithoutPZ = File(outputDir, "specification-without-pz.pdf")
    pdfFileWithoutPZ.writeBytes(renderToPdf(laidOutWithoutPZ, fonts.registry))
    println("pages: ${laidOutWithoutPZ.pages.size}")
    println("wrote ${pdfFileWithoutPZ.absolutePath}")
}
