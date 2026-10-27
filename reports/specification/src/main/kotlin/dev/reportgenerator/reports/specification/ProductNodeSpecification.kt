package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
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

// Values every composition item reads. The unit is read for all sections, but only materials show it (in the notes).
private class CompositionFields(val position: Int?, val quantity: Double, val unit: String?)

private fun CompositionUsage.fields(): CompositionFields =
    CompositionFields(position = positionOf(), quantity = quantityOf(), unit = unitOf())

private fun CompositionUsage.toAssemblyItem(): AssemblyItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    val format = formatCell(target.formatOf(PdmNodeKind.Specification))
    return AssemblyItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        format = format.column,
        notes = format.notes + listOfNotNull(noteOf()),
    )
}

private fun CompositionUsage.toPartItem(): PartItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    val format = formatCell(target.formatOf(PdmNodeKind.DetailDrawing))
    return PartItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        format = format.column,
        notes = format.notes + listOfNotNull(noteOf()),
    )
}

private fun CompositionUsage.toStandardItem(): StandardItem {
    val fields = fields()
    val (designation, name) = target.standardDesignationAndName()
    return StandardItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        notes = listOfNotNull(noteOf()),
    )
}

private fun CompositionUsage.toOtherItem(): OtherItem {
    val fields = fields()
    val (designation, name) = target.standardDesignationAndName()
    return OtherItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        notes = listOfNotNull(noteOf()),
    )
}

// The unit of a material is the first note, the note of the composition link follows it.
private fun CompositionUsage.toMaterialItem(): MaterialItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    return MaterialItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        notes = listOfNotNull(fields.unit, noteOf()),
    )
}

// Format of the sheet: the mark "*)" and the value of a complex format (with the letter x), the value itself otherwise.
private class FormatCell(val column: String?, val notes: List<String>)

private const val COMPLEX_FORMAT_MARK = "*)"

// Letters of a complex format: the Latin x and the Cyrillic х (х, Х), both cases.
private val complexFormatLetters = setOf('x', 'X', 'х', 'Х')

private fun formatCell(format: String?): FormatCell = when {
    format == null -> FormatCell(column = null, notes = emptyList())
    format.any { it in complexFormatLetters } -> FormatCell(
        column = COMPLEX_FORMAT_MARK,
        notes = listOf("$COMPLEX_FORMAT_MARK $format"),
    )
    else -> FormatCell(column = format, notes = emptyList())
}

// Format of an assembly or a part: the first document of the given kind (specification, detail drawing) that has a
// format. A document without a format or with a format not loaded gives no format.
private fun PdmNode.formatOf(documentKind: PdmNodeKind): String? = usages
    .filterIsInstance<DocumentUsage>()
    .filter { it.target.kind == documentKind }
    .firstNotNullOfOrNull { it.target.attributes[AttributeKey.Format].textOrNull() }

// Note of the composition link: null when the link has none (empty) or the note was not requested (not loaded).
private fun CompositionUsage.noteOf(): String? = attributes[LinkAttributeKey.Note].textOrNull()

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

// Standard and other products: the designation column takes the attribute "Обозначение изделия" (missing or not loaded
// gives an empty cell), the name column takes "Обозначение" (the key of the type, the full name of the product). The name
// is required, the product has no "Наименование" attribute in Loodsman.
private fun PdmNode.standardDesignationAndName(): Pair<String?, String> {
    val designationText = when (val value = attributeText(AttributeKey.ProductDesignation)) {
        is TextState.Value -> value.text
        is TextState.Missing, is TextState.NotLoaded -> null
    }
    val nameText = when (val value = attributeText(AttributeKey.Designation)) {
        is TextState.Value -> value.text
        is TextState.Missing -> throw SpecificationDataException("Node ${id.asInt()}: name is missing for a standard or other product")
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

// Optional text of a value (format, note): the text, or null for an empty, blank or not loaded value.
private fun AttributeValue?.textOrNull(): String? = when (this) {
    is AttributeValue.Text -> value.takeIf { it.isNotBlank() }
    is AttributeValue.Number -> value.toString()
    AttributeValue.Empty, null -> null
}
