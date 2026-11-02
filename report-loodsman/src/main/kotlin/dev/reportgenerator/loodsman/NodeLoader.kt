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

/** Размер пачки свойств (get-prop-objects) на один запрос. */
private const val PROPS_BATCH_SIZE = 100

/** Загрузка объектов Loodsman: пачками для обхода дерева и по одному для [loadNode]. */
internal class NodeLoader(
    private val api: LoodsmanApi,
    private val mapping: MappingConfig,
    private val kindMapper: KindMapper,
    private val attributeRequests: AttributeRequests,
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
        val attributes = if (request.needsObjectAttributes()) api.objectInfo.infoAboutVersion(id.asInt()) else emptyList()
        return toPdmNode(props, kind, attributes, request)
    }

    /**
     * Загружает узлы [ids] в [nodes] в рамках сессии: свойства читаются пачками по [PROPS_BATCH_SIZE], уже прочитанные
     * в этой загрузке — из кэша сессии.
     */
    suspend fun loadNodes(
        session: LoadSession,
        ids: List<Int>,
        snapshot: MetadataSnapshot,
        nodes: MutableMap<Int, LoadedNode>,
        query: StructureQuery,
    ) {
        val props = session.props.getAll(ids) { missing -> loadProps(session, missing) }

        val prepared = ids.map { id ->
            val prop = props.getValue(id)
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
        val attributeKeys = requests.filterValues { it.needsObjectAttributes() }.map { (id, request) ->
            AttributesKey(id, request)
        }
        val loadedAttributes = session.attributes.getAll(attributeKeys) { missing ->
            val results = session.limiter.mapPermitted(missing) { key -> api.objectInfo.infoAboutVersion(key.id) }
            missing.zip(results).toMap()
        }

        prepared.forEach { (id, pair) ->
            val (prop, kind) = pair
            val request = requests[id]
            val attributes = if (request != null && request.needsObjectAttributes()) {
                loadedAttributes.getValue(AttributesKey(id, request))
            } else {
                emptyList()
            }
            nodes[id] = LoadedNode(prop, kind, attributes, keyAttributes[id], request)
        }
    }

    /** Свойства пачками по [PROPS_BATCH_SIZE]; объект, которого нет в Loodsman, — ошибка. */
    internal suspend fun loadProps(session: LoadSession, missing: List<Int>): Map<Int, PropObjectOutputDto> {
        // Пачки идут по одной: порядок запросов к Loodsman тот же, что был до кэша.
        val loaded = missing.chunked(PROPS_BATCH_SIZE).flatMap { chunk ->
            session.limiter.permit { api.objectInfo.propObjects(chunk) }
        }.associateBy { it.idVersion }
        return missing.associateWith { id ->
            loaded[id] ?: throw LoodsmanApiException("Object not found in Loodsman: id=$id")
        }
    }

    fun toPdmNode(
        prop: PropObjectOutputDto,
        kind: PdmNodeKind,
        attributes: List<ObjectAttributeOutputDto>,
        request: AttributeRequest,
    ): PdmNode = toDomain(prop, kind, attributes, request, mapping.properties.version, mapping.properties.state)
}
