package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.VariantUsage
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

// The matrix of the group specification: a row per item of the union of the main part and the executions, a value per
// execution column ("х" for documentation, "-" for no item or a zero quantity, otherwise the quantity).
class GroupSpecificationDataTest : ShouldSpec({

    var nextId = 100
    fun node(kind: PdmNodeKind, designation: String, name: String, id: Int = nextId++) = PdmNode(
        id = PdmNodeId(id),
        kind = kind,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text(name),
        ),
    )

    fun part(designation: String) = node(PdmNodeKind.Part, designation, "Деталь $designation")

    fun link(target: PdmNode, quantity: Quantity = Quantity.Exact(1.0)) = CompositionUsage(
        target = target,
        position = Field.Missing,
        quantity = quantity,
        unit = Field.Missing,
    )

    fun execution(designation: String, vararg usages: Usage) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = PdmNodeKind.Assembly,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Изделие $designation"),
        ),
        usages = usages.toList(),
    )

    fun root(vararg usages: Usage, variants: List<PdmNode> = emptyList()) = PdmNode(
        id = PdmNodeId(1),
        kind = PdmNodeKind.Assembly,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text("ИЗД.00"),
            AttributeKey.Name to AttributeValue.Text("Изделие"),
        ),
        usages = usages.toList() + variants.map { VariantUsage(target = it) },
    )

    fun nameOf(node: PdmNode): String = (node.attributes[AttributeKey.Name] as AttributeValue.Text).value

    fun rowOf(data: GroupSpecificationData, node: PdmNode): GroupRow =
        data.rows.single { it.item.name == nameOf(node) }

    should("show the quantity of an item in every execution column and a dash where the item is not") {
        val a = part("АБ.01")
        val b = part("АБ.02")
        val c = part("АБ.03")
        val v1 = execution("ИЗД-2", link(a, Quantity.Exact(2.0)), link(c, Quantity.Exact(3.0)))
        val v2 = execution("ИЗД-10", link(b, Quantity.Exact(1.0)))
        val data = root(link(a, Quantity.Exact(2.0)), link(b, Quantity.Exact(1.0)), variants = listOf(v1, v2))
            .toGroupSpecificationData()

        data.executions shouldBe listOf("ИЗД.00", "ИЗД-2", "ИЗД-10")
        rowOf(data, a).values shouldBe listOf("2", "2", "-")
        rowOf(data, b).values shouldBe listOf("1", "-", "1")
        rowOf(data, c).values shouldBe listOf("-", "3", "-")
    }

    should("take the lower bound of a quantity range") {
        val a = part("АБ.01")
        val data = root(link(a, Quantity.Range(1.0, 5.0))).toGroupSpecificationData()

        rowOf(data, a).values shouldBe listOf("1")
    }

    should("show the mark х in every column for a documentation row") {
        val drawing = node(PdmNodeKind.AssemblyDrawing, "СБ.00", "Сборочный чертёж")
        val variant = execution("ИЗД-2")
        val data = root(
            DocumentUsage(target = drawing),
            variants = listOf(variant),
        ).toGroupSpecificationData()

        rowOf(data, drawing).values shouldBe listOf("х", "х")
    }

    should("keep a row whose every column is a dash") {
        val a = part("АБ.01")
        val variant = execution("ИЗД-2")
        val data = root(link(a, Quantity.Exact(0.0)), variants = listOf(variant)).toGroupSpecificationData()

        rowOf(data, a).values shouldBe listOf("-", "-")
    }

    should("show a material as its number with a decimal comma") {
        val material = node(PdmNodeKind.Material, "Сталь 45", "Сталь 45")
        val variant = execution("ИЗД-2", link(material, Quantity.Exact(0.35)))
        val data = root(link(material, Quantity.Exact(0.35)), variants = listOf(variant)).toGroupSpecificationData()

        rowOf(data, material).values shouldBe listOf("0,35", "0,35")
    }

    should("take a row with the same item and a different quantity as another row") {
        val a = part("АБ.01")
        val variant = execution("ИЗД-2", link(a, Quantity.Exact(3.0)))
        val data = root(link(a, Quantity.Exact(2.0)), variants = listOf(variant)).toGroupSpecificationData()

        data.rows.map { it.item.name to it.values } shouldBe listOf(nameOf(a) to listOf("2", "-"), nameOf(a) to listOf("-", "3"))
    }

    should("have no rows without items") {
        root().toGroupSpecificationData().rows.shouldBeEmpty()
    }
})
