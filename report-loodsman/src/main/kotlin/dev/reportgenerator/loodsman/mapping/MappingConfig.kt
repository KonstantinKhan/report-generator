package dev.reportgenerator.loodsman.mapping

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class OnUnmapped {
    KEEP, SKIP, FAIL
}

@Serializable
enum class PropField {
    PRODUCT, VERSION, STATE, TYPE
}

@Serializable
sealed interface FieldSource {
    @Serializable
    @SerialName("prop")
    data class Prop(val field: PropField) : FieldSource

    @Serializable
    @SerialName("attribute")
    data class Attribute(val name: String) : FieldSource
}

@Serializable
data class KindIdentity(
    val designation: FieldSource? = null,
    val name: FieldSource? = null
)

@Serializable
data class IdentityMapping(
    val designation: FieldSource,
    val name: FieldSource,
    val byKind: Map<String, KindIdentity> = emptyMap()
)

@Serializable
data class UnmappedPolicy(
    val kind: OnUnmapped = OnUnmapped.KEEP,
    val relation: OnUnmapped = OnUnmapped.SKIP
)

@Serializable
data class AttributeRule(
    val name: String? = null,
    val byKind: Map<String, String> = emptyMap()
)

@Serializable
data class LinkAttributeRule(
    val relation: String,
    val parent: Set<String> = emptySet(),
    val child: Set<String> = emptySet(),
    val name: String
)

@Serializable
data class LinkMapping(
    val attributes: Map<String, List<LinkAttributeRule>> = emptyMap()
) {
    val positionRules: List<LinkAttributeRule> get() = attributes[POSITION_ID].orEmpty()

    companion object {
        const val POSITION_ID = "position"
    }
}

@Serializable
data class MappingConfig(
    val kinds: Map<String, String>,
    val relations: Map<String, String>,
    val requiredRelations: Set<String> = emptySet(),
    val identity: IdentityMapping,
    val attributes: Map<String, AttributeRule> = emptyMap(),
    val link: LinkMapping = LinkMapping(),
    val onUnmapped: UnmappedPolicy = UnmappedPolicy()
) {
    fun validate() {
        kinds.forEach { (_, value) ->
            Registries.itemKinds.resolve(value)
        }

        attributes.forEach { (key, rule) ->
            Registries.attributeKeys.resolve(key)
            if (rule.name == null && rule.byKind.isEmpty()) {
                throw IllegalArgumentException(
                    "AttributeRule for '$key' must have either name or byKind defined"
                )
            }
            rule.byKind.forEach { (kindId, _) ->
                Registries.itemKinds.resolve(kindId)
            }
        }

        relations.forEach { (_, value) ->
            Registries.relations.resolve(value)
        }

        requiredRelations.forEach { relName ->
            Registries.relations.resolve(relName)
            if (!relations.containsValue(relName)) {
                throw IllegalArgumentException(
                    "Required relation '$relName' not found in relations mapping"
                )
            }
        }

        identity.byKind.forEach { (kindKey, _) ->
            Registries.itemKinds.resolve(kindKey)
        }

        link.attributes.forEach { (key, rules) ->
            Registries.linkAttributeKeys.resolve(key)
            rules.validateRules()
        }
    }

    private fun List<LinkAttributeRule>.validateRules() {
        forEach { rule ->
            Registries.relations.resolve(rule.relation)
            rule.parent.forEach { Registries.itemKinds.resolve(it) }
            rule.child.forEach { Registries.itemKinds.resolve(it) }
        }

        for (i in indices) {
            for (j in i + 1 until size) {
                val a = this[i]
                val b = this[j]
                val sameShape = a.relation == b.relation &&
                    a.parent.isEmpty() == b.parent.isEmpty() &&
                    a.child.isEmpty() == b.child.isEmpty()
                if (!sameShape) continue

                if (a.parent == b.parent && a.child == b.child) {
                    throw IllegalArgumentException(
                        "Link attribute rules at indices $i and $j are identical for relation ${a.relation}"
                    )
                }
                val parentsOverlap = a.parent.isEmpty() || a.parent.intersect(b.parent).isNotEmpty()
                val childrenOverlap = a.child.isEmpty() || a.child.intersect(b.child).isNotEmpty()
                if (parentsOverlap && childrenOverlap) {
                    throw IllegalArgumentException(
                        "Link attribute rules at indices $i and $j have overlapping selectors for relation ${a.relation}"
                    )
                }
            }
        }
    }
}
