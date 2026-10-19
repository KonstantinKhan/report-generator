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
data class MappingConfig(
    val kinds: Map<String, String>,
    val relations: Map<String, String>,
    val requiredRelations: Set<String> = emptySet(),
    val identity: IdentityMapping,
    val position: FieldSource,
    val attributes: Map<String, FieldSource> = emptyMap(),
    val onUnmapped: UnmappedPolicy = UnmappedPolicy()
) {
    fun validate() {
        kinds.forEach { (_, value) ->
            Registries.itemKinds.resolve(value)
        }

        attributes.forEach { (key, _) ->
            Registries.attributeKeys.resolve(key)
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
    }
}
