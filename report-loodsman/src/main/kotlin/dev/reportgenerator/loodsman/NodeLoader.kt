package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.MappingConfig
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import khan366kos.mapping.AttributeRequest
import khan366kos.mapping.toDomain

private const val PROPS_BATCH_SIZE = 100

/** Загрузка объектов Loodsman: пачками для обхода дерева и по одному для [loadNode]. */
internal class NodeLoader(
    private val api: LoodsmanApi,
    private val mapping: MappingConfig,
    private val kindMapper: KindMapper,
    private val attributeRequests: AttributeRequests,
    private val limiter: Limiter,
) {

    /** Загрузка одного узла; предпроверка типа и запроса атрибутов та же, что в [loadNodes]. */
    suspend fun loadNode(
        id: PdmNodeId,
        snapshot: MetadataSnapshot,
        attributeKeys: Set<AttributeKey>,
        includeUnmapped: Boolean,
    ): PdmNode {
        val props = api.objectInfo.propObject(id.asInt())
        val kind = kindMapper.map(props.type)
            ?: error("Loodsman object $id: unmapped type '${props.type}'")
        val request = attributeRequests.attributeRequest(
            kind,
            attributeRequests.keyAttributeOf(snapshot, id.asInt(), props),
            attributeKeys,
            includeUnmapped,
        )
        return toPdmNode(props, kind, objectAttributesOf(id.asInt(), request), request)
    }

    /** Загружает узлы [ids] в [nodes]; свойства читаются пачками по [PROPS_BATCH_SIZE]. */
    suspend fun loadNodes(
        ids: List<Int>,
        snapshot: MetadataSnapshot,
        nodes: MutableMap<Int, LoadedNode>,
        query: StructureQuery,
    ) {
        val props = ids.chunked(PROPS_BATCH_SIZE).flatMap { chunk ->
            api.objectInfo.propObjects(chunk)
        }.associateBy { it.idVersion }

        val prepared = ids.map { id ->
            val prop = props[id] ?: throw LoodsmanApiException("Object not found in Loodsman: id=$id")
            id to Pair(prop, kindMapper.map(prop.type))
        }

        val keyAttributes = prepared.mapNotNull { (id, pair) ->
            pair.second?.let { id to attributeRequests.keyAttributeOf(snapshot, id, pair.first) }
        }.toMap()
        val requests = prepared.mapNotNull { (id, pair) ->
            val kind = pair.second ?: return@mapNotNull null
            id to attributeRequests.attributeRequest(
                kind, keyAttributes[id], query.attributes, query.includeUnmappedAttributes
            )
        }.toMap()
        val attributes = limiter.withPermits(requests.keys.toList()) { id ->
            objectAttributesOf(id, requests.getValue(id))
        }

        prepared.forEach { (id, pair) ->
            val (prop, kind) = pair
            nodes[id] = LoadedNode(prop, kind, attributes[id].orEmpty(), keyAttributes[id], requests[id])
        }
    }

    /** Атрибуты объекта читаются, только если запрос требует не ключевые атрибуты. */
    private suspend fun objectAttributesOf(id: Int, request: AttributeRequest): List<ObjectAttributeOutputDto> =
        if (request.needsObjectAttributes()) api.objectInfo.infoAboutVersion(id) else emptyList()

    fun toPdmNode(
        prop: PropObjectOutputDto,
        kind: PdmNodeKind,
        attributes: List<ObjectAttributeOutputDto>,
        request: AttributeRequest,
    ): PdmNode = toDomain(prop, kind, attributes, request, mapping.properties.version, mapping.properties.state)
}
