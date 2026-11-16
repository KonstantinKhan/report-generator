package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.reports.specification.SpecificationOrder.sortedByDesignationAndName

// Data of the specification list ("Ведомость спецификаций", ВС): one row per object (an assembly unit or a kit) of the whole
// product structure, the designation and the name are the ones of its specification. The cell "Куда входит" lists the
// parents of the object: the designation of the parent's specification and the quantity of the composition link, one line
// per link. The rows of a section are sorted as in the group specification (SpecificationOrder).
//
// The walk goes down the composition at any depth from the root (the root is not a row). An object of the kind Assembly
// or Kit, a composition child of a walked node:
// - with an operational documents list of the code «ВС» is skipped with its whole subtree (it is not walked);
// - without a specification is skipped as a row, its children are still walked (decision: the object is not a parent in
//   the cells, so the links from it are not listed; a child of it gets the parents from its other links with a specification).
// One row per object (the walk visits every object once): the parents are the links from the walked objects with a
// specification (the root included), the total is the sum of their quantities.
//
// `rows` = the rows in the order of the sheet: the assemblies, then the kits, each by designation and name.
data class SpecificationListData(
    val documentDesignation: String,
    val documentName: String,
    val rows: List<SpecificationListRow>,
    // «Индекс заказчика» корня (null = атрибута нет или он пуст): в нижнюю ячейку основной надписи с представителем заказчика.
    val customerIndex: String? = null,
)

// One row of the sheet: the designation and the name of the object's specification. `section` is ASSEMBLY or KIT;
// `parents` = one line of "Куда входит" per link (empty: the object has no parent with a specification).
class SpecificationListRow(
    val section: SpecificationSection,
    override val designation: String,
    override val name: String,
    val parents: List<SpecificationListParent>,
) : PositionedItem {
    override val position: Int? = null

    // The sum of the quantities of the parent links; null when there is no parent (the cell stays empty).
    val total: Double? get() = parents.takeIf { it.isNotEmpty() }?.sumOf { it.quantity }
}

// A line of "Куда входит": the designation of the parent's specification and the quantity of the composition link.
class SpecificationListParent(
    val designation: String,
    val quantity: Double,
)

// The specification list data of a root node. The structure is the one of [specificationListQuery] (every depth, the
// specifications and the operational documents lists of the objects).
fun PdmNode.toSpecificationListData(): SpecificationListData {
    val (documentDesignation, documentName) = documentNames()
    val rootLabel = listSpecification()?.labelOf()
        ?: throw SpecificationDataException("Root node ${id.asInt()}: specification is missing")
    val builders = LinkedHashMap<Int, RowBuilder>()
    val walked = HashSet<Int>()

    // `label` = the designation of the node's specification, null when the node has none (it is not a parent in the cells).
    fun walk(node: PdmNode, label: String?) {
        if (!walked.add(node.id.asInt())) return
        node.usages.filterIsInstance<CompositionUsage>()
            .filter { it.target.kind == PdmNodeKind.Assembly || it.target.kind == PdmNodeKind.Kit }
            .forEach { usage ->
                val child = usage.target
                if (child.hasSpecificationList()) return@forEach
                val specification = child.listSpecification()
                if (specification != null) {
                    val builder = builders.getOrPut(child.id.asInt()) {
                        val (designation, name) = specification.designationAndName()
                        RowBuilder(
                            section = if (child.kind == PdmNodeKind.Kit) SpecificationSection.KIT else SpecificationSection.ASSEMBLY,
                            designation = designation ?: throw SpecificationDataException(
                                "Node ${specification.id.asInt()}: designation of the specification is missing"
                            ),
                            name = name,
                        )
                    }
                    if (label != null) builder.parents += SpecificationListParent(label, usage.quantityOf())
                }
                walk(child, specification?.labelOf())
            }
    }
    walk(this, rootLabel)

    // The sections in the order of the sheet, the rows of a section sorted as in the group specification.
    val rows = listOf(SpecificationSection.ASSEMBLY, SpecificationSection.KIT).flatMap { section ->
        builders.values.filter { it.section == section }
            .map { SpecificationListRow(it.section, it.designation, it.name, it.parents.toList()) }
            .sortedByDesignationAndName()
    }
    return SpecificationListData(
        documentDesignation = documentDesignation,
        documentName = documentName,
        rows = rows,
        customerIndex = customerIndex(),
    )
}

// The builder of a row: the parents are added by the walk, the row is made once per object.
private class RowBuilder(
    val section: SpecificationSection,
    val designation: String,
    val name: String,
) {
    val parents = mutableListOf<SpecificationListParent>()
}

// The specification document of the object (the first document of the kind Specification), null when there is none.
private fun PdmNode.listSpecification(): PdmNode? = usages.filterIsInstance<DocumentUsage>()
    .firstOrNull { it.target.kind == PdmNodeKind.Specification }?.target

// The designation of a specification: a missing designation is a data problem (the cell of the parent has no text).
private fun PdmNode.labelOf(): String = designationAndName().first
    ?: throw SpecificationDataException("Node ${id.asInt()}: designation of the specification is missing")

// The code of the operational documents list: «ВС» (ведомость спецификаций) skips the object with its subtree.
private const val SPECIFICATION_LIST_CODE = "ВС"

private fun PdmNode.hasSpecificationList(): Boolean = usages.filterIsInstance<DocumentUsage>().any {
    it.target.kind == PdmNodeKind.OperationalDocumentsList &&
        it.target.attributes[AttributeKey.DocumentCode].textOrNull()?.trim() == SPECIFICATION_LIST_CODE
}
