package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
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

    should("put a kit that is in one execution only into the block of this execution, not into the main part") {
        val kit = PdmNode(
            id = PdmNodeId(30),
            kind = PdmNodeKind.Kit,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("КМП.30"),
                AttributeKey.Name to AttributeValue.Text("Комплект 30"),
            ),
            usages = listOf(link(part(31), 1.0)),
        )
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0)),
            execution("ИЗД.001-02", link(part(2), 1.0), link(kit, 1.0)),
        )

        val data = root.toSpecificationData()

        data.kitBlocks.shouldBeEmpty()
        data.variants.single().kitBlocks shouldBe listOf(
            KitBlock(label = "Комплект 30", parts = listOf(PartItem(designation = "АБ.31", name = "Деталь 31", quantity = 1.0))),
        )
    }

    should("put a kit that is in every execution with the same quantity into the main part") {
        val kit = PdmNode(
            id = PdmNodeId(30),
            kind = PdmNodeKind.Kit,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("КМП.30"),
                AttributeKey.Name to AttributeValue.Text("Комплект 30"),
            ),
            usages = listOf(link(part(31), 1.0)),
        )
        val root = withVariants(
            execution("ИЗД.001", link(kit, 1.0)),
            execution("ИЗД.001-02", link(kit, 1.0)),
        )

        val data = root.toSpecificationData()

        data.kitBlocks.map { it.label } shouldBe listOf("Комплект 30")
        data.variants.shouldBeEmpty()
    }

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

    should("order the items of an execution block by designation") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0)),
            execution("ИЗД.001-02", link(part(2), 1.0), link(part(10), 1.0), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.variants.single().parts.map { it.designation } shouldBe listOf("АБ.3", "АБ.10")
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

    // A kit with the purpose («Назначение комплекта») of the usage: a note, not an item of the executions.
    fun kitLink(kit: PdmNode, purpose: String) = CompositionUsage(
        target = kit,
        position = Field.Missing,
        quantity = Quantity.Exact(1.0),
        unit = Field.Missing,
        attributes = mapOf(LinkAttributeKey.KitPurpose to AttributeValue.Text(purpose)),
    )

    fun kit(id: Int) = PdmNode(
        id = PdmNodeId(id),
        kind = PdmNodeKind.Kit,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text("КМП.$id"),
            AttributeKey.Name to AttributeValue.Text("Комплект $id"),
        ),
    )

    // A document set («Комплект документов») with its operational documents list; the quantity is the one of the composition link.
    fun documentSetLink(id: Int, quantity: Quantity = Quantity.Exact(1.0)) = CompositionUsage(
        target = PdmNode(
            id = PdmNodeId(id),
            kind = PdmNodeKind.DocumentSet,
            usages = listOf(
                DocumentUsage(
                    target = PdmNode(
                        id = PdmNodeId(id + 1000),
                        kind = PdmNodeKind.OperationalDocumentsList,
                        attributes = mapOf(
                            AttributeKey.Designation to AttributeValue.Text("ВЭ.$id"),
                            AttributeKey.Name to AttributeValue.Text("Изделие $id"),
                        ),
                    ),
                ),
            ),
        ),
        position = Field.Missing,
        quantity = quantity,
        unit = Field.Missing,
    )

    // A document set («Комплект документов») with the purpose («Назначение комплекта») of the link and without an operational
    // documents list: a note, not an item of the executions.
    fun documentSetPurposeLink(id: Int, purpose: String) = CompositionUsage(
        target = PdmNode(
            id = PdmNodeId(id),
            kind = PdmNodeKind.DocumentSet,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("КД.$id"),
                AttributeKey.Name to AttributeValue.Text("Комплект документов $id"),
            ),
        ),
        position = Field.Missing,
        quantity = Quantity.Exact(1.0),
        unit = Field.Missing,
        attributes = mapOf(LinkAttributeKey.KitPurpose to AttributeValue.Text(purpose)),
    )

    should("put a document set that is in every execution with the same quantity into the main part") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0), documentSetLink(40, Quantity.Exact(2.0))),
            execution("ИЗД.001-02", link(part(2), 1.0), documentSetLink(40, Quantity.Exact(2.0))),
        )

        val data = root.toSpecificationData()

        data.kits.map { it.designation } shouldBe listOf("ВЭ.40")
        data.variants.shouldBeEmpty()
    }

    should("put a document set with another quantity into the block of every execution it is in") {
        val root = withVariants(
            execution("ИЗД.001", documentSetLink(40, Quantity.Exact(2.0))),
            execution("ИЗД.001-02", documentSetLink(40, Quantity.Exact(3.0))),
        )

        val data = root.toSpecificationData()

        data.kits shouldBe emptyList()
        data.variants.map { it.kits.map { kit -> kit.quantity } } shouldBe listOf(listOf(2.0), listOf(3.0))
    }

    should("compare the document set by the whole quantity of its link: the same lower bound with another upper bound is another item") {
        val root = withVariants(
            execution("ИЗД.001", documentSetLink(40, Quantity.Range(1.0, 2.0))),
            execution("ИЗД.001-02", documentSetLink(40, Quantity.Range(1.0, 3.0))),
        )

        val data = root.toSpecificationData()

        data.kits shouldBe emptyList()
        data.variants.map { it.kits.map { kit -> kit.quantity } } shouldBe listOf(listOf(1.0), listOf(1.0))
    }

    should("put a document set with the same range in every execution into the main part") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0), documentSetLink(40, Quantity.Range(1.0, 2.0))),
            execution("ИЗД.001-02", link(part(2), 1.0), documentSetLink(40, Quantity.Range(1.0, 2.0))),
        )

        val data = root.toSpecificationData()

        data.kits.map { it.quantity } shouldBe listOf(1.0)
        data.variants.shouldBeEmpty()
    }

    should("not let a document set without an operational documents list take part in the comparison") {
        val setWithoutList = CompositionUsage(
            target = PdmNode(id = PdmNodeId(41), kind = PdmNodeKind.DocumentSet),
            position = Field.Missing,
            quantity = Quantity.Exact(1.0),
            unit = Field.Missing,
        )
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0), setWithoutList),
            execution("ИЗД.001-02", link(part(2), 1.0)),
        )

        val data = root.toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.2")
        data.kits.shouldBeEmpty()
        data.variants.shouldBeEmpty()
    }

    should("not make an execution differ by a kit with the purpose: no variant block for it") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0), kitLink(kit(9), "Для сборки")),
            execution("ИЗД.001-02", link(part(2), 1.0), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.variants.single().designation shouldBe "ИЗД.001-02"
        data.variants.single().parts.map { it.designation } shouldBe listOf("АБ.3")
        data.variants.single().kits shouldBe emptyList()
        data.kitNotes shouldBe listOf("Примечание - КМП.9 - Комплект 9 Для сборки")
    }

    should("write one note per kit and purpose from the root and every execution, the root first") {
        val root = withVariants(
            execution("ИЗД.001", kitLink(kit(9), "Для сборки"), kitLink(kit(8), "Для ремонта")),
            execution("ИЗД.001-02", kitLink(kit(9), "Для сборки"), kitLink(kit(9), "Для ремонта"), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.kitNotes shouldBe listOf(
            "Примечание - КМП.9 - Комплект 9 Для сборки",
            "Примечание - КМП.8 - Комплект 8 Для ремонта",
            "Примечание - КМП.9 - Комплект 9 Для ремонта",
        )
    }

    should("not make an execution differ by a document set with the purpose: no variant block for it") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0), documentSetPurposeLink(43, "Для сборки")),
            execution("ИЗД.001-02", link(part(2), 1.0), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.parts.map { it.designation } shouldBe listOf("АБ.2")
        data.kits.shouldBeEmpty()
        data.variants.single().designation shouldBe "ИЗД.001-02"
        data.variants.single().parts.map { it.designation } shouldBe listOf("АБ.3")
        data.variants.single().kits.shouldBeEmpty()
        data.kitNotes shouldBe listOf("Примечание - КД.43 - Комплект документов 43 Для сборки")
    }

    should("write one note of a document set with the purpose for the root and the execution, not twice") {
        val root = withVariants(
            execution("ИЗД.001", link(part(2), 1.0), documentSetPurposeLink(43, "Для сборки")),
            execution("ИЗД.001-02", link(part(2), 1.0), documentSetPurposeLink(43, "Для сборки"), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.kitNotes shouldBe listOf("Примечание - КД.43 - Комплект документов 43 Для сборки")
        data.variants.single().parts.map { it.designation } shouldBe listOf("АБ.3")
    }

    should("write the notes of kits and document sets with the purpose, the root first, one per node and purpose") {
        val root = withVariants(
            execution("ИЗД.001", kitLink(kit(9), "Для сборки"), documentSetPurposeLink(43, "Для ремонта")),
            execution("ИЗД.001-02", kitLink(kit(9), "Для сборки"), documentSetPurposeLink(43, "Для сборки"), link(part(3), 1.0)),
        )

        val data = root.toSpecificationData()

        data.kitNotes shouldBe listOf(
            "Примечание - КМП.9 - Комплект 9 Для сборки",
            "Примечание - КД.43 - Комплект документов 43 Для ремонта",
            "Примечание - КД.43 - Комплект документов 43 Для сборки",
        )
    }

    should("compare designations by number of the digit runs and ignore the case of the text") {
        NaturalOrder.compare("ИЗД-2", "ИЗД-10") shouldBeLessThan 0
        NaturalOrder.compare("ИЗД-02", "ИЗД-2") shouldBe 0
        NaturalOrder.compare("изд-b", "ИЗД-A") shouldBeGreaterThan 0
        NaturalOrder.compare("ИЗД-1", "ИЗД-1А") shouldBeLessThan 0
        NaturalOrder.compare("ИЗД-1А", "ИЗД-1") shouldBeGreaterThan 0
    }
})
