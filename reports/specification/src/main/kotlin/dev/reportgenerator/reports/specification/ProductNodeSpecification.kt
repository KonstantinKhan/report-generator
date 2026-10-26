package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.SpecificationData
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.domain.TechnologicalPartUsage
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.UnmappedUsage

// Converts the product structure of the root node into the specification data.
// Composition items go first (in usage order), then documents (usages with the AssemblyDrawing target).
// Usages of unknown kind are skipped.
fun PdmNode.toSpecificationData(): SpecificationData {
    val documentDesignation = when (val value = attributeText(AttributeKey.Designation)) {
        is TextState.Value -> value.text
        is TextState.Missing -> throw SpecificationDataException("Root node ${id.asInt()}: designation is missing")
        is TextState.NotLoaded -> throw SpecificationDataException("Root node ${id.asInt()}: designation is not loaded")
    }
    val documentName = when (val value = attributeText(AttributeKey.Name)) {
        is TextState.Value -> value.text
        is TextState.Missing -> documentDesignation
        is TextState.NotLoaded -> throw SpecificationDataException("Root node ${id.asInt()}: name is not loaded")
    }
    val composition = usages.filter { it.group() == UsageGroup.COMPOSITION }.map { it.toItem() }
    val documents = usages.filter { it.group() == UsageGroup.DOCUMENT }.map { it.toItem() }
    return SpecificationData(documentDesignation, documentName, composition + documents)
}

private enum class UsageGroup { COMPOSITION, DOCUMENT }

private fun Usage.group(): UsageGroup? = when (this) {
    is CompositionUsage -> UsageGroup.COMPOSITION.takeIf {
        target.kind != PdmNodeKind.AssemblyDrawing && target.kind !is PdmNodeKind.Unmapped
    }
    is DocumentUsage -> UsageGroup.DOCUMENT.takeIf { target.kind == PdmNodeKind.AssemblyDrawing }
    is TechnologicalPartUsage -> null
    is UnmappedUsage -> null
}

private fun Usage.toItem(): SpecificationItem = when (this) {
    is CompositionUsage -> target.toItem(quantity = quantityOf(), unit = unitOf())
    is DocumentUsage -> target.toItem(quantity = 0.0, unit = null)
    is TechnologicalPartUsage -> error(
        "Node ${target.id.asInt()}: technological part usage must be filtered out before conversion"
    )
    is UnmappedUsage -> error("Node ${target.id.asInt()}: unmapped usage must be filtered out before conversion")
}

private fun CompositionUsage.quantityOf(): Double = when (val value = quantity) {
    is Quantity.Exact -> value.amount
    is Quantity.Range -> throw SpecificationDataException(
        "Node ${target.id.asInt()}: quantity range ${value.min}..${value.max} is not supported in specification"
    )
    is Quantity.Missing -> throw SpecificationDataException("Node ${target.id.asInt()}: quantity is missing")
    is Quantity.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: quantity is not loaded")
}

private fun CompositionUsage.unitOf(): String? = when (val value = unit) {
    is Field.Value -> value.value
    is Field.Missing -> null
    is Field.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: unit is not loaded")
}

private fun PdmNode.toItem(quantity: Double, unit: String?): SpecificationItem {
    val itemKind = kind.toItemKind() ?: error("Node ${id.asInt()}: product kind $kind has no specification kind")
    if (kind == PdmNodeKind.Material) {
        // In the specification the material designation is shown in the name column, the designation column stays empty.
        val materialName = when (val value = attributeText(AttributeKey.Designation)) {
            is TextState.Value -> value.text
            is TextState.Missing -> throw SpecificationDataException("Node ${id.asInt()}: designation is missing for a material")
            is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: designation is not loaded")
        }
        return SpecificationItem(
            designation = null,
            name = materialName,
            kind = itemKind,
            quantity = quantity,
            unit = unit
        )
    }
    if (kind == PdmNodeKind.Standard || kind == PdmNodeKind.Other) {
        val designationText = when (val value = attributeText(AttributeKey.Designation)) {
            is TextState.Value -> value.text
            is TextState.Missing -> null
            is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: designation is not loaded")
        }
        return when (val value = attributeText(AttributeKey.Name)) {
            // Old data form: the name is set, the designation column is kept as is.
            is TextState.Value -> SpecificationItem(
                designation = designationText,
                name = value.text,
                kind = itemKind,
                quantity = quantity,
                unit = unit
            )
            // New data form: the key value is in the designation, so it moves to the name column.
            is TextState.Missing -> SpecificationItem(
                designation = null,
                name = designationText
                    ?: throw SpecificationDataException("Node ${id.asInt()}: name and designation are both missing"),
                kind = itemKind,
                quantity = quantity,
                unit = unit
            )
            is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: name is not loaded")
        }
    }
    val designationText = when (val value = attributeText(AttributeKey.Designation)) {
        is TextState.Value -> value.text
        is TextState.Missing -> null
        is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: designation is not loaded")
    }
    val nameText = when (val value = attributeText(AttributeKey.Name)) {
        is TextState.Value -> value.text
        is TextState.Missing -> designationText
            ?: throw SpecificationDataException("Node ${id.asInt()}: name and designation are both missing")
        is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: name is not loaded")
    }
    return SpecificationItem(
        designation = designationText,
        name = nameText,
        kind = itemKind,
        quantity = quantity,
        unit = unit
    )
}

// Intermediate state of a text attribute: file-private, used only while building the specification.
private sealed interface TextState {
    data class Value(val text: String) : TextState
    data object Missing : TextState
    data object NotLoaded : TextState
}

// Attribute reading: Text -> Value, Empty -> Missing, key not in the map (not requested) -> NotLoaded.
private fun PdmNode.attributeText(key: AttributeKey): TextState = when (val value = attributes[key]) {
    null -> TextState.NotLoaded
    is AttributeValue.Text -> TextState.Value(value.value)
    is AttributeValue.Number -> TextState.Value(value.value.toString())
    AttributeValue.Empty -> TextState.Missing
}

private fun PdmNodeKind.toItemKind(): ItemKind? = when (this) {
    PdmNodeKind.Assembly -> ItemKind.ASSEMBLY
    PdmNodeKind.Part -> ItemKind.PART
    PdmNodeKind.Standard -> ItemKind.STANDARD
    PdmNodeKind.Other -> ItemKind.OTHER
    PdmNodeKind.Material -> ItemKind.MATERIAL
    PdmNodeKind.AssemblyDrawing -> ItemKind.DOCUMENTATION
    PdmNodeKind.Complex -> ItemKind.COMPLEX
    PdmNodeKind.Kit -> ItemKind.SET
    is PdmNodeKind.Unmapped -> null
}
