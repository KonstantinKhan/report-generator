package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.VariantUsage
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe

// Variants (исполнения) of the root: the common items stay in the main part, the others go to the block of every
// execution they are in. Items are identified by the object id and the quantity.
class ProductNodeVariantsTest : ShouldSpec({

    fun part(id: Int) = PdmNode(
        id = PdmNodeId(id),
        kind = PdmNodeKind.Part,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text("АБ.$id"),
            AttributeKey.Name to AttributeValue.Text("Деталь $id"),
        ),
    )

    fun link(child: PdmNode, quantity: Double) = CompositionUsage(
        target = child,
        position = Field.Missing,
        quantity = Quantity.Exact(quantity),
        unit = Field.Missing,
    )

    fun execution(designation: String, vararg usages: Usage) = PdmNode(
        id = PdmNodeId(designation.hashCode()),
        kind = PdmNodeKind.Assembly,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Изделие $designation"),
        ),
        usages = usages.toList(),
    )

    fun withVariants(root: PdmNode, vararg variants: PdmNode): PdmNode =
        root.copy(usages = root.usages + variants.map { VariantUsage(target = it) })

    should("have no variant blocks and keep every item when the root has no variants") {
        val root = execution("ИЗД.001", link(part(2), 1.0), link(part(3), 2.0))

        val data = root.toSpecificationData()

        data.variants.shouldBeEmpty()
        data.parts.map { it.designation } shouldBe listOf("АБ.2", "АБ.3")
    }

    should("keep the items of the root in the main part and have no block when the variant has the same items") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 2.0), link(part(3), 1.0)),
            execution("ИЗД.001-02", link(part(2), 2.0), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.2", "АБ.3")
        data.variants.shouldBeEmpty()
    }

    should("print an item with another quantity under every execution it is in, not in the main part") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 2.0)),
            execution("ИЗД.001-02", link(part(2), 3.0)),
        )

        val data = root.toSpecificationData()

        data.parts shouldBe emptyList()
        data.variants.map { it.designation } shouldBe listOf("ИЗД.001", "ИЗД.001-02")
        data.variants[0].parts.single().quantity shouldBe 2.0
        data.variants[1].parts.single().quantity shouldBe 3.0
    }

    should("put a unique item of the root in the root block and keep the common ones in the main part") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 2.0), link(part(3), 1.0)),
            execution("ИЗД.001-02", link(part(2), 2.0)),
        )

        val data = root.toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.2")
        data.variants.map { it.designation } shouldBe listOf("ИЗД.001")
        data.variants.single().parts.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("give a block to the variant that has an item of its own, and none to a variant without one") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 2.0)),
            execution("ИЗД.001-02", link(part(2), 2.0), link(part(4), 1.0)),
            execution("ИЗД.001-03", link(part(2), 2.0)),
        )

        val data = root.toSpecificationData()

        data.variants.map { it.designation } shouldBe listOf("ИЗД.001-02")
        data.variants.single().parts.map { it.designation } shouldBe listOf("АБ.4")
    }

    should("compare the documents of the executions by the object id") {
        val drawing = PdmNode(
            id = PdmNodeId(50),
            kind = PdmNodeKind.AssemblyDrawing,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ИЗД.001.СБ"),
                AttributeKey.Name to AttributeValue.Text("Сборочный чертеж"),
            ),
        )
        val root = withVariants(
            execution("ИЗД.001").copy(usages = listOf(dev.reportgenerator.domain.DocumentUsage(target = drawing))),
            execution("ИЗД.001-02"),
        )

        val data = root.toSpecificationData()

        data.documentation shouldBe emptyList()
        data.variants.map { it.designation } shouldBe listOf("ИЗД.001")
        data.variants.single().documentation.map { it.designation } shouldBe listOf("ИЗД.001.СБ")
    }

    should("put the root first and the other executions by designation in natural order, not in the link order") {
        val root = withVariants(
            execution("ИЗД.001", link(part(20), 1.0)),
            execution("ИЗД.001-10", link(part(21), 1.0)),
            execution("ИЗД.001-2", link(part(22), 1.0)),
            execution("ИЗД.001-05", link(part(23), 1.0)),
        )

        val data = root.toSpecificationData()

        data.variants.map { it.designation } shouldBe listOf("ИЗД.001", "ИЗД.001-2", "ИЗД.001-05", "ИЗД.001-10")
    }

    should("put the root first even when its designation is greater than the others") {
        val root = withVariants(
            execution("ИЗД.009", link(part(20), 1.0)),
            execution("ИЗД.001-01", link(part(21), 1.0)),
        )

        val data = root.toSpecificationData()

        data.variants.map { it.designation } shouldBe listOf("ИЗД.009", "ИЗД.001-01")
    }

    should("keep the link order of the executions with equal designations") {
        val root = withVariants(
            execution("ИЗД.001", link(part(20), 1.0)),
            execution("ИЗД.001-2", link(part(31), 1.0)),
            execution("ИЗД.001-2", link(part(32), 1.0)),
        )

        val data = root.toSpecificationData()

        data.variants.map { it.designation } shouldBe listOf("ИЗД.001", "ИЗД.001-2", "ИЗД.001-2")
        data.variants.drop(1).map { it.parts.single().designation } shouldBe listOf("АБ.31", "АБ.32")
    }

    should("compare designations by number of the digit runs and ignore the case of the text") {
        NaturalOrder.compare("ИЗД-2", "ИЗД-10") shouldBeLessThan 0
        NaturalOrder.compare("ИЗД-02", "ИЗД-2") shouldBe 0
        NaturalOrder.compare("изд-b", "ИЗД-A") shouldBeGreaterThan 0
        NaturalOrder.compare("ИЗД-1", "ИЗД-1А") shouldBeLessThan 0
        NaturalOrder.compare("ИЗД-1А", "ИЗД-1") shouldBeGreaterThan 0
    }
})
