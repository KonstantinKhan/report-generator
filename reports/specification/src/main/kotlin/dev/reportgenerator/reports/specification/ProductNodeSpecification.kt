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
import dev.reportgenerator.domain.VariantUsage
import java.math.BigDecimal
import java.math.RoundingMode
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByDesignationAndName
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByMaterialKind
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByStandard

// Converts the product structure of the root node into the specification data. Each section takes the usages whose
// target has the kind of the section; usages of other kinds (complex, unmapped, drawings other than the assembly
// drawing) do not get into the sheet. The rows of a section are ordered by the rules of [SpecificationOrder] (the position from
// Loodsman is only shown); kits are sorted by position.
//
// Kits (комплекты): a kit with the purpose («Назначение комплекта», link attribute) is not an item of any section, it
// gives the note of the sheet instead (`kitNotes`, from the root and every execution). A kit without the purpose and with
// a specification (a DOCUMENT usage of the kind Specification) is a row of the kits section. A kit without the purpose
// and without a specification is a block of its composition (`kitBlocks`), in the main part or in the block of every
// execution it is in, as the other items. A kit inside such a kit is not shown.
//
// Document sets (комплекты документов, child of the composition): a document set with the purpose is a note too, the same
// as a kit with the purpose, and it needs no operational documents list for the note. Without the purpose, a document set
// with an operational documents list (ведомость эксплуатационных документов, a document of the set) is a row of the kits
// section; without the list it is not shown and takes no part in the comparison of the executions. A document set inside
// a kit is not shown.
//
// Variants (исполнения, связь VariantUsage от корня): an item is common when every execution (the root and each
// variant) has it with the same object id and the same quantity. Common items stay in the main part; every other item
// goes to the block of each execution it is in (the root's block first, then the variants in link order). An execution
// without such items has no block.
// The designation and the name of the root object (the stamp and the header of the sheet). A missing name is the designation.
internal fun PdmNode.documentNames(): Pair<String, String> {
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
    return documentDesignation to documentName
}

fun PdmNode.toSpecificationData(): SpecificationData {
    val (documentDesignation, documentName) = documentNames()
    val executions = listOf(this) + usages.filterIsInstance<VariantUsage>().map { it.target }
    val keysOf = executions.map { it.specificationKeys() }
    val common = keysOf.reduce { acc, keys -> acc intersect keys }
    val isCommon: (ItemKey) -> Boolean = { it in common }
    // The replacement notes of an execution's items (permissible groups only), computed once per execution.
    val replacementsOf = executions.map { ReplacementNotes.of(it) }

    val main = sectionsOf(replacementsOf.first()) { isCommon(it) }
    val designations = executions.map { it.designationLabel() }
    // The root execution first, the others by designation (natural order); the sort is stable, so equal designations keep the link order.
    val order = executions.indices.sortedWith(
        compareBy<Int> { it != 0 }.thenComparator { a, b -> NaturalOrder.compare(designations[a], designations[b]) }
    )
    val variants = order.map { index ->
        val sections = executions[index].sectionsOf(replacementsOf[index]) { !isCommon(it) }
        SpecificationVariant(
            designation = designations[index],
            documentation = sections.documentation,
            assemblies = sections.assemblies,
            parts = sections.parts,
            standards = sections.standards,
            others = sections.others,
            materials = sections.materials,
            kits = sections.kits,
            kitBlocks = sections.kitBlocks,
        )
    }.filterNot { it.isEmpty() }

    // One note per kit and purpose: the root first, then the executions in the order of their blocks.
    val kitNotes = order.flatMap { executions[it].notesOfKits() }
        .distinctBy { it.kitId to it.purpose }
        .map { it.text }

    return SpecificationData(
        documentDesignation = documentDesignation,
        documentName = documentName,
        documentation = main.documentation,
        assemblies = main.assemblies,
        parts = main.parts,
        standards = main.standards,
        others = main.others,
        materials = main.materials,
        kits = main.kits,
        kitBlocks = main.kitBlocks,
        variants = variants,
        kitNotes = kitNotes,
    )
}

// The items of one execution in the sections of the sheet; `keep` selects the items by their key.
private class Sections(
    val documentation: List<DocumentationItem>,
    val assemblies: List<AssemblyItem>,
    val parts: List<PartItem>,
    val standards: List<StandardItem>,
    val others: List<OtherItem>,
    val materials: List<MaterialItem>,
    val kits: List<KitItem>,
    val kitBlocks: List<KitBlock>,
)

