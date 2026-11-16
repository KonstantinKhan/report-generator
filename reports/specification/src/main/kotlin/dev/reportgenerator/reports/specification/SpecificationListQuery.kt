package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery

// The structure of the specification list: the composition at ANY depth (the walk of [toSpecificationListData] goes down the
// whole product) and the documents of every object (its specification, the operational documents list with its code). The
// designation, the name and the customer index are read for the nodes too (the specification's and the root's ones). The
// quantities of the composition links are always loaded; no link attributes, no replacement groups, no variants.
fun specificationListQuery(rootId: PdmNodeId): StructureQuery = StructureQuery(
    rootId = rootId,
    relations = setOf(Relation.Composition, Relation.Document),
    attributes = setOf(
        AttributeKey.Designation,
        AttributeKey.Name,
        AttributeKey.CustomerIndex,
        AttributeKey.DocumentCode,
    ),
)

// The whole structure for the specification list; the cycles of the structure are reported by the client.
suspend fun PdmNodeStructureClient.loadSpecificationListStructure(rootId: PdmNodeId): PdmNode =
    loadTree(specificationListQuery(rootId))
