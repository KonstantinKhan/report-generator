package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
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

// Designation and name are read from attributes; null means the key was not requested (NotLoaded).
private fun node(
    id: Int,
    kind: PdmNodeKind = PdmNodeKind.Part,
    designation: AttributeValue? = AttributeValue.Text("АБ.$id"),
    name: AttributeValue? = AttributeValue.Text("Имя $id"),
    usages: List<Usage> = emptyList(),
) = PdmNode(
    id = PdmNodeId(id),
    kind = kind,
    attributes = attributesOf(designation, name),
    usages = usages,
)

private fun attributesOf(designation: AttributeValue?, name: AttributeValue?): Map<AttributeKey, AttributeValue> =
    buildMap {
        designation?.let { put(AttributeKey.Designation, it) }
        name?.let { put(AttributeKey.Name, it) }
    }

private fun rootNode(
    designation: AttributeValue? = AttributeValue.Text("ИЗД.001"),
    name: AttributeValue? = AttributeValue.Text("Изделие"),
    usages: List<Usage> = emptyList(),
) = node(
    id = 1,
    kind = PdmNodeKind.Assembly,
    designation = designation,
    name = name,
    usages = usages,
)

private fun root(vararg usages: Usage) = rootNode(usages = usages.toList())

private fun composition(
    child: PdmNode,
    quantity: Quantity = Quantity.Exact(1.0),
    unit: Field<String> = Field.Missing,
) = CompositionUsage(child = child, position = Field.Missing, quantity = quantity, unit = unit)

private fun document(child: PdmNode) = DocumentUsage(child)

