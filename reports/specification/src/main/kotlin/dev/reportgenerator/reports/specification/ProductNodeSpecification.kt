package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity

// Converts the product structure of the root node into the specification data. Each section takes the usages whose
// target has the kind of the section; usages of other kinds (complex, kit, unmapped, drawings other than the assembly
// drawing) do not get into the sheet. Items with a position are sorted by it, items without one go after them.
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
    val composition = usages.filterIsInstance<CompositionUsage>()
    return SpecificationData(
        documentDesignation = documentDesignation,
        documentName = documentName,
        documentation = usages.filterIsInstance<DocumentUsage>()
            .filter { it.isSpecificationDocument() }
            .map { it.target.toDocumentationItem() }
            .sortedWith(documentationOrder),
        assemblies = composition.ofKind(PdmNodeKind.Assembly).map { it.toAssemblyItem() }.sortedByPosition(),
        parts = composition.ofKind(PdmNodeKind.Part).map { it.toPartItem() }.sortedByPosition(),
        standards = composition.ofKind(PdmNodeKind.Standard).map { it.toStandardItem() }.sortedByPosition(),
        others = composition.ofKind(PdmNodeKind.Other).map { it.toOtherItem() }.sortedByPosition(),
        materials = composition.ofKind(PdmNodeKind.Material).map { it.toMaterialItem() }.sortedByPosition(),
    )
}

// Which documents get into the documentation section. Kept as a named function: the rule will be refined later.
private fun DocumentUsage.isSpecificationDocument(): Boolean = target.kind == PdmNodeKind.AssemblyDrawing

// Order of the documentation section: by designation, without one by name.
private val documentationOrder: Comparator<DocumentationItem> = compareBy { it.designation ?: it.name }

// Items with a position go first in position order (equal positions keep the usage order); items without one go
// after them, by designation or, without a designation, by name.
private fun <T : PositionedItem> List<T>.sortedByPosition(): List<T> {
    val (positioned, unpositioned) = partition { it.position != null }
    return positioned.sortedBy { it.position } + unpositioned.sortedBy { it.designation ?: it.name }
}

private fun List<CompositionUsage>.ofKind(kind: PdmNodeKind): List<CompositionUsage> =
    filter { it.target.kind == kind }

private fun PdmNode.toDocumentationItem(): DocumentationItem {
    val (designation, name) = designationAndName()
    return DocumentationItem(designation = designation, name = name)
}

// Values every composition item reads. The unit is read for all sections (its absence is a data error), but only
// materials show it.
private class CompositionFields(val position: Int?, val quantity: Double, val unit: String?)

private fun CompositionUsage.fields(): CompositionFields =
    CompositionFields(position = positionOf(), quantity = quantityOf(), unit = unitOf())

private fun CompositionUsage.toAssemblyItem(): AssemblyItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    return AssemblyItem(position = fields.position, designation = designation, name = name, quantity = fields.quantity)
}

private fun CompositionUsage.toPartItem(): PartItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    return PartItem(position = fields.position, designation = designation, name = name, quantity = fields.quantity)
}

private fun CompositionUsage.toStandardItem(): StandardItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    return StandardItem(position = fields.position, designation = designation, name = name, quantity = fields.quantity)
}

private fun CompositionUsage.toOtherItem(): OtherItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    return OtherItem(position = fields.position, designation = designation, name = name, quantity = fields.quantity)
}

private fun CompositionUsage.toMaterialItem(): MaterialItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    return MaterialItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        unit = fields.unit,
    )
}

private fun CompositionUsage.positionOf(): Int? = when (val value = position) {
    is Field.Value -> value.value
    is Field.Missing -> null
    is Field.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: position is not loaded")
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

// Designation and name of an item of the sheet (the rules of the name column).
private fun PdmNode.designationAndName(): Pair<String?, String> {
    if (kind == PdmNodeKind.Material) {
        // In the specification the material designation is shown in the name column, the designation column stays empty.
        val materialName = when (val value = attributeText(AttributeKey.Designation)) {
            is TextState.Value -> value.text
            is TextState.Missing -> throw SpecificationDataException("Node ${id.asInt()}: designation is missing for a material")
            is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: designation is not loaded")
        }
        return null to materialName
    }
    if (kind == PdmNodeKind.Standard || kind == PdmNodeKind.Other) {
        val designationText = when (val value = attributeText(AttributeKey.Designation)) {
            is TextState.Value -> value.text
            is TextState.Missing -> null
            is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: designation is not loaded")
        }
        return when (val value = attributeText(AttributeKey.Name)) {
            // Old data form: the name is set, the designation column is kept as is.
            is TextState.Value -> designationText to value.text
            // New data form: the key value is in the designation, so it moves to the name column.
            is TextState.Missing -> null to (
                designationText ?: throw SpecificationDataException("Node ${id.asInt()}: name and designation are both missing")
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
    return designationText to nameText
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
