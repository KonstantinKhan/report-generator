package dev.reportgenerator.cli

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.data.mapToSpecificationData
import dev.reportgenerator.ir.FontFamilies
import dev.reportgenerator.ir.TextStyle
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
                ItemDto("AAA.02.003", "Втулка распределительная консольная весовая", "PART", 5),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8),
            )
        ),
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие"
    )

    fun loadFont(name: String): ByteArray =
        requireNotNull(Resources.javaClass.getResourceAsStream("/fonts/$name")) {
            "font resource missing — expected report-cli/src/main/resources/fonts/$name"
        }.readBytes()

    val registry = FontRegistry()
    // GOST Type A: real font not available/licensed — PT Sans Regular remains a stand-in.
    val regularRef = registry.register("gost-type-a", loadFont("PT_Sans-Regular.ttf"))
    // GOST Type B: real ASCON font (KOMPAS-3D), licensed for this use.
    val gostBRef = registry.register("gost-type-b", loadFont("GOST-Type-B.ttf"))

    fun fontResolver(style: TextStyle) =
        if (style.fontFamily == FontFamilies.GOST_TYPE_B) gostBRef else regularRef

    val textMeasurer = PdfBoxTextMeasurer(registry, ::fontResolver)

    val document = specification(data)
    val laidOut = layOut(document, textMeasurer, ::fontResolver)

    val svgRendered = render(laidOut)
    svgRendered.forEachIndexed { index, svgContent ->
        val pageNum = index + 1
        val svgFile = File(outputDir, "specification-page-$pageNum.svg")
        svgFile.writeText(svgContent)
        println("wrote ${svgFile.absolutePath}")
    }

    val pdfFile = File(outputDir, "specification.pdf")
    pdfFile.writeBytes(renderToPdf(laidOut, registry))

    println("pages: ${laidOut.pages.size}")
    println("wrote ${pdfFile.absolutePath}")
}
