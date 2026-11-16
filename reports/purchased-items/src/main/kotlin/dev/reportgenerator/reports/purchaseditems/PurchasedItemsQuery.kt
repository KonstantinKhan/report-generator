package dev.reportgenerator.reports.purchaseditems

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery

/**
 * Bounded explicit query for the purchased-items report. The depth is deliberately finite: the
 * server must not accidentally request an unbounded product graph. Cycles are also rejected by
 * [toPurchasedItemsData].
 */
fun purchasedItemsQuery(rootId: PdmNodeId, maxDepth: Int = 32): StructureQuery = StructureQuery(
    rootId = rootId,
    depth = maxDepth,
    relations = setOf(Relation.Composition, Relation.Document),
    relationDepth = mapOf(Relation.Composition to maxDepth, Relation.Document to 1),
    attributes = setOf(
        AttributeKey.Designation,
        AttributeKey.Name,
        AttributeKey.CompanyName,
        AttributeKey.SourceReceipt,
        AttributeKey.ProductCode,
        AttributeKey.AlternativeProductCode,
        AttributeKey.SupplyDocument,
        AttributeKey.Supplier,
        AttributeKey.AdjustmentQuantity,
        AttributeKey.DevelopedBy,
        AttributeKey.LastModifiedNumber,
        AttributeKey.PrimaryApplicability,
        AttributeKey.Department,
    ),
    linkAttributes = setOf(LinkAttributeKey.Note, LinkAttributeKey.KitPurpose, LinkAttributeKey.SpecificationSection),
)

suspend fun PdmNodeStructureClient.loadPurchasedItemsStructure(rootId: PdmNodeId, maxDepth: Int = 32) =
    loadTree(purchasedItemsQuery(rootId, maxDepth))

