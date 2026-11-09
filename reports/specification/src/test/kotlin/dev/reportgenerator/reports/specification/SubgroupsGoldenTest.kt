package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.PositionedImage
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Golden guard for the sub-titles of standard items ("Болты", "Винты", ...): the layout dump of a specification whose
// "Стандартные изделия" has several kinds of product in several standard categories, plus an unknown kind and a
// standard of an unrecognized category (no sub-title). A missing golden file is generated on first run.
class SubgroupsGoldenTest {

    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private fun standard(name: String, standardDesignation: String?, productKind: String?, sizeType: String? = null) =
        StandardItem(name = name, quantity = 4.0, standardDesignation = standardDesignation, productKind = productKind, sizeType = sizeType)

    // The data is already built, so the items are given in the order of the sheet (category, kind, standard, size).
    private fun data() = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        assemblies = listOf(AssemblyItem(designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
        standards = listOf(
            standard("Болт М6x20", "ГОСТ 7798-70", "Болт", "М6 20"),
            standard("Болт М8x30", "ГОСТ 7798-70", "Болт", "М8 30"),
            standard("Винт М4x10", "ГОСТ 1491-80", "Винт", "М4 10"),
            standard("Гайка М6", "ГОСТ 5915-70", "Гайка", "М6"),
            standard("Шайба 6", "ГОСТ 11371-78", "Шайба", "6"),
            standard("Болт М10x40", "ГОСТ Р 50-2010", "Болт", "М10 40"),
            standard("Болт М5x16", "DIN 933", "Болт", "М5 16"),
            standard("Кольцо стопорное", "DIN 471", "Supporting ring", "20"),
            standard("Втулка", "СТО 123", "Втулка", "10"),
            standard("Пломба", null, null),
        ),
        others = listOf(OtherItem(name = "Манжета 1", quantity = 4.0, standardDesignation = "ОСТ 38.01", productKind = "Манжета", sizeType = "20")),
        materials = listOf(MaterialItem(name = "Сталь 45", quantity = 0.35, notes = listOf("кг"))),
    )

    private fun dump(doc: LaidOutDocument): String = buildString {
        fun L(l: Length) = l.raw.toString()
        fun R(r: Rect) = "${L(r.x)} ${L(r.y)} ${L(r.width)} ${L(r.height)}"
        for (page in doc.pages) {
            appendLine("PAGE ${page.number} ${page.format.name} ${L(page.format.width)} ${L(page.format.height)}")
            for (e in page.elements) when (e) {
                is PositionedText -> appendLine("T \"${e.text}\" ${R(e.rect)} ${e.style.font.id} ${e.style.sizePt} i=${e.style.italic} ${e.orientation}")
                is Line -> appendLine("L ${L(e.from.x)} ${L(e.from.y)} ${L(e.to.x)} ${L(e.to.y)} w=${L(e.style.width)} ${e.style.color}")
                is Rectangle -> appendLine("R ${R(e.rect)} w=${L(e.style.width)} ${e.style.color}")
                is PositionedImage -> appendLine("I ${R(e.rect)} ${e.image.id}")
            }
        }
    }

    @Test
    fun `layout of standard items with sub-titles matches the golden dump`() {
        val laidOut = layOut(specification(data()), measurer, fonts::resolve)
        val actual = dump(laidOut)
        val golden = File("src/test/resources/golden/spec-subgroups.txt")
        if (!golden.exists()) golden.writeText(actual)
        assertEquals(golden.readText(), actual, "golden mismatch: spec-subgroups")
    }

    @Test
    fun `sub-titles stand once per run of a kind, in the order of the standard categories`() {
        val texts = layOut(specification(data()), measurer, fonts::resolve).pages.flatMap { it.elements }
            .filterIsInstance<PositionedText>().map { it.text }
        val headings = texts.filter { it in setOf("Болты", "Винты", "Гайки", "Шайбы", "Манжеты") }
        // ГОСТ: Болты, Винты, Гайки, Шайбы; ГОСТ Р: Болты; DIN: Болты (same kind right after - not repeated);
        // Втулка (СТО) and Пломба have none; ОСТ in OTHER: Манжеты
        assertEquals(listOf("Болты", "Винты", "Гайки", "Шайбы", "Болты", "Манжеты"), headings)
    }
}
