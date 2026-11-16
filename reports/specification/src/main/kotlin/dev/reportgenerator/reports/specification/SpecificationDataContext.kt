package dev.reportgenerator.reports.specification

import dev.reportgenerator.ir.FlowAppendix
import dev.reportgenerator.ir.FlowAppendixBlock
import dev.reportgenerator.ir.FlowKitBlock
import dev.reportgenerator.ir.FlowKits
import dev.reportgenerator.ir.FlowNotes
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.RecordBuilder
import dev.reportgenerator.template.dataContext
import java.math.BigDecimal

// Adapter SpecificationData -> DataContext (schema + values) for the template binds of the specification
// sheet. Extend by adding fields to `doc { }`: the schema is declared by the same builder that supplies the values.
// `item { }` declares (schema only, no values) the RAW row record the flow table of gost-spec.yaml reads:
// the YAML decides the grouping, numbering and how a value is shown. The values of each row come from itemRecords().
fun SpecificationData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
        // null = no value: the bind of the customer representative table is `optional`, its cell stays empty.
        string("customerIndex", customerIndex)
    }
    item { itemFields(null) }
}

// One record per item, the sections in the order of the sheet and the items of a section in their data order.
// Fields without a value (position of documentation, quantity of documentation or of a non-basic item, unit of a non-material) are left out:
// the template marks their cells `optional` or does not bind them.
fun SpecificationData.itemRecords(): List<DataValue.Record> = rows().records()

// The part "Переменные данные для исполнений" after the main table (same columns and groups), null when no execution has
// a block. The title spans the designation and the name columns; the label of a block is the execution's designation.
internal fun SpecificationData.variantAppendix(): FlowAppendix? {
    if (variants.isEmpty()) return null
    return FlowAppendix(
        title = VARIANTS_TITLE,
        titleFrom = "designation",
        titleTo = "name",
        labelColumn = "name",
        blocks = variants.map { FlowAppendixBlock(it.designation, it.rows().records(), it.kitsFlow()) },
    )
}

internal const val VARIANTS_TITLE = "Переменные данные для исполнений"

// The blocks of the kits without a specification, after the kits rows of the main table; null when there are none.
// The title is drawn only when the main table has no kits rows (the group KIT is skipped then).
internal fun SpecificationData.kitsFlow(): FlowKits? = kitsFlow(kits, kitBlocks)

// The same for a block of an execution: its kits rows and its kit blocks.
private fun SpecificationVariant.kitsFlow(): FlowKits? = kitsFlow(kits, kitBlocks)

private fun kitsFlow(kits: List<KitItem>, kitBlocks: List<KitBlock>): FlowKits? {
    if (kitBlocks.isEmpty()) return null
    return FlowKits(
        blocks = kitBlocks.map { FlowKitBlock(it.label, it.rows().records()) },
        labelColumn = "name",
        title = KITS_TITLE.takeIf { kits.isEmpty() },
    )
}

// The title of the kits section (the group title of the kits in the flow table, gost-spec.yaml).
internal const val KITS_TITLE = "Комплекты"

// The notes of the kits with a purpose, after the variants part: the text of every note in its own row spanning the
// designation and the name columns, null when there is no note.
internal fun SpecificationData.kitNoteTail(): FlowNotes? {
    if (kitNotes.isEmpty()) return null
    return FlowNotes(texts = kitNotes, spanFrom = "designation", spanTo = "name")
}

private fun List<ItemRow>.records(): List<DataValue.Record> = map { row ->
    dataContext { item { itemFields(row) } }.get("item") as DataValue.Record
}

// Section of a row: the template groups the rows by it, in the order of GOST R 2.106-2019 p.1.
enum class SpecificationSection { DOCUMENTATION, ASSEMBLY, PART, STANDARD, OTHER, MATERIAL, KIT }

// One row of the sheet, the fields of all sections in one shape (the section tells which of them are set).
class ItemRow(
    val section: SpecificationSection,
    val position: Int? = null,
    val designation: String? = null,
    val name: String,
    val quantity: Double? = null,
    val format: String? = null,
    val zone: String? = null,
    val notes: List<String> = emptyList(),
    // Sub-title of the group (product kind in the plural) for standard and other products; null = no sub-title.
    val subgroup: String? = null,
)

internal fun SpecificationData.rows(): List<ItemRow> = sectionRows(documentation, assemblies, parts, standards, others, materials, kits)

private fun SpecificationVariant.rows(): List<ItemRow> = sectionRows(documentation, assemblies, parts, standards, others, materials, kits)

// The rows of a kit block: its sections (without kits: a kit inside a kit is not shown).
private fun KitBlock.rows(): List<ItemRow> = sectionRows(documentation, assemblies, parts, standards, others, materials, emptyList())

internal fun sectionRows(
    documentation: List<DocumentationItem>,
    assemblies: List<AssemblyItem>,
    parts: List<PartItem>,
    standards: List<StandardItem>,
    others: List<OtherItem>,
    materials: List<MaterialItem>,
    kits: List<KitItem>,
): List<ItemRow> =
    documentation.map {
        ItemRow(SpecificationSection.DOCUMENTATION, designation = it.designation, name = it.name, format = it.format, notes = it.notes)
    } + assemblies.map {
        ItemRow(SpecificationSection.ASSEMBLY, it.position, it.designation, it.name, it.quantity, zone = it.zone, format = it.format, notes = it.notes)
    } + parts.map {
        ItemRow(SpecificationSection.PART, it.position, it.designation, it.name, it.quantity, zone = it.zone, format = it.format, notes = it.notes)
    } + standards.map {
        ItemRow(SpecificationSection.STANDARD, it.position, it.designation, it.name, it.quantity, zone = it.zone, notes = it.notes, subgroup = SpecificationSubgroups.of(it))
    } + others.map {
        ItemRow(SpecificationSection.OTHER, it.position, it.designation, it.name, it.quantity, zone = it.zone, notes = it.notes, subgroup = SpecificationSubgroups.of(it))
    } + materials.map {
        ItemRow(SpecificationSection.MATERIAL, it.position, it.designation, it.name, it.quantity, zone = it.zone, notes = it.notes)
    } + kits.map {
        ItemRow(SpecificationSection.KIT, it.position, it.designation, it.name, it.quantity, zone = it.zone, format = it.format, notes = it.notes)
    }

internal fun RecordBuilder.itemFields(row: ItemRow?) {
    integer("position", row?.position?.toLong())
    string("designation", row?.designation)
    string("name", row?.name)
    enum("section", SpecificationSection.entries.map { it.name }, row?.section?.name)
    // BigDecimal.valueOf(double): the shortest decimal text of the double, as the legacy formatter used it
    decimal("quantity", row?.quantity?.let { BigDecimal.valueOf(it) })
    string("format", row?.format)
    string("zone", row?.zone)
    // The template has no list binds: the notes of a row are one text, one note per line.
    string("notes", row?.notes?.takeIf { it.isNotEmpty() }?.joinToString("\n"))
    // Sub-title of the group (product kind, plural) of standard and other products; absent = no sub-title.
    string("subgroup", row?.subgroup)
}
