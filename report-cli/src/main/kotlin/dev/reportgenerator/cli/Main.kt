package dev.reportgenerator.cli

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.data.mapToSpecificationData
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
                ItemDto("ГОСТ 7798-70", "Болт М6", "STANDARD", 8)
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
    val regularRef = registry.register("gost-type-a", loadFont("PT_Sans-Regular.ttf"))
    // Stand-in for the real ГОСТ 2.304 Type B face (upright, heavier strokes than Type A) — we
    // don't have that font licensed/available, so PT Sans Bold plays the same visual role: a
    // distinct weight for header labels vs. body text, resolved by TextStyle.fontFamily.
    val boldRef = registry.register("gost-type-b", loadFont("PT_Sans-Bold.ttf"))

    fun fontResolver(style: TextStyle) =
        if (style.fontFamily == "GOST Type B") boldRef else regularRef

    val textMeasurer = PdfBoxTextMeasurer(registry, ::fontResolver)

    val document = specification(data)
    val laidOut = layOut(document, textMeasurer, ::fontResolver)

    val svgFile = File(outputDir, "specification.svg")
    svgFile.writeText(render(laidOut).single())

    val pdfFile = File(outputDir, "specification.pdf")
    pdfFile.writeBytes(renderToPdf(laidOut, registry))

    println("pages: ${laidOut.pages.size}")
    println("wrote ${svgFile.absolutePath}")
    println("wrote ${pdfFile.absolutePath}")
}
