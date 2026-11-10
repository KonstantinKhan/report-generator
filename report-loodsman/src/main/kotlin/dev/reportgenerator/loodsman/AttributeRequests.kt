package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.Registries
import dev.reportgenerator.loodsman.mapping.nameFor
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import khan366kos.mapping.AttributeRequest

/** Строит запрос атрибутов для вида: имена разрешаются по правилам маппинга. */
internal class AttributeRequests(private val mapping: MappingConfig) {

    /** Правило атрибута по доменному ключу; ключ без правила проверяется до чтения объектов. */
    fun ruleOf(key: AttributeKey): AttributeRule =
        Registries.attributeKeys.idOf(key)?.let { mapping.attributes[it] }
            ?: throw LoodsmanApiException("Attribute '$key' is not configured in mapping.attributes")

    /** Ключевой атрибут типа объекта; тип обязан быть в метаданных Loodsman. */
    fun keyAttributeOf(snapshot: MetadataSnapshot, id: Int, props: PropObjectOutputDto): String? {
        val typeName = requireNotNull(props.type) { "Loodsman object $id: type is null" }
        if (!snapshot.hasObjectType(typeName)) {
            throw LoodsmanApiException("Object $id: type '$typeName' not found in Loodsman metadata")
        }
        return snapshot.keyAttributeOf(typeName)
    }

    /**
     * Единое правило чтения для загрузки дерева и одного узла: имена атрибутов разрешаются для вида.
     * [availableNames] — имена атрибутов метаданных Loodsman: optional-атрибут, которого там нет, не запрашивается
     * (его ключ опускается, значение NotLoaded). null — без отбора (как до введения optional).
     */
    fun attributeRequest(
        kind: PdmNodeKind,
        keyAttribute: String?,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean,
        availableNames: Set<String>? = null,
    ): AttributeRequest {
        val keyKey = keyAttribute?.let { name ->
            mapping.attributes.entries.firstOrNull { (_, rule) -> rule.nameFor(kind) == name }
                ?.let { (id, _) -> Registries.attributeKeys.resolve(id) }
        }
        val requested = attributeKeys.filter { key ->
            val rule = ruleOf(key)
            val name = rule.nameFor(kind)
            !(rule.optional && availableNames != null && name != null && name !in availableNames)
        }
        return AttributeRequest(
            keyAttribute = keyAttribute,
            keyKey = keyKey,
            names = requested.associateWith { key -> ruleOf(key).nameFor(kind) },
            mappedNames = mapping.attributes.values.mapNotNullTo(HashSet()) { it.nameFor(kind) },
            includeUnmapped = includeUnmapped,
        )
    }
}
