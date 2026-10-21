package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery

// Specification needs only the root's direct composition and its documents: depth 1 is enough.
fun specificationQuery(rootId: PdmNodeId): StructureQuery = StructureQuery(
    rootId = rootId,
    depth = 1,
    relations = setOf(Relation.Composition, Relation.Document),
)
