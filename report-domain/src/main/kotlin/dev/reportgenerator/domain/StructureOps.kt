package dev.reportgenerator.domain

fun ProductNode.usagesOf(relation: Relation = Relation.Composition): List<Usage> =
    usages.filter { it.relation == relation }

fun ProductNode.walk(relation: Relation = Relation.Composition): Sequence<Usage> =
    sequence {
        yieldAll(usages.filter { it.relation == relation })
        for (usage in usages.filter { it.relation == relation }) {
            yieldAll(usage.child.walk(relation))
        }
    }

fun ProductNode.groupedByKind(relation: Relation = Relation.Composition): Map<ProductKind, List<Usage>> =
    usages.filter { it.relation == relation }.groupBy { it.child.kind }
