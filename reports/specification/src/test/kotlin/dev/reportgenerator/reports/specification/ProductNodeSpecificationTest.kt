package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.ProductNode
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.SpecificationData
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.UnmappedUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun node(
    id: String,
    kind: ProductKind = ProductKind.Part,
    designation: Designation = Designation.Value("АБ.$id"),
    name: Name = Name.Value("Имя $id"),
    usages: List<Usage> = emptyList(),
) = ProductNode(ProductId(id), kind, designation, name, usages = usages)

private fun root(vararg usages: Usage) = node(
    id = "1",
    kind = ProductKind.Assembly,
    designation = Designation.Value("ИЗД.001"),
    name = Name.Value("Изделие"),
    usages = usages.toList(),
)

private fun composition(
    child: ProductNode,
    quantity: Quantity = Quantity.Exact(1.0),
    unit: Field<String> = Field.Missing,
) = CompositionUsage(child = child, position = Field.Missing, quantity = quantity, unit = unit)

private fun document(child: ProductNode) = DocumentUsage(child)

private fun unmapped(child: ProductNode) = UnmappedUsage(
    relation = Relation.Unmapped("Неведомая связь"),
    child = child,
)

private fun SpecificationData.single(): SpecificationItem = items.single()

class ProductNodeSpecificationTest : ShouldSpec({
    should("map the header from the root designation and name") {
        val data = root().toSpecificationData()

        data shouldBe SpecificationData("ИЗД.001", "Изделие", emptyList())
    }

    should("use the root designation as document name when the name is missing") {
        val data = root().copy(name = Name.Missing).toSpecificationData()

        data.documentName shouldBe "ИЗД.001"
    }

    should("fail when the root designation is missing or not loaded") {
        val missing = root().copy(designation = Designation.Missing)
        val notLoaded = root().copy(designation = Designation.NotLoaded)

        shouldThrow<IllegalStateException> { missing.toSpecificationData() }.message shouldContain "1"
        shouldThrow<IllegalStateException> { notLoaded.toSpecificationData() }.message shouldContain "1"
    }

    should("fail when the root name is not loaded") {
        val node = root().copy(name = Name.NotLoaded)

        shouldThrow<IllegalStateException> { node.toSpecificationData() }.message shouldContain "1"
    }

    should("map each product kind to its item kind") {
        val expected = listOf(
            ProductKind.Assembly to ItemKind.ASSEMBLY,
            ProductKind.Part to ItemKind.PART,
            ProductKind.Standard to ItemKind.STANDARD,
            ProductKind.Other to ItemKind.OTHER,
            ProductKind.Material to ItemKind.MATERIAL,
            ProductKind.Complex to ItemKind.COMPLEX,
            ProductKind.Kit to ItemKind.SET,
        )

        expected.forEach { (productKind, itemKind) ->
            val data = root(composition(node("2", kind = productKind))).toSpecificationData()

            data.single().kind shouldBe itemKind
        }
    }

    should("map exact quantity amount and usage unit to the item") {
        val child = node("2", kind = ProductKind.Part)
        val data = root(composition(child, Quantity.Exact(2.5), Field.Value("кг"))).toSpecificationData()

        data.single() shouldBe SpecificationItem("АБ.2", "Имя 2", ItemKind.PART, 2.5, "кг")
    }

    should("show a material designation in the name column and leave the designation empty") {
        val child = node("2", kind = ProductKind.Material, designation = Designation.Value("Сталь 09Г2С"), name = Name.Value("Лист"))
        val data = root(composition(child, Quantity.Exact(2.5), Field.Value("кг"))).toSpecificationData()

        data.single() shouldBe SpecificationItem(null, "Сталь 09Г2С", ItemKind.MATERIAL, 2.5, "кг")
    }

    should("fail on a material with a missing designation") {
        val child = node("2", kind = ProductKind.Material, designation = Designation.Missing)

        shouldThrow<IllegalStateException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a material with a not loaded designation") {
        val child = node("2", kind = ProductKind.Material, designation = Designation.NotLoaded)

        shouldThrow<IllegalStateException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("map missing usage unit to null") {
        val data = root(composition(node("2"), Quantity.Exact(1.0), Field.Missing)).toSpecificationData()

        data.single().unit shouldBe null
    }

    should("fail on a quantity range because it is not supported") {
        val usage = composition(node("2"), Quantity.Range(1.0, 3.0))

        shouldThrow<IllegalStateException> { root(usage).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a missing or not loaded quantity") {
        val missing = composition(node("2"), Quantity.Missing)
        val notLoaded = composition(node("3"), Quantity.NotLoaded)

        shouldThrow<IllegalStateException> { root(missing).toSpecificationData() }.message shouldContain "2"
        shouldThrow<IllegalStateException> { root(notLoaded).toSpecificationData() }.message shouldContain "3"
    }

    should("fail on a not loaded usage unit") {
        val usage = composition(node("2"), unit = Field.NotLoaded)

        shouldThrow<IllegalStateException> { root(usage).toSpecificationData() }.message shouldContain "2"
    }

    should("map a document usage to zero quantity and no unit") {
        val data = root(document(node("2", kind = ProductKind.AssemblyDrawing))).toSpecificationData()

        data.single() shouldBe SpecificationItem("АБ.2", "Имя 2", ItemKind.DOCUMENTATION, 0.0, null)
    }

    should("drop assembly drawings from the composition") {
        val drawing = node("2", kind = ProductKind.AssemblyDrawing)
        val data = root(composition(drawing), composition(node("3"))).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("drop documents that are not assembly drawings") {
        val data = root(document(node("2", kind = ProductKind.Part))).toSpecificationData()

        data.items shouldBe emptyList()
    }

    should("put composition items first and documents after them in usage order") {
        val data = root(
            document(node("10", kind = ProductKind.AssemblyDrawing)),
            composition(node("2")),
            document(node("11", kind = ProductKind.AssemblyDrawing)),
            composition(node("3")),
        ).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.2", "АБ.3", "АБ.10", "АБ.11")
    }

    should("skip usages whose kind is unmapped") {
        val data = root(
            composition(node("2", kind = ProductKind.Unmapped("Неведомый тип"))),
            composition(node("3")),
        ).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("skip unmapped usages") {
        val data = root(unmapped(node("2")), composition(node("3"))).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("map a missing designation to null") {
        val child = node("2", designation = Designation.Missing)
        val data = root(composition(child)).toSpecificationData()

        data.single().designation shouldBe null
    }

    should("fail on a not loaded designation") {
        val child = node("2", designation = Designation.NotLoaded)

        shouldThrow<IllegalStateException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("use the designation as item name when the name is missing") {
        val child = node("2", designation = Designation.Value("ГОСТ 7798-70"), name = Name.Missing)
        val data = root(composition(child)).toSpecificationData()

        data.single().name shouldBe "ГОСТ 7798-70"
    }

    should("fail when the name and designation of an item are both missing") {
        val child = node("2", designation = Designation.Missing, name = Name.Missing)

        shouldThrow<IllegalStateException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a not loaded item name") {
        val child = node("2", name = Name.NotLoaded)

        shouldThrow<IllegalStateException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }
})