private fun unmapped(child: PdmNode) = UnmappedUsage(
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
        val data = rootNode(name = AttributeValue.Empty).toSpecificationData()

        data.documentName shouldBe "ИЗД.001"
    }

    should("fail when the root designation is missing or not loaded") {
        val missing = rootNode(designation = AttributeValue.Empty)
        val notLoaded = rootNode(designation = null)

        shouldThrow<SpecificationDataException> { missing.toSpecificationData() }.message shouldContain "1"
        shouldThrow<SpecificationDataException> { notLoaded.toSpecificationData() }.message shouldContain "1"
    }

    should("fail when the root name is not loaded") {
        val node = rootNode(name = null)

        shouldThrow<SpecificationDataException> { node.toSpecificationData() }.message shouldContain "1"
    }

    should("map each product kind to its item kind") {
        val expected = listOf(
            PdmNodeKind.Assembly to ItemKind.ASSEMBLY,
            PdmNodeKind.Part to ItemKind.PART,
            PdmNodeKind.Standard to ItemKind.STANDARD,
            PdmNodeKind.Other to ItemKind.OTHER,
            PdmNodeKind.Material to ItemKind.MATERIAL,
            PdmNodeKind.Complex to ItemKind.COMPLEX,
            PdmNodeKind.Kit to ItemKind.SET,
        )

        expected.forEach { (productKind, itemKind) ->
            val data = root(composition(node(2, kind = productKind))).toSpecificationData()

            data.single().kind shouldBe itemKind
        }
    }

    should("map exact quantity amount and usage unit to the item") {
        val child = node(2, kind = PdmNodeKind.Part)
        val data = root(composition(child, Quantity.Exact(2.5), Field.Value("кг"))).toSpecificationData()

        data.single() shouldBe SpecificationItem("АБ.2", "Имя 2", ItemKind.PART, 2.5, "кг")
    }

    should("show a material designation in the name column and leave the designation empty") {
        val child = node(
            2,
            kind = PdmNodeKind.Material,
            designation = AttributeValue.Text("Сталь 09Г2С"),
            name = AttributeValue.Text("Лист"),
        )
        val data = root(composition(child, Quantity.Exact(2.5), Field.Value("кг"))).toSpecificationData()

        data.single() shouldBe SpecificationItem(null, "Сталь 09Г2С", ItemKind.MATERIAL, 2.5, "кг")
    }

    should("fail on a material with a missing designation") {
        val child = node(2, kind = PdmNodeKind.Material, designation = AttributeValue.Empty)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a material with a not loaded designation") {
        val child = node(2, kind = PdmNodeKind.Material, designation = null)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    listOf(
        PdmNodeKind.Standard to ItemKind.STANDARD,
        PdmNodeKind.Other to ItemKind.OTHER,
    ).forEach { (productKind, itemKind) ->
        should("keep the designation column and take the name from the name for a $productKind when both are set") {
            val child = node(
                2,
                kind = productKind,
                designation = AttributeValue.Text("ГОСТ 7798-70"),
                name = AttributeValue.Text("Болт"),
            )
            val data = root(composition(child, Quantity.Exact(2.0), Field.Value("шт"))).toSpecificationData()

            data.single() shouldBe SpecificationItem("ГОСТ 7798-70", "Болт", itemKind, 2.0, "шт")
        }

        should("use the designation as name for a $productKind when the name is missing") {
            val child = node(
                2,
                kind = productKind,
                designation = AttributeValue.Text("ГОСТ 7798-70"),
                name = AttributeValue.Empty,
            )
            val data = root(composition(child)).toSpecificationData()

            data.single() shouldBe SpecificationItem(null, "ГОСТ 7798-70", itemKind, 1.0, null)
        }

        should("use the name for a $productKind when the designation is missing") {
            val child = node(
                2,
                kind = productKind,
                designation = AttributeValue.Empty,
                name = AttributeValue.Text("Болт"),
            )
            val data = root(composition(child)).toSpecificationData()

            data.single() shouldBe SpecificationItem(null, "Болт", itemKind, 1.0, null)
        }

        should("fail on a $productKind when the name and designation are both missing") {
            val child = node(2, kind = productKind, designation = AttributeValue.Empty, name = AttributeValue.Empty)

            shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
        }

        should("fail on a $productKind with a not loaded designation") {
            val child = node(2, kind = productKind, designation = null, name = AttributeValue.Text("Болт"))

            shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
        }

        should("fail on a $productKind with a not loaded name") {
            val child = node(2, kind = productKind, name = null)

            shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
        }
    }

    should("map missing usage unit to null") {
        val data = root(composition(node(2), Quantity.Exact(1.0), Field.Missing)).toSpecificationData()

        data.single().unit shouldBe null
    }

    should("fail on a quantity range because it is not supported") {
        val usage = composition(node(2), Quantity.Range(1.0, 3.0))

        shouldThrow<SpecificationDataException> { root(usage).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a missing or not loaded quantity") {
        val missing = composition(node(2), Quantity.Missing)
        val notLoaded = composition(node(3), Quantity.NotLoaded)

        shouldThrow<SpecificationDataException> { root(missing).toSpecificationData() }.message shouldContain "2"
        shouldThrow<SpecificationDataException> { root(notLoaded).toSpecificationData() }.message shouldContain "3"
    }

    should("fail on a not loaded usage unit") {
        val usage = composition(node(2), unit = Field.NotLoaded)

        shouldThrow<SpecificationDataException> { root(usage).toSpecificationData() }.message shouldContain "2"
    }

    should("map a document usage to zero quantity and no unit") {
        val data = root(document(node(2, kind = PdmNodeKind.AssemblyDrawing))).toSpecificationData()

        data.single() shouldBe SpecificationItem("АБ.2", "Имя 2", ItemKind.DOCUMENTATION, 0.0, null)
    }

    should("drop assembly drawings from the composition") {
        val drawing = node(2, kind = PdmNodeKind.AssemblyDrawing)
        val data = root(composition(drawing), composition(node(3))).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("drop documents that are not assembly drawings") {
        val data = root(document(node(2, kind = PdmNodeKind.Part))).toSpecificationData()

        data.items shouldBe emptyList()
    }

    should("put composition items first and documents after them in usage order") {
        val data = root(
            document(node(10, kind = PdmNodeKind.AssemblyDrawing)),
            composition(node(2)),
            document(node(11, kind = PdmNodeKind.AssemblyDrawing)),
            composition(node(3)),
        ).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.2", "АБ.3", "АБ.10", "АБ.11")
    }

    should("skip usages whose kind is unmapped") {
        val data = root(
            composition(node(2, kind = PdmNodeKind.Unmapped("Неведомый тип"))),
            composition(node(3)),
        ).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("skip unmapped usages") {
        val data = root(unmapped(node(2)), composition(node(3))).toSpecificationData()

        data.items.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("map a missing designation to null") {
        val child = node(2, designation = AttributeValue.Empty)
        val data = root(composition(child)).toSpecificationData()

        data.single().designation shouldBe null
    }

    should("fail on a not loaded designation") {
        val child = node(2, designation = null)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("use the designation as item name when the name is missing") {
        val child = node(2, designation = AttributeValue.Text("ГОСТ 7798-70"), name = AttributeValue.Empty)
        val data = root(composition(child)).toSpecificationData()

        data.single().name shouldBe "ГОСТ 7798-70"
    }

    should("fail when the name and designation of an item are both missing") {
        val child = node(2, designation = AttributeValue.Empty, name = AttributeValue.Empty)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("fail on a not loaded item name") {
        val child = node(2, name = null)

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }.message shouldContain "2"
    }

    should("read a text attribute as a value") {
        val child = node(2, designation = AttributeValue.Text("АБ.7"), name = AttributeValue.Text("Вал"))
        val data = root(composition(child)).toSpecificationData()

        data.single() shouldBe SpecificationItem("АБ.7", "Вал", ItemKind.PART, 1.0, null)
    }

    should("read an empty attribute as missing") {
        val child = node(2, designation = AttributeValue.Empty, name = AttributeValue.Text("Вал"))
        val data = root(composition(child)).toSpecificationData()

        data.single() shouldBe SpecificationItem(null, "Вал", ItemKind.PART, 1.0, null)
    }

    should("fail as not loaded when the attribute key was not requested") {
        val child = node(2, designation = null, name = AttributeValue.Text("Вал"))

        shouldThrow<SpecificationDataException> { root(composition(child)).toSpecificationData() }
            .message shouldContain "designation is not loaded"
    }

    should("not treat a data error as an IllegalStateException so the generic handler does not catch it") {
        val exception: Throwable = SpecificationDataException("data")

        (exception is IllegalStateException) shouldBe false
    }
})