private fun PdmNode.sectionsOf(
    replacements: Map<Int, ReplacementNote>,
    documentSets: Boolean = true,
    keep: (ItemKey) -> Boolean,
): Sections {
    // A kit or a document set with the purpose is a note, not an item: it is left out of every section.
    val composition = usages.filterIsInstance<CompositionUsage>().filter { it.isShown(documentSets) && keep(it.itemKey()) }
    return Sections(
        documentation = usages.filterIsInstance<DocumentUsage>()
            .filter { it.isSpecificationDocument() && keep(ItemKey(it.target.id.asInt(), null)) }
            .map { it.target.toDocumentationItem() }
            .sortedWith(documentationOrder),
        assemblies = composition.ofKind(PdmNodeKind.Assembly).map { it.toAssemblyItem(replacements) }.sortedByDesignationAndName(),
        parts = composition.ofKind(PdmNodeKind.Part).map { it.toPartItem(replacements) }.sortedByDesignationAndName(),
        standards = composition.ofKind(PdmNodeKind.Standard).map { it.toStandardItem(replacements) }.sortedByStandard(),
        others = composition.ofKind(PdmNodeKind.Other).map { it.toOtherItem(replacements) }.sortedByStandard(),
        materials = composition.filter { it.target.kind in materialKinds }
            .map { it.toMaterialItem(replacements) }.sortedByMaterialKind(),
        kits = (composition.ofKind(PdmNodeKind.Kit).filter { it.target.hasSpecification() }
            .map { it.toKitItem(replacements) } +
            composition.ofKind(PdmNodeKind.DocumentSet).mapNotNull { usage ->
                usage.target.operationalDocumentsList()?.let { usage.toDocumentSetItem(it) }
            }).sortedByPosition(),
        kitBlocks = composition.ofKind(PdmNodeKind.Kit).filterNot { it.target.hasSpecification() }
            .sortedByUsagePosition().map { it.toKitBlock() }.filterNot { it.isEmpty() },
    )
}

// Identity of an item for the comparison of the executions: the object id and the quantity of the composition link
// (null for a document).
internal data class ItemKey(val targetId: Int, val quantity: Quantity?)

internal fun CompositionUsage.itemKey(): ItemKey = ItemKey(target.id.asInt(), quantity)

// The composition usage gets into the sheet: a kit or a document set with the purpose is a note, and a document set gets in
// only in the root and the executions (`documentSets`) with an operational documents list.
internal fun CompositionUsage.isShown(documentSets: Boolean): Boolean = kitPurpose() == null &&
    (target.kind != PdmNodeKind.DocumentSet || (documentSets && target.operationalDocumentsList() != null))

// The keys of every item of the execution: its composition and its specification documents. A kit or a document set with
// the purpose is a note, so it does not take part in the comparison of the executions.
private fun PdmNode.specificationKeys(): Set<ItemKey> =
    usages.filterIsInstance<CompositionUsage>().filter { it.isShown(documentSets = true) }.mapTo(HashSet()) { it.itemKey() } +
        usages.filterIsInstance<DocumentUsage>().filter { it.isSpecificationDocument() }
            .mapTo(HashSet()) { ItemKey(it.target.id.asInt(), null) }

// Purpose of a kit or a document set (link attribute «Назначение комплекта», the same for both kinds): the text of the
// attribute, null for any other usage, a kit or a document set without the purpose, or a blank purpose.
internal fun CompositionUsage.kitPurpose(): String? = when (target.kind) {
    PdmNodeKind.Kit, PdmNodeKind.DocumentSet -> attributes[LinkAttributeKey.KitPurpose].textOrNull()
    else -> null
}

// A kit has a specification when one of its documents is of the kind Specification.
internal fun PdmNode.hasSpecification(): Boolean =
    usages.filterIsInstance<DocumentUsage>().any { it.target.kind == PdmNodeKind.Specification }

// The operational documents list (ведомость эксплуатационных документов) of a document set: the first document of the kind
// that the set has, null when there is none.
internal fun PdmNode.operationalDocumentsList(): PdmNode? = usages.filterIsInstance<DocumentUsage>()
    .firstOrNull { it.target.kind == PdmNodeKind.OperationalDocumentsList }?.target

