package dev.reportgenerator.reports.specification

import dev.reportgenerator.template.DataValue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

private fun standard(productKind: String?, standardDesignation: String? = "ГОСТ 7798-70") =
    StandardItem(name = "Изделие", quantity = 1.0, standardDesignation = standardDesignation, productKind = productKind)

private fun other(productKind: String?, standardDesignation: String? = "ГОСТ 7798-70") =
    OtherItem(name = "Изделие", quantity = 1.0, standardDesignation = standardDesignation, productKind = productKind)

private fun subgroupField(item: StandardItem): String? {
    val data = SpecificationData("AAA.00.000", "Изделие", standards = listOf(item))
    return (data.itemRecords().single().fields["subgroup"] as DataValue.Str?)?.value
}

class SpecificationSubgroupsTest : ShouldSpec({
    context("dictionary") {
        should("hold the starting set") {
            val expected = mapOf(
                "Болт" to "Болты", "Винт" to "Винты", "Гайка" to "Гайки", "Шайба" to "Шайбы", "Шплинт" to "Шплинты",
                "Шпилька" to "Шпильки", "Штифт" to "Штифты", "Шпонка" to "Шпонки", "Заклепка" to "Заклепки",
                "Кольцо" to "Кольца", "Подшипник" to "Подшипники", "Манжета" to "Манжеты", "Гвоздь" to "Гвозди",
                "Саморез" to "Саморезы", "Анкер" to "Анкеры",
            )
            expected.forEach { (single, plural) -> SpecificationSubgroups.plural(single) shouldBe plural }
        }
        should("look a key up ignoring case and surrounding spaces") {
            SpecificationSubgroups.plural("  БОЛТ ") shouldBe "Болты"
            SpecificationSubgroups.plural("шайба") shouldBe "Шайбы"
        }
        should("not know other kinds") {
            SpecificationSubgroups.plural("Supporting ring") shouldBe null
            SpecificationSubgroups.plural("") shouldBe null
            SpecificationSubgroups.plural(null) shouldBe null
        }
        should("parse a flat yaml map, skipping comments and blank lines") {
            SpecificationSubgroups.parse("# c\n\nБолт: Болты\n\"Гайка\": \"Гайки\"\n") shouldBe mapOf("болт" to "Болты", "гайка" to "Гайки")
        }
    }

    context("subgroup of an item") {
        should("be the plural of a known kind of a standard item") {
            SpecificationSubgroups.of(standard("Болт")) shouldBe "Болты"
            subgroupField(standard("Винт")) shouldBe "Винты"
        }
        should("be set for other products too") {
            SpecificationSubgroups.of(other("Гайка")) shouldBe "Гайки"
            val data = SpecificationData("AAA.00.000", "Изделие", others = listOf(other("Гайка")))
            (data.itemRecords().single().fields["subgroup"] as DataValue.Str).value shouldBe "Гайки"
        }
        should("recognize every standard category") {
            listOf("ГОСТ 1-70", "ГОСТ Р 5-2010", "ОСТ 1 2", "ТУ 3-4", "DIN 933").forEach {
                SpecificationSubgroups.of(standard("Болт", it)) shouldBe "Болты"
            }
        }
        should("be absent for a kind outside the dictionary") {
            SpecificationSubgroups.of(standard("Supporting ring")) shouldBe null
            subgroupField(standard("Supporting ring")) shouldBe null
        }
        should("be absent for an empty or missing kind") {
            SpecificationSubgroups.of(standard(null)) shouldBe null
            SpecificationSubgroups.of(standard("")) shouldBe null
            SpecificationSubgroups.of(standard("  ")) shouldBe null
        }
        should("be absent without a recognized standard category") {
            SpecificationSubgroups.of(standard("Болт", null)) shouldBe null
            SpecificationSubgroups.of(standard("Болт", "СТО 123")) shouldBe null
            SpecificationSubgroups.of(standard("Болт", "ГОСТО 1")) shouldBe null
            subgroupField(standard("Болт", "")) shouldBe null
        }
        should("be absent for the rows of the other sections") {
            val data = SpecificationData(
                "AAA.00.000", "Изделие",
                parts = listOf(PartItem(name = "Вал", quantity = 1.0)),
                materials = listOf(MaterialItem(name = "Сталь", quantity = 1.0, materialKind = "металлы черные")),
            )
            data.itemRecords().map { it.fields["subgroup"] } shouldBe listOf(null, null)
        }
    }
})
