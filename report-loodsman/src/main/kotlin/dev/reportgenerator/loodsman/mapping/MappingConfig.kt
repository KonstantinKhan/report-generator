package dev.reportgenerator.loodsman.mapping

import io.github.konstantinkhan.loodsman.PropField
import kotlinx.serialization.Serializable

@Serializable
enum class OnUnmapped {
    KEEP, SKIP, FAIL
}

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
enum class LinkField(val numeric: Boolean) {
    MIN_QUANTITY(true),
    MAX_QUANTITY(true),
    UNIT(false)
}

@Serializable
data class PropertiesMapping(
    val version: PropField = PropField.VERSION,
    val state: PropField = PropField.STATE
)

@Serializable
data class LinkFieldsMapping(
    val quantityMin: LinkField = LinkField.MIN_QUANTITY,
    val quantityMax: LinkField = LinkField.MAX_QUANTITY,
    val unit: LinkField = LinkField.UNIT
)

@Serializable
data class LinkMapping(
    val fields: LinkFieldsMapping = LinkFieldsMapping(),
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
    val attributes: Map<String, AttributeRule> = emptyMap(),
    val properties: PropertiesMapping = PropertiesMapping(),
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

        require(link.fields.quantityMin.numeric) {
            "link.fields.quantityMin must be a numeric field, got ${link.fields.quantityMin}"
        }
        require(link.fields.quantityMax.numeric) {
            "link.fields.quantityMax must be a numeric field, got ${link.fields.quantityMax}"
        }
        require(!link.fields.unit.numeric) {
            "link.fields.unit must be a text field, got ${link.fields.unit}"
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
