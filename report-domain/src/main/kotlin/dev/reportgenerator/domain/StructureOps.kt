package dev.reportgenerator.domain

fun PdmNode.usagesOf(relation: Relation = Relation.Composition): List<Usage> =
    usages.filter { it.relation == relation }

fun PdmNode.walk(relation: Relation = Relation.Composition): Sequence<Usage> =
    sequence {
        yieldAll(usages.filter { it.relation == relation })
        for (usage in usages.filter { it.relation == relation }) {
            yieldAll(usage.target.walk(relation))
        }
    }

fun PdmNode.groupedByKind(relation: Relation = Relation.Composition): Map<PdmNodeKind, List<Usage>> =
    usages.filter { it.relation == relation }.groupBy { it.target.kind }
