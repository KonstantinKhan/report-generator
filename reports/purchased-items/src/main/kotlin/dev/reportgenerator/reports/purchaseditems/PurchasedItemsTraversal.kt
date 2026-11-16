package dev.reportgenerator.reports.purchaseditems

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.NodeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import java.math.BigDecimal

private data class RawOccurrence(
    val target: PdmNode,
    val parentDesignation: String,
    val unit: String,
    val quantity: BigDecimal,
    val inKit: Boolean,
    val note: String?,
    val selectionRelated: Boolean,
)

private data class RowKey(
    val itemId: Int,
    val supplyDocument: String?,
    val unit: String,
    val inclusion: String,
)

fun PdmNode.toPurchasedItemsData(): PurchasedItemsData {
    if (kind != PdmNodeKind.Assembly) {
        throw PurchasedItemsDataException("Root node ${id.asInt()}: expected ASSEMBLY, got $kind")
    }
    val designation = requiredText(AttributeKey.Designation, "designation")
    val name = text(AttributeKey.Name) ?: designation
    val occurrences = mutableListOf<RawOccurrence>()
    val references = mutableListOf<PurchasedDocumentReference>()

    fun visit(node: PdmNode, factor: BigDecimal, inKit: Boolean, selectionRelated: Boolean, path: List<Int>) {
        if (node.id != this.id) {
            val ownDocuments = node.usages.filterIsInstance<DocumentUsage>()
                .map { it.target }
                .filter { it.kind == PdmNodeKind.Unmapped("Ведомость покупных изделий") }
            if (ownDocuments.isNotEmpty()) {
                val nodeDesignation = node.text(AttributeKey.Designation) ?: node.keyText()
                    ?: throw PurchasedItemsDataException("Node ${node.id.asInt()}: designation is missing")
                val nodeName = node.text(AttributeKey.Name)?.takeIf { it != nodeDesignation }
                ownDocuments.forEach { document ->
                    val documentDesignation = document.text(AttributeKey.Designation) ?: document.keyText()
                        ?: throw PurchasedItemsDataException("VP document ${document.id.asInt()}: designation is missing")
                    references += PurchasedDocumentReference(
                        parentId = node.id.asInt(),
                        name = listOfNotNull(nodeDesignation, nodeName).joinToString(" "),
                        documentDesignation = documentDesignation,
                    )
                }
                return
            }
        }
        node.usages.filterIsInstance<CompositionUsage>().forEach { usage ->
            val childId = usage.target.id.asInt()
            if (childId in path) {
                throw PurchasedItemsDataException("Cycle in composition: ${(path + childId).joinToString(" -> ")}")
            }
            val quantity = usage.quantity.exact(node.id.asInt(), childId)
            val unit = usage.unit.valueOr("pcs")
            val childFactor = factor.multiply(quantity)
            val childInKit = inKit || usage.target.kind == PdmNodeKind.Kit
            val childSelectionRelated = selectionRelated || usage.isSelectionElement()
            if (usage.target.isPurchased()) {
                val parentDesignation = node.text(AttributeKey.Designation) ?: node.keyText()
                    ?: "#${node.id.asInt()}"
                occurrences += RawOccurrence(
                    target = usage.target,
                    parentDesignation = parentDesignation,
                    unit = unit,
                    quantity = childFactor,
                    inKit = childInKit,
                    note = usage.attributes[LinkAttributeKey.Note].text(),
                    selectionRelated = childSelectionRelated,
                )
                // A purchased node is a leaf for this report even if it has a structure of its own.
            } else {
                visit(usage.target, childFactor, childInKit, childSelectionRelated, path + childId)
            }
        }
    }

    visit(this, BigDecimal.ONE, inKit = false, selectionRelated = false, path = listOf(id.asInt()))

    val rows = occurrences.groupBy { occurrence ->
        RowKey(
            occurrence.target.id.asInt(),
            occurrence.target.text(AttributeKey.SupplyDocument),
            occurrence.unit,
            occurrence.parentDesignation,
        )
    }.map { (key, group) ->
        val target = group.first().target
        val adjustment = target.decimal(AttributeKey.AdjustmentQuantity) ?: BigDecimal.ZERO
        PurchasedItemRow(
            itemId = key.itemId,
            name = target.keyText() ?: target.text(AttributeKey.Name)
                ?: throw PurchasedItemsDataException("Purchased node ${key.itemId}: key attribute/product is missing"),
            group = purchasedItemGroup(target.keyText() ?: target.text(AttributeKey.Name).orEmpty()),
            productCode = target.text(AttributeKey.ProductCode),
            supplyDocument = key.supplyDocument,
            supplier = target.text(AttributeKey.Supplier),
            inclusion = key.inclusion,
            unit = key.unit,
            quantityOnProduct = group.filterNot { it.inKit }.sumOf { it.quantity },
            quantityInKits = group.filter { it.inKit }.sumOf { it.quantity },
            quantityAdjustment = adjustment,
            note = group.mapNotNull { it.note }.distinct().joinToString("; ").ifBlank { null },
            selectionRelated = group.any { it.selectionRelated },
        )
    }.sortedWith(compareBy<PurchasedItemRow>({ it.group.ordinal }, { it.selectionRelated }, { it.name.lowercase() }, { it.inclusion }))

    return PurchasedItemsData(
        documentDesignation = "${designation}ВП",
        documentName = name,
        companyName = text(AttributeKey.CompanyName),
        developedBy = text(AttributeKey.DevelopedBy),
        primaryApplicability = text(AttributeKey.PrimaryApplicability),
        version = (version as? Field.Value)?.value,
        state = (state as? Field.Value)?.value,
        lastModifiedNumber = text(AttributeKey.LastModifiedNumber),
        rows = rows,
        references = references.distinctBy { it.parentId to it.documentDesignation },
    )
}