// A document set with its operational documents list: a row of the kits section. The designation and the name are the
// attributes of the list, the format is its format; the quantity is the quantity of the composition link (the lower bound
// of a range, as the other items). The row has no position, zone or notes.
internal fun CompositionUsage.toDocumentSetItem(list: PdmNode): KitItem {
    val (designation, name) = list.designationAndName()
    return KitItem(
        position = null,
        designation = designation,
        name = name,
        quantity = quantityOf(),
        zone = null,
        format = list.attributes[AttributeKey.Format].textOrNull(),
        notes = emptyList(),
    )
}

// The note of a kit or a document set with the purpose: "Примечание - <обозначение> - <наименование> <значение>". The
// designation and the name are the attributes of the kit or the document set itself (not of its operational documents
// list). Without designation: "Примечание - <наименование> <значение>".
internal class KitNote(val kitId: Int, val purpose: String, val text: String)

internal fun PdmNode.notesOfKits(): List<KitNote> = usages.filterIsInstance<CompositionUsage>().mapNotNull { usage ->
    val purpose = usage.kitPurpose() ?: return@mapNotNull null
    val (designation, name) = usage.target.designationAndName()
    KitNote(
        kitId = usage.target.id.asInt(),
        purpose = purpose,
        text = "Примечание - ${listOfNotNull(designation, name).joinToString(" - ")} $purpose",
    )
}

// Designation of an execution: the label of its block in the variants section.
internal fun PdmNode.designationLabel(): String = when (val value = attributeText(AttributeKey.Designation)) {
    is TextState.Value -> value.text
    is TextState.Missing -> throw SpecificationDataException("Node ${id.asInt()}: designation is missing for an execution")
    is TextState.NotLoaded -> throw SpecificationDataException("Node ${id.asInt()}: designation is not loaded")
}

// Which documents get into the documentation section. Kept as a named function: the rule will be refined later.
internal fun DocumentUsage.isSpecificationDocument(): Boolean = target.kind == PdmNodeKind.AssemblyDrawing

// Order of the documentation section: by designation, without one by name.
internal val documentationOrder: Comparator<DocumentationItem> = compareBy { it.designation ?: it.name }

// Natural order of designations: a run of digits compares as a number ("-2" < "-10"), the rest of the text compares
// ignoring case. Texts equal in this order compare as 0 (the caller's stable sort keeps their order).
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val byNumber = compareNumbers(a.substring(startA, i), b.substring(startB, j))
                if (byNumber != 0) return byNumber
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    // Digit runs as numbers without leading zeros: a longer run is the bigger number, equal lengths compare as text.
    private fun compareNumbers(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        return if (x.length != y.length) x.length.compareTo(y.length) else x.compareTo(y)
    }
}

// Items with a position go first in position order (equal positions keep the usage order); items without one go
// after them, by designation or, without a designation, by name.
internal fun <T : PositionedItem> List<T>.sortedByPosition(): List<T> {
    val (positioned, unpositioned) = partition { it.position != null }
    return positioned.sortedBy { it.position } + unpositioned.sortedBy { it.designation ?: it.name }
}

internal fun List<CompositionUsage>.ofKind(kind: PdmNodeKind): List<CompositionUsage> =
    filter { it.target.kind == kind }

// The same order as [sortedByPosition], for the usages: by position, then by designation or name.
private fun List<CompositionUsage>.sortedByUsagePosition(): List<CompositionUsage> {
    val (positioned, unpositioned) = partition { it.positionOf() != null }
    return positioned.sortedBy { it.positionOf() } + unpositioned.sortedBy { usage ->
        usage.target.designationAndName().let { (designation, name) -> designation ?: name }
    }
}

internal fun PdmNode.toDocumentationItem(): DocumentationItem {
    val (designation, name) = designationAndName()
    val format = formatCell(attributes[AttributeKey.Format].textOrNull())
    return DocumentationItem(designation = designation, name = name, format = format.column, notes = format.notes)
}

