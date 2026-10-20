package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.ProductNode
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.SpecificationData
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.UnmappedUsage

// Converts the product structure of the root node into the specification data.
// Composition items go first (in usage order), then documents (usages with the AssemblyDrawing child).
// Usages of unknown kind are skipped.
fun ProductNode.toSpecificationData(): SpecificationData {
    val documentDesignation = when (val value = designation) {
        is Designation.Value -> value.text
        is Designation.Missing -> error("Root node ${id.value}: designation is missing")
        is Designation.NotLoaded -> error("Root node ${id.value}: designation is not loaded")
    }
    val documentName = when (val value = name) {
        is Name.Value -> value.text
        is Name.Missing -> documentDesignation
        is Name.NotLoaded -> error("Root node ${id.value}: name is not loaded")
    }
    val composition = usages.filter { it.group() == UsageGroup.COMPOSITION }.map { it.toItem() }
    val documents = usages.filter { it.group() == UsageGroup.DOCUMENT }.map { it.toItem() }
    return SpecificationData(documentDesignation, documentName, composition + documents)
}

private enum class UsageGroup { COMPOSITION, DOCUMENT }

private fun Usage.group(): UsageGroup? = when (this) {
    is CompositionUsage -> UsageGroup.COMPOSITION.takeIf {
        child.kind != ProductKind.AssemblyDrawing && child.kind !is ProductKind.Unmapped
    }
    is DocumentUsage -> UsageGroup.DOCUMENT.takeIf { child.kind == ProductKind.AssemblyDrawing }
    is UnmappedUsage -> null
}

private fun Usage.toItem(): SpecificationItem = when (this) {
    is CompositionUsage -> child.toItem(quantity = quantityOf(), unit = unitOf())
    is DocumentUsage -> child.toItem(quantity = 0.0, unit = null)
    is UnmappedUsage -> error("Node ${child.id.value}: unmapped usage must be filtered out before conversion")
}

private fun CompositionUsage.quantityOf(): Double = when (val value = quantity) {
    is Quantity.Exact -> value.amount
    is Quantity.Range -> error(
        "Node ${child.id.value}: quantity range ${value.min}..${value.max} is not supported in specification"
    )
    is Quantity.Missing -> error("Node ${child.id.value}: quantity is missing")
    is Quantity.NotLoaded -> error("Node ${child.id.value}: quantity is not loaded")
}

private fun CompositionUsage.unitOf(): String? = when (val value = unit) {
    is Field.Value -> value.value
    is Field.Missing -> null
    is Field.NotLoaded -> error("Node ${child.id.value}: unit is not loaded")
}

private fun ProductNode.toItem(quantity: Double, unit: String?): SpecificationItem {
    val itemKind = kind.toItemKind() ?: error("Node ${id.value}: product kind $kind has no specification kind")
    val designationText = when (val value = designation) {
        is Designation.Value -> value.text
        is Designation.Missing -> null
        is Designation.NotLoaded -> error("Node ${id.value}: designation is not loaded")
    }
    val nameText = when (val value = name) {
        is Name.Value -> value.text
        is Name.Missing -> designationText ?: error("Node ${id.value}: name and designation are both missing")
        is Name.NotLoaded -> error("Node ${id.value}: name is not loaded")
    }
    return SpecificationItem(
        designation = designationText,
        name = nameText,
        kind = itemKind,
        quantity = quantity,
        unit = unit
    )
}

private fun ProductKind.toItemKind(): ItemKind? = when (this) {
    ProductKind.Assembly -> ItemKind.ASSEMBLY
    ProductKind.Part -> ItemKind.PART
    ProductKind.Standard -> ItemKind.STANDARD
    ProductKind.Other -> ItemKind.OTHER
    ProductKind.Material -> ItemKind.MATERIAL
    ProductKind.AssemblyDrawing -> ItemKind.DOCUMENTATION
    ProductKind.Complex -> ItemKind.COMPLEX
    ProductKind.Kit -> ItemKind.SET
    is ProductKind.Unmapped -> null
}
