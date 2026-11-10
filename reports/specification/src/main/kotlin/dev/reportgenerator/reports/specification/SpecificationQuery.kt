package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.VariantUsage

// Specification needs the root's direct composition and the documents of the root and of its children: the
// specification (assembly) and the detail drawing (part) of a child give its format. The composition is expanded
// one level only (relationDepth), so the children of the children stay out; their documents are read (depth 2).
// The material of a material form is read by the made-from link of the form (the form is at the depth of the
// composition, its link is expanded one level further, depth 2).
// Designation, product designation, name and format are read from attributes (plus four attributes for the order of the rows: material kind, standard designation, product kind, size type), the note and the zone from the link
// attributes of the composition. The product designation is set for standard and other products only, for the other kinds it is not read.
// The replacement groups are read for the root only (depth 0): the permissible ones give the replacement notes of the items.
// The variants (исполнения) of the root are read by the variant link at the first level only (relationDepth 1): their
// composition is NOT in this query, one StructureQuery cannot expand the composition of the variants and not of the
// root's children at the same time. [loadSpecificationStructure] reads the composition of every variant by this same
// query for the variant as the root (variants = false: no variant link there).
fun specificationQuery(rootId: PdmNodeId, variants: Boolean = true): StructureQuery = StructureQuery(
    rootId = rootId,
    depth = 2,
    relations = if (variants) SPECIFICATION_RELATIONS + Relation.Variant else SPECIFICATION_RELATIONS,
    relationDepth = if (variants) mapOf(Relation.Composition to 1, Relation.Variant to 1) else mapOf(Relation.Composition to 1),
    attributes = setOf(
        AttributeKey.Designation,
        AttributeKey.ProductDesignation,
        AttributeKey.Name,
        AttributeKey.Format,
        // Индекс заказчика читается у всех узлов запроса, в отчёт берётся только у корня (см. customerIndex()).
        AttributeKey.CustomerIndex,
        // Атрибуты только для сортировки строк раздела; для видов без правила в маппинге значение остаётся пустым.
        AttributeKey.MaterialKind,
        AttributeKey.StandardDesignation,
        AttributeKey.ProductVariety,
        AttributeKey.SizeType,
    ),
    linkAttributes = setOf(
        LinkAttributeKey.Note,
        LinkAttributeKey.Zone,
        LinkAttributeKey.ApplicationMethod,
        LinkAttributeKey.KitPurpose,
    ),
    replacements = true,
    replacementDepth = 0,
)

private val SPECIFICATION_RELATIONS = setOf(Relation.Composition, Relation.Document, Relation.MadeFrom)

// The specification tree of the root: the root by [specificationQuery], then the same query for every variant of the
// root (the variant's composition and documents), placed into the variant usages of the root. [loadTreeWithVariants]
// reads the root and the variants in one load session in parallel, and the nodes they share are read once.
// Then the composition of the kits without a purpose and without a specification is read (see [withKitCompositions]).
suspend fun PdmNodeStructureClient.loadSpecificationStructure(rootId: PdmNodeId): PdmNode = loadSession {
    val tree = loadTreeWithVariants(specificationQuery(rootId)) { specificationQuery(it, variants = false) }
    withKitCompositions(tree)
}

// A kit in the tree has its documents only: its composition is one level below the query of the root. Every kit without a
// purpose and without a specification (of the root and of every variant) gets its composition by [specificationQuery]
// for the kit as the root, placed into its usage. The kit is read once for all executions (the load session shares it).
// The composition of a kit inside such a kit is not read: the kit inside is not shown.
private suspend fun PdmNodeStructureClient.withKitCompositions(tree: PdmNode): PdmNode {
    val executions = listOf(tree) + tree.usages.filterIsInstance<VariantUsage>().map { it.target }
    val kitIds = executions.flatMap { it.kitIdsToLoad() }.distinct()
    if (kitIds.isEmpty()) return tree
    val full = kitIds.zip(loadTrees(kitIds.map { specificationQuery(it, variants = false) })).toMap()
    val withKits = tree.withKitNodes(full)
    return withKits.copy(usages = withKits.usages.map { usage ->
        if (usage is VariantUsage) usage.copy(target = usage.target.withKitNodes(full)) else usage
    })
}

// Kits whose composition is read: a composition usage of the kind Kit without the purpose and without a specification.
private fun PdmNode.kitIdsToLoad(): List<PdmNodeId> = usages.filterIsInstance<CompositionUsage>()
    .filter { it.target.kind == PdmNodeKind.Kit && it.kitPurpose() == null && !it.target.hasSpecification() }
    .map { it.target.id }

// The node with the loaded kits in its composition usages (the same usages, the target of a kit replaced).
private fun PdmNode.withKitNodes(full: Map<PdmNodeId, PdmNode>): PdmNode = copy(usages = usages.map { usage ->
    if (usage is CompositionUsage && usage.target.id in full) usage.copy(target = full.getValue(usage.target.id)) else usage
})