// Values every composition item reads. The unit is read for all sections, but only materials show it (in the notes).
// The quantity is the exact one or the lower bound of a range; the upper bound of a range is the note `maxQuantityNote`.
// A non-basic item of a permissible replacement group has no quantity in the column (null); its count is the first note
// (`countNote`, the lower bound of the quantity), the replacement texts are the last notes. The application method of a
// production member is the note after the notes of the item and before the replacement texts.
private class CompositionFields(
    val position: Int?,
    val quantity: Double?,
    val countNote: String?,
    val unit: String?,
    val maxQuantityNote: String?,
    val applicationMethod: String?,
    val replacementTexts: List<String>,
) {
    // The notes in the order of the sheet: the count of a non-basic item, the notes of the item, the application method,
    // the replacement texts. The replacement text of a basic item starts with a capital letter only when it is the first note of the item.
    fun notes(base: List<String>): List<String> {
        val head = listOfNotNull(countNote)
        val texts = if (head.isEmpty() && base.isEmpty() && applicationMethod == null) capitalizedFirst(replacementTexts) else replacementTexts
        return head + base + listOfNotNull(applicationMethod) + texts
    }

    private fun capitalizedFirst(texts: List<String>): List<String> {
        val first = texts.firstOrNull() ?: return texts
        if (!first.startsWith(ReplacementNotes.BASIC_TEXT_START)) return texts
        return listOf(first.replaceFirstChar { it.uppercaseChar() }) + texts.drop(1)
    }
}

private fun CompositionUsage.fields(replacements: Map<Int, ReplacementNote>, material: Boolean = false): CompositionFields {
    val replacement = linkId?.let { replacements[it] }
    val nonBasic = replacement?.isNonBasic == true
    val minQuantity = quantityOf()
    val unit = unitOf()
    return CompositionFields(
        position = positionOf(),
        quantity = minQuantity.takeUnless { nonBasic },
        countNote = if (nonBasic) countNoteOf(minQuantity, unit, material) else null,
        unit = unit,
        maxQuantityNote = maxQuantityNoteOf(material),
        applicationMethod = replacement?.applicationMethod,
        replacementTexts = replacement?.texts.orEmpty(),
    )
}

internal fun CompositionUsage.toAssemblyItem(replacements: Map<Int, ReplacementNote>): AssemblyItem {
    val fields = fields(replacements)
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
        notes = fields.notes(format.notes + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf())),
    )
}

// A kit with a specification: the same cells as an assembly unit; its format is the format of its specification.
internal fun CompositionUsage.toKitItem(replacements: Map<Int, ReplacementNote>): KitItem {
    val fields = fields(replacements)
    val (designation, name) = target.designationAndName()
    val format = formatCell(target.formatOf(PdmNodeKind.Specification))
    val zone = zoneCell(zoneOf())
    return KitItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        format = format.column,
        notes = fields.notes(format.notes + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf())),
    )
}

// A kit without a specification as a block: the sections of its composition, with the replacement notes of the kit itself.
// The kit's own kits are not read into the block (a kit inside a kit is not supported).
private fun CompositionUsage.toKitBlock(): KitBlock {
    val kit = target
    val (_, name) = kit.designationAndName()
    val sections = kit.sectionsOf(ReplacementNotes.of(kit), documentSets = false) { true }
    return KitBlock(
        label = name,
        documentation = sections.documentation,
        assemblies = sections.assemblies,
        parts = sections.parts,
        standards = sections.standards,
        others = sections.others,
        materials = sections.materials,
    )
}

internal fun CompositionUsage.toPartItem(replacements: Map<Int, ReplacementNote>): PartItem {
    val fields = fields(replacements)
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
        notes = fields.notes(format.notes + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf())),
    )
}

internal fun CompositionUsage.toStandardItem(replacements: Map<Int, ReplacementNote>): StandardItem {
    val fields = fields(replacements)
    val (designation, name) = target.standardDesignationAndName()
    val zone = zoneCell(zoneOf())
    return StandardItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        notes = fields.notes(zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf())),
        standardDesignation = target.attributeValue(AttributeKey.StandardDesignation),
        productKind = target.attributeValue(AttributeKey.ProductVariety),
        sizeType = target.attributeValue(AttributeKey.SizeType),
    )
}

internal fun CompositionUsage.toOtherItem(replacements: Map<Int, ReplacementNote>): OtherItem {
    val fields = fields(replacements)
    val (designation, name) = target.standardDesignationAndName()
    val zone = zoneCell(zoneOf())
    return OtherItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        notes = fields.notes(zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf())),
        standardDesignation = target.attributeValue(AttributeKey.StandardDesignation),
        productKind = target.attributeValue(AttributeKey.ProductVariety),
        sizeType = target.attributeValue(AttributeKey.SizeType),
    )
}

