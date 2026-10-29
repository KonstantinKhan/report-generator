package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery

// Specification needs the root's direct composition and the documents of the root and of its children: the
// specification (assembly) and the detail drawing (part) of a child give its format. The composition is expanded
// one level only (relationDepth), so the children of the children stay out; their documents are read (depth 2).
// The material of a material form is read by the made-from link of the form (the form is at the depth of the
// composition, its link is expanded one level further, depth 2).
// Designation, product designation, name and format are read from attributes, the note and the zone from the link
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
    ),
    linkAttributes = setOf(LinkAttributeKey.Note, LinkAttributeKey.Zone),
    replacements = true,
    replacementDepth = 0,
)

private val SPECIFICATION_RELATIONS = setOf(Relation.Composition, Relation.Document, Relation.MadeFrom)

// The specification tree of the root: the root by [specificationQuery], then the same query for every variant of the
// root (the variant's composition and documents), placed into the variant usages of the root. [loadTreeWithVariants]
// reads the root and the variants in one load session in parallel, and the nodes they share are read once.
suspend fun PdmNodeStructureClient.loadSpecificationStructure(rootId: PdmNodeId): PdmNode =
    loadTreeWithVariants(specificationQuery(rootId)) { specificationQuery(it, variants = false) }
