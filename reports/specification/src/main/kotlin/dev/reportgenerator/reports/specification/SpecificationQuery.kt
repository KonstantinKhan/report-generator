package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery

// Specification needs the root's direct composition and the documents of the root and of its children: the
// specification (assembly) and the detail drawing (part) of a child give its format. The composition is expanded
// one level only (relationDepth), so the children of the children stay out; their documents are read (depth 2).
// The material of a material form is read by the made-from link of the form (the form is at the depth of the
// composition, its link is expanded one level further, depth 2).
// Designation, product designation, name and format are read from attributes, the note from the link attributes of the
// composition. The product designation is set for standard and other products only, for the other kinds it is not read.
fun specificationQuery(rootId: PdmNodeId): StructureQuery = StructureQuery(
    rootId = rootId,
    depth = 2,
    relations = setOf(Relation.Composition, Relation.Document, Relation.MadeFrom),
    relationDepth = mapOf(Relation.Composition to 1),
    attributes = setOf(
        AttributeKey.Designation,
        AttributeKey.ProductDesignation,
        AttributeKey.Name,
        AttributeKey.Format,
    ),
    linkAttributes = setOf(LinkAttributeKey.Note),
)
