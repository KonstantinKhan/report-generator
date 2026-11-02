package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation

fun List<LinkAttributeRule>.select(
    relation: Relation,
    parentKind: PdmNodeKind,
    childKind: PdmNodeKind
): LinkAttributeRule? {
    val relationId = Registries.relations.idOf(relation) ?: return null
    val parentKindId = Registries.itemKinds.idOf(parentKind)
    val childKindId = Registries.itemKinds.idOf(childKind)

    val matching = filter { rule ->
        rule.relation == relationId &&
                (rule.parent.isEmpty() || (parentKindId != null && parentKindId in rule.parent)) &&
                (rule.child.isEmpty() || (childKindId != null && childKindId in rule.child))
    }

    return matching.maxByOrNull { rule ->
        val childScore = if (rule.child.isNotEmpty()) 2 else 0
        val parentScore = if (rule.parent.isNotEmpty()) 1 else 0
        childScore + parentScore
    }
}

fun AttributeRule.nameFor(kind: PdmNodeKind): String? {
    val kindId = Registries.itemKinds.idOf(kind)
    return kindId?.let { byKind[it] } ?: name
}
