package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.MadeFromUsage
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import java.math.BigDecimal
import java.math.RoundingMode

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
        materials = composition.filter { it.target.kind in materialKinds }.map { it.toMaterialItem() }.sortedByPosition(),
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
    val format = formatCell(attributes[AttributeKey.Format].textOrNull())
    return DocumentationItem(designation = designation, name = name, format = format.column, notes = format.notes)
}

// Values every composition item reads. The unit is read for all sections, but only materials show it (in the notes).
// The quantity is the exact one or the lower bound of a range; the upper bound of a range is the note `maxQuantityNote`.
private class CompositionFields(
    val position: Int?,
    val quantity: Double,
    val unit: String?,
    val maxQuantityNote: String?,
)

private fun CompositionUsage.fields(material: Boolean = false): CompositionFields =
    CompositionFields(
        position = positionOf(),
        quantity = quantityOf(),
        unit = unitOf(),
        maxQuantityNote = maxQuantityNoteOf(material),
    )

private fun CompositionUsage.toAssemblyItem(): AssemblyItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    val format = formatCell(target.formatOf(PdmNodeKind.Specification))
    val zone = zoneCell(zoneOf())
    return AssemblyItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        format = format.column,
        notes = format.notes + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf()),
    )
}

private fun CompositionUsage.toPartItem(): PartItem {
    val fields = fields()
    val (designation, name) = target.designationAndName()
    val format = formatCell(target.formatOf(PdmNodeKind.DetailDrawing))
    val zone = zoneCell(zoneOf())
    return PartItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        format = format.column,
        notes = format.notes + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf()),
    )
}

private fun CompositionUsage.toStandardItem(): StandardItem {
    val fields = fields()
    val (designation, name) = target.standardDesignationAndName()
    val zone = zoneCell(zoneOf())
    return StandardItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        notes = zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf()),
    )
}

private fun CompositionUsage.toOtherItem(): OtherItem {
    val fields = fields()
    val (designation, name) = target.standardDesignationAndName()
    val zone = zoneCell(zoneOf())
    return OtherItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        notes = zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf()),
    )
}

// Kinds of the materials section: a material itself, or a material form whose material is read from its usages.
private val materialKinds = setOf(PdmNodeKind.Material, PdmNodeKind.MaterialForm)

// The unit of a material is the first note, the note of the composition link follows it. A material form takes its
// name and designation from its material, the position, quantity, unit and note come from the composition link.
private fun CompositionUsage.toMaterialItem(): MaterialItem {
    val fields = fields(material = true)
    val (designation, name) = target.materialNode().designationAndName()
    val zone = zoneCell(zoneOf())
    return MaterialItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        notes = listOfNotNull(fields.unit) + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf()),
    )
}

// The material of a material form: the first material among its usages (a form may have several, the first one wins).
private fun PdmNode.materialNode(): PdmNode {
    if (kind != PdmNodeKind.MaterialForm) return this
    return usages.filterIsInstance<MadeFromUsage>()
        .firstOrNull { it.target.kind == PdmNodeKind.Material }?.target
        ?: throw SpecificationDataException("Node ${id.asInt()}: material form has no material")
}

// A value that does not fit its narrow column goes to the notes: the column shows the mark "*)", the first note
// of the row is the mark and the value (no space between them).
private class FootnoteCell(val column: String?, val notes: List<String>)

private const val FOOTNOTE_MARK = "*)"

private fun footnoteCell(value: String?, isComplex: (String) -> Boolean): FootnoteCell = when {
    value == null -> FootnoteCell(column = null, notes = emptyList())
    isComplex(value) -> FootnoteCell(column = FOOTNOTE_MARK, notes = listOf("$FOOTNOTE_MARK$value"))
    else -> FootnoteCell(column = value, notes = emptyList())
}

// Letters of a complex format: the Latin x and the Cyrillic х (х, Х), both cases.
private val complexFormatLetters = setOf('x', 'X', 'х', 'Х')

// Format of the sheet: a complex format has the letter x.
private fun formatCell(format: String?): FootnoteCell =
    footnoteCell(format) { value -> value.any { it in complexFormatLetters } }

// Zone of the sheet: a complex zone lists several zones, separated by a comma.
private const val ZONE_SEPARATOR = ','

private fun zoneCell(zone: String?): FootnoteCell = footnoteCell(zone) { ZONE_SEPARATOR in it }

// Format of an assembly or a part: the first document of the given kind (specification, detail drawing) that has a
// format. A document without a format or with a format not loaded gives no format.
private fun PdmNode.formatOf(documentKind: PdmNodeKind): String? = usages
    .filterIsInstance<DocumentUsage>()
    .filter { it.target.kind == documentKind }
    .firstNotNullOfOrNull { it.target.attributes[AttributeKey.Format].textOrNull() }

// Note of the composition link: null when the link has none (empty) or the note was not requested (not loaded).
private fun CompositionUsage.noteOf(): String? = attributes[LinkAttributeKey.Note].textOrNull()

// Zone of the composition link: null when the link has none (empty) or the zone was not requested (not loaded).
private fun CompositionUsage.zoneOf(): String? = attributes[LinkAttributeKey.Zone].textOrNull()

private fun CompositionUsage.positionOf(): Int? = when (val value = position) {
    is Field.Value -> value.value
    is Field.Missing -> null
    is Field.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: position is not loaded")
}

private fun CompositionUsage.quantityOf(): Double = when (val value = quantity) {
    is Quantity.Exact -> value.amount
    is Quantity.Range -> value.min
    is Quantity.Missing -> throw SpecificationDataException("Node ${target.id.asInt()}: quantity is missing")
    is Quantity.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: quantity is not loaded")
}

// The upper bound of a quantity range is the note "Наиб. кол. <max>"; an exact quantity has none. A material shows the
// number as is (decimal comma, no trailing zeros), the other sections show a whole count (rounded half up), as the
// quantity column does.
private fun CompositionUsage.maxQuantityNoteOf(material: Boolean): String? {
    val range = quantity as? Quantity.Range ?: return null
    val max = BigDecimal.valueOf(range.max)
    val text = if (material) {
        max.stripTrailingZeros().toPlainString().replace('.', ',')
    } else {
        max.setScale(0, RoundingMode.HALF_UP).toPlainString()
    }
    return "$MAX_QUANTITY_NOTE $text"
}

private const val MAX_QUANTITY_NOTE = "Наиб. кол."

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
