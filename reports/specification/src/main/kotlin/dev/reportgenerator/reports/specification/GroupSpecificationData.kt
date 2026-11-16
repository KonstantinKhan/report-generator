package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.VariantUsage
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByDesignationAndName
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByMaterialKind
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByStandard
import java.util.IdentityHashMap

// Data of the group specification ("Групповая спецификация"): the items of the main part and of every execution in one
// table, a column per execution. The sections, their order, the sorting of the rows and the cells of the rows are the same
// as in the specification (see ProductNodeSpecification); the difference is the values of the execution columns.
//
// `executions` = the designations of the columns in their order: column 0 is the main part (the composition of the root),
// then the executions by designation (the same order as the variants section of the specification).
// `rows` = one row per item of the union (documentation first, then the sections in the order of the sheet); a row is
// the same item in every column: a key of the composition (the object id and the quantity of the link) or a document.
data class GroupSpecificationData(
    val documentDesignation: String,
    val documentName: String,
    val executions: List<String>,
    val rows: List<GroupRow>,
    // Notes of the kits and document sets with a purpose, the tail of every table (as in the specification).
    val kitNotes: List<String> = emptyList(),
    // «Индекс заказчика» корня (null = атрибута нет или он пуст): в нижнюю ячейку основной надписи с представителем заказчика.
    val customerIndex: String? = null,
)

// `values` = the text of the row in every column (the size is the number of the executions): "х" for documentation,
// "-" when the item is not in the execution (or its quantity is zero), otherwise the quantity (formatted as in the specification).
class GroupRow(
    val item: ItemRow,
    val values: List<String>,
)

// The group specification data of a root node. Kits without a specification and without a purpose (kit blocks) are
// not shown in the group specification: their composition is not a row of the table.
fun PdmNode.toGroupSpecificationData(): GroupSpecificationData {
    val (documentDesignation, documentName) = documentNames()
    val executions = listOf(this) + usages.filterIsInstance<VariantUsage>().map { it.target }
    val designations = executions.map { it.designationLabel() }
    // The root execution first, the others by designation (natural order); the same order as the variants section.
    val columns = executions.indices.sortedWith(
        compareBy<Int> { it != 0 }.thenComparator { a, b -> NaturalOrder.compare(designations[a], designations[b]) }
    ).map { executions[it] }
    // The replacement notes of every execution, keyed by the link id (the links of different executions have other ids).
    val replacements = columns.fold(mutableMapOf<Int, ReplacementNote>()) { acc, execution ->
        acc.putAll(ReplacementNotes.of(execution))
        acc
    }

    // The first usage of every key (the root first) gives the cells of the row; the quantity of the key per column.
    val compositionUsages = linkedMapOf<ItemKey, CompositionUsage>()
    val documentUsages = linkedMapOf<ItemKey, DocumentUsage>()
    val quantities = HashMap<ItemKey, MutableMap<Int, Double>>()
    columns.forEachIndexed { column, execution ->
        execution.usages.filterIsInstance<CompositionUsage>().filter { it.isShown(documentSets = true) }.forEach { usage ->
            val key = usage.itemKey()
            compositionUsages.putIfAbsent(key, usage)
            quantities.getOrPut(key) { mutableMapOf() }.putIfAbsent(column, usage.quantityOf())
        }
        execution.usages.filterIsInstance<DocumentUsage>().filter { it.isSpecificationDocument() }.forEach { usage ->
            documentUsages.putIfAbsent(ItemKey(usage.target.id.asInt(), null), usage)
        }
    }

    // Every item is registered under its key by identity: the sorts of the sections keep the instances.
    val keyOf = IdentityHashMap<Any, ItemKey>()
    fun <T : Any> T.keyed(key: ItemKey): T = also { keyOf[it] = key }
    fun ofKind(kind: PdmNodeKind) = compositionUsages.entries.filter { it.value.target.kind == kind }

    val documentation = documentUsages.map { (key, usage) -> usage.target.toDocumentationItem().keyed(key) }
        .sortedWith(documentationOrder)
    val assemblies = ofKind(PdmNodeKind.Assembly).map { (key, usage) -> usage.toAssemblyItem(replacements).keyed(key) }
        .sortedByDesignationAndName()
    val parts = ofKind(PdmNodeKind.Part).map { (key, usage) -> usage.toPartItem(replacements).keyed(key) }
        .sortedByDesignationAndName()
    val standards = ofKind(PdmNodeKind.Standard).map { (key, usage) -> usage.toStandardItem(replacements).keyed(key) }
        .sortedByStandard()
    val others = ofKind(PdmNodeKind.Other).map { (key, usage) -> usage.toOtherItem(replacements).keyed(key) }
        .sortedByStandard()
    val materials = compositionUsages.entries.filter { it.value.target.kind in materialKinds }
        .map { (key, usage) -> usage.toMaterialItem(replacements).keyed(key) }
        .sortedByMaterialKind()
    val kits = (
        ofKind(PdmNodeKind.Kit).filter { it.value.target.hasSpecification() }
            .map { (key, usage) -> usage.toKitItem(replacements).keyed(key) } +
            ofKind(PdmNodeKind.DocumentSet).mapNotNull { (key, usage) ->
                usage.target.operationalDocumentsList()?.let { usage.toDocumentSetItem(it).keyed(key) }
            }
        ).sortedByPosition()

    // The same order of the rows as the sheet: sectionRows keeps the order of the lists given.
    val items: List<Any> = documentation + assemblies + parts + standards + others + materials + kits
    val itemRows = sectionRows(documentation, assemblies, parts, standards, others, materials, kits)
    check(itemRows.size == items.size) { "group specification: ${itemRows.size} rows for ${items.size} items" }
    val rows = itemRows.zip(items).map { (row, item) ->
        val values = when (item) {
            is DocumentationItem -> columns.map { DOCUMENTATION_MARK }
            else -> {
                val key = requireNotNull(keyOf[item]) { "group specification: no key for an item" }
                val material = item is MaterialItem
                columns.indices.map { column -> quantityText(quantities[key]?.get(column), material) }
            }
        }
        GroupRow(row, values)
    }

    // The kits with a purpose: one note per kit and purpose, the root first, then the executions (the specification's rule).
    val kitNotes = columns.flatMap { it.notesOfKits() }
        .distinctBy { it.kitId to it.purpose }
        .map { it.text }

    return GroupSpecificationData(
        documentDesignation = documentDesignation,
        documentName = documentName,
        executions = designations,
        rows = rows,
        kitNotes = kitNotes,
        customerIndex = customerIndex(),
    )
}

// The mark of a documentation row in every column (the documentation is the same for every execution).
internal const val DOCUMENTATION_MARK = "х"

// The text of an item's quantity in a column: "-" when the item is not in the execution or its quantity is zero, otherwise
// the quantity as the specification shows it (materials: the number as is; others: a whole count).
internal fun quantityText(quantity: Double?, material: Boolean): String =
    if (quantity == null || quantity == 0.0) GROUP_EMPTY_MARK else formatQuantity(quantity, material)

// The mark of an empty cell of an execution column (no item in the execution, or a zero quantity).
internal const val GROUP_EMPTY_MARK = "-"