// Kinds of the materials section: a material itself, or a material form whose material is read from its usages.
internal val materialKinds = setOf(PdmNodeKind.Material, PdmNodeKind.MaterialForm)

// The unit of a material is the first note, the note of the composition link follows it. A non-basic material shows its
// count with the unit instead ("2,5 кг" is the first note, the unit is not repeated). A material form takes its
// name and designation from its material, the position, quantity, unit and note come from the composition link.
internal fun CompositionUsage.toMaterialItem(replacements: Map<Int, ReplacementNote>): MaterialItem {
    val fields = fields(replacements, material = true)
    val (designation, name) = target.materialNode().designationAndName()
    val zone = zoneCell(zoneOf())
    return MaterialItem(
        position = fields.position,
        designation = designation,
        name = name,
        quantity = fields.quantity,
        zone = zone.column,
        notes = fields.notes(listOfNotNull(fields.unit.takeIf { fields.countNote == null }) + zone.notes + listOfNotNull(fields.maxQuantityNote, noteOf())),
        materialKind = target.materialNode().attributeValue(AttributeKey.MaterialKind),
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

internal fun CompositionUsage.positionOf(): Int? = when (val value = position) {
    is Field.Value -> value.value
    is Field.Missing -> null
    is Field.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: position is not loaded")
}

internal fun CompositionUsage.quantityOf(): Double = when (val value = quantity) {
    is Quantity.Exact -> value.amount
    is Quantity.Range -> value.min
    is Quantity.Missing -> throw SpecificationDataException("Node ${target.id.asInt()}: quantity is missing")
    is Quantity.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: quantity is not loaded")
}

// The upper bound of a quantity range is the note "Наиб. кол. <max>"; an exact quantity has none.
private fun CompositionUsage.maxQuantityNoteOf(material: Boolean): String? {
    val range = quantity as? Quantity.Range ?: return null
    return "$MAX_QUANTITY_NOTE ${formatQuantity(range.max, material)}"
}

private const val MAX_QUANTITY_NOTE = "Наиб. кол."

// The count of a non-basic item: the quantity (its lower bound) and the unit of a material, or the piece unit.
private fun countNoteOf(quantity: Double, unit: String?, material: Boolean): String {
    val suffix = if (material) unit ?: PIECE_UNIT else PIECE_UNIT
    return "${formatQuantity(quantity, material)} $suffix"
}

private const val PIECE_UNIT = "шт."

// A material shows the number as is (decimal comma, no trailing zeros), the other sections show a whole count (rounded
// half up), as the quantity column does.
internal fun formatQuantity(value: Double, material: Boolean): String {
    val decimal = BigDecimal.valueOf(value)
    return if (material) {
        decimal.stripTrailingZeros().toPlainString().replace('.', ',')
    } else {
        decimal.setScale(0, RoundingMode.HALF_UP).toPlainString()
    }
}

private fun CompositionUsage.unitOf(): String? = when (val value = unit) {
    is Field.Value -> value.value
    is Field.Missing -> null
    is Field.NotLoaded -> throw SpecificationDataException("Node ${target.id.asInt()}: unit is not loaded")
}

// Designation and name of an item of the sheet (the rules of the name column).
internal fun PdmNode.designationAndName(): Pair<String?, String> {
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
internal sealed interface TextState {
    data class Value(val text: String) : TextState
    data object Missing : TextState
    data object NotLoaded : TextState
}

// Attribute reading: Text -> Value, Empty -> Missing, key not in the map (not requested) -> NotLoaded.
internal fun PdmNode.attributeText(key: AttributeKey): TextState = when (val value = attributes[key]) {
    null -> TextState.NotLoaded
    is AttributeValue.Text -> TextState.Value(value.value)
    is AttributeValue.Number -> TextState.Value(value.value.toString())
    AttributeValue.Empty -> TextState.Missing
}

// Attribute for the order of the rows and the sub-titles: the trimmed text, null for an empty, blank or not loaded value.
private fun PdmNode.attributeValue(key: AttributeKey): String? = attributes[key].textOrNull()?.trim()?.takeIf { it.isNotEmpty() }

// Optional text of a value (format, note): the text, or null for an empty, blank or not loaded value.
internal fun AttributeValue?.textOrNull(): String? = when (this) {
    is AttributeValue.Text -> value.takeIf { it.isNotBlank() }
    is AttributeValue.Number -> value.toString()
    AttributeValue.Empty, null -> null
}