private fun CompositionUsage.isSelectionElement(): Boolean {
    val section = attributes[LinkAttributeKey.SpecificationSection].text()
    val targetText = listOfNotNull(target.text(AttributeKey.Name), target.text(AttributeKey.Designation), target.keyText())
    return (listOfNotNull(section) + targetText).any { it.trim().equals("Подборные элементы", ignoreCase = true) }
}

private fun PdmNode.isPurchased(): Boolean =
    kind in setOf(PdmNodeKind.Standard, PdmNodeKind.Other) &&
        text(AttributeKey.SourceReceipt)?.trim()?.equals("Покупное", ignoreCase = true) == true

private fun Quantity.exact(parentId: Int, childId: Int): BigDecimal = when (this) {
    is Quantity.Exact -> amount.validQuantity(parentId, childId)
    is Quantity.Range -> {
        if (!min.isFinite() || !max.isFinite() || min < 0.0 || max < min) {
            throw PurchasedItemsDataException(
                "Composition $parentId -> $childId: invalid quantity range $min..$max"
            )
        }
        // Loodsman stores link quantities as min/max. For the purchased-items report,
        // a range is calculated by its maximum value.
        BigDecimal.valueOf(max)
    }
    Quantity.Missing -> throw PurchasedItemsDataException("Composition $parentId -> $childId: quantity is missing")
    Quantity.NotLoaded -> throw PurchasedItemsDataException("Composition $parentId -> $childId: quantity is not loaded")
}

private fun Double.validQuantity(parentId: Int, childId: Int): BigDecimal {
    if (!isFinite() || this < 0.0) {
        throw PurchasedItemsDataException("Composition $parentId -> $childId: invalid quantity $this")
    }
    return BigDecimal.valueOf(this)
}

private fun Field<String>.valueOr(default: String): String = when (this) {
    is Field.Value -> value.ifBlank { default }
    Field.Missing -> default
    Field.NotLoaded -> throw PurchasedItemsDataException("Composition unit is not loaded")
}

private fun PdmNode.keyText(): String? = (key as? NodeKey.Value)?.text?.takeIf { it.isNotBlank() }

private fun PdmNode.requiredText(key: AttributeKey, label: String): String = text(key)
    ?: throw PurchasedItemsDataException("Root node ${id.asInt()}: $label is missing or not loaded")

private fun PdmNode.text(key: AttributeKey): String? = attributes[key].text()

private fun AttributeValue?.text(): String? = when (this) {
    is AttributeValue.Text -> value.trim().takeIf { it.isNotEmpty() }
    is AttributeValue.Number -> value.toString()
    AttributeValue.Empty, null -> null
}

private fun PdmNode.decimal(key: AttributeKey): BigDecimal? = attributes[key].text()?.let {
    it.replace(',', '.').toBigDecimalOrNull()
        ?: throw PurchasedItemsDataException("Node ${id.asInt()}: attribute $key is not a decimal: '$it'")
}
