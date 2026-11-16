package dev.reportgenerator.reports.purchaseditems

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.NodeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.math.BigDecimal

class PurchasedItemsTraversalTest {
    @Test
    fun `nested 2 x 2 x 3 and second branch 6 are summed as 18`() {
        val purchased = item(9, "Болт", adjustment = "1")
        val nested = assembly(3, "A.003", uses(purchased, 3.0))
        val parent = assembly(2, "A.002", uses(nested, 2.0))
        val second = assembly(4, "A.004", uses(purchased, 6.0))
        val root = root(uses(parent, 2.0), uses(second, 1.0))

        val data = root.toPurchasedItemsData()

        assertEquals(2, data.rows.size, "different immediate parents must remain separate for 'Куда входит'")
        assertEquals(0, BigDecimal("12.0").compareTo(data.rows.first { it.inclusion == "A.003" }.quantityOnProduct))
        assertEquals(0, BigDecimal("6.0").compareTo(data.rows.first { it.inclusion == "A.004" }.quantityOnProduct))
        assertEquals(BigDecimal("1"), data.rows.first().quantityAdjustment)
    }

    @Test
    fun `kit quantity goes to in-kits column`() {
        val purchased = item(9, "DIN bearing")
        val kit = PdmNode(PdmNodeId(5), PdmNodeKind.Kit, attributes = attrs("K.001"), usages = listOf(uses(purchased, 4.0)))
        val row = root(uses(kit, 2.0)).toPurchasedItemsData().rows.single()
        assertEquals(BigDecimal.ZERO, row.quantityOnProduct)
        assertEquals(BigDecimal("8.00"), row.quantityInKits)
    }

    @Test
    fun `type without explicit purchased source is excluded`() {
        val notPurchased = item(9, "Болт", purchased = false)
        assertEquals(emptyList(), root(uses(notPurchased, 1.0)).toPurchasedItemsData().rows)
    }

    @Test
    fun `range quantity uses max quantity from composition link`() {
        val child = item(9, "Болт")
        val usage = uses(child, 1.0).copy(quantity = Quantity.Range(1.0, 4.0))
        val row = root(usage).toPurchasedItemsData().rows.single()
        assertEquals(0, BigDecimal("4").compareTo(row.quantityOnProduct))
    }

    @Test
    fun `document designation appends VP code without a space`() {
        val data = root(uses(item(9, "Болт"), 1.0)).toPurchasedItemsData()
        assertEquals("ROOTВП", data.documentDesignation)
    }

    @Test
    fun `invalid range quantity fails with path identifiers`() {
        val child = item(9, "Болт")
        val usage = uses(child, 1.0).copy(quantity = Quantity.Range(4.0, 1.0))
        val error = assertFailsWith<PurchasedItemsDataException> { root(usage).toPurchasedItemsData() }
        kotlin.test.assertTrue(error.message!!.contains("1 -> 9"))
    }

    @Test
    fun `cycle is rejected`() {
        val a0 = assembly(2, "A.002")
        val b = assembly(3, "A.003", uses(a0, 1.0))
        val a = a0.copy(usages = listOf(uses(b, 1.0)))
        val error = assertFailsWith<PurchasedItemsDataException> { root(uses(a, 1.0)).toPurchasedItemsData() }
        kotlin.test.assertTrue(error.message!!.contains("Cycle"))
    }

    @Test
    fun `items are grouped by product type and sorted alphabetically`() {
        val rows = root(
            uses(item(10, "Шайба Б"), 1.0),
            uses(item(11, "Болт Я"), 1.0),
            uses(item(12, "Болт А"), 1.0),
        ).toPurchasedItemsData().rows

        assertEquals(listOf(PurchasedItemGroup.BOLT, PurchasedItemGroup.BOLT, PurchasedItemGroup.WASHER), rows.map { it.group })
        assertEquals(listOf("Болт А", "Болт Я", "Шайба Б"), rows.map { it.name })
    }

    @Test
    fun `selection elements are placed after ordinary items inside product group`() {
        val selectionContainer = assembly(4, "Подборные элементы", uses(item(12, "Болт А"), 1.0))
        val rows = root(
            uses(selectionContainer, 1.0),
            uses(item(11, "Болт Я"), 1.0),
        ).toPurchasedItemsData().rows

        assertEquals(listOf("Болт Я", "Болт А"), rows.map { it.name })
        assertEquals(listOf(false, true), rows.map { it.selectionRelated })
    }

    @Test
    fun `nested own VP stops traversal while root own VP is ignored`() {
        val nestedItem = item(9, "Болт скрытый")
        val nestedVp = vp(91, "A.002 ВП")
        val nested = assembly(2, "A.002", uses(nestedItem, 1.0)).copy(
            attributes = attrs("A.002") + mapOf(AttributeKey.Name to AttributeValue.Text("Узел")),
            usages = listOf(DocumentUsage(nestedVp), uses(nestedItem, 1.0)),
        )
        val rootItem = item(10, "Шайба корневая")
        val data = root(uses(nested, 1.0), uses(rootItem, 1.0)).copy(
            usages = listOf(DocumentUsage(vp(90, "ROOT ВП")), uses(nested, 1.0), uses(rootItem, 1.0)),
        ).toPurchasedItemsData()

        assertEquals(listOf("Шайба корневая"), data.rows.map { it.name })
        assertEquals(PurchasedDocumentReference(2, "A.002 Узел", "A.002 ВП"), data.references.single())
    }

    private fun root(vararg usages: CompositionUsage) = PdmNode(
        PdmNodeId(1), PdmNodeKind.Assembly,
        attributes = attrs("ROOT") + mapOf(AttributeKey.Name to AttributeValue.Text("Редуктор")),
        usages = usages.toList(),
    )

    private fun assembly(id: Int, designation: String, vararg usages: CompositionUsage) = PdmNode(
        PdmNodeId(id), PdmNodeKind.Assembly, attributes = attrs(designation), usages = usages.toList()
    )

    private fun item(id: Int, product: String, purchased: Boolean = true, adjustment: String? = null) = PdmNode(
        PdmNodeId(id), PdmNodeKind.Standard,
        attributes = buildMap {
            put(AttributeKey.SourceReceipt, AttributeValue.Text(if (purchased) "Покупное" else "Собственное"))
            put(AttributeKey.SupplyDocument, AttributeValue.Text("ГОСТ 1"))
            adjustment?.let { put(AttributeKey.AdjustmentQuantity, AttributeValue.Text(it)) }
        },
        key = NodeKey.Value(AttributeKey.Designation, product),
    )

    private fun vp(id: Int, designation: String) = PdmNode(
        PdmNodeId(id), PdmNodeKind.Unmapped("Ведомость покупных изделий"), attributes = attrs(designation)
    )

    private fun attrs(designation: String): Map<AttributeKey, AttributeValue> =
        mapOf(AttributeKey.Designation to AttributeValue.Text(designation))

    private fun uses(target: PdmNode, quantity: Double) = CompositionUsage(
        target = target,
        position = Field.Missing,
        quantity = Quantity.Exact(quantity),
        unit = Field.Value("pcs"),
        attributes = mapOf(LinkAttributeKey.SpecificationSection to AttributeValue.Text("Крепёж")),
    )
}
