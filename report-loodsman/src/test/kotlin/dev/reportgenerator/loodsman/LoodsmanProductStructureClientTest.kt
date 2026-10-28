package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.NodeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.TechnologicalPartUsage
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.ReplacementGroup
import dev.reportgenerator.domain.ReplacementType
import dev.reportgenerator.domain.ReplacementVariant
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.LinkAttributeRule
import dev.reportgenerator.loodsman.mapping.LinkMapping
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.loodsman.mapping.OnUnmapped
import dev.reportgenerator.loodsman.mapping.UnmappedPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val COMPOSITION = "Состоит из ..."
private const val TECHNOLOGICAL_PART = "Технологическая ДСЕ для"

// Размер пачки получения свойств объектов, как в клиенте.
private const val PROPS_BATCH = 100

// Базовый id обратных связей в фейке: не пересекается с id прямых связей (parent*100+порядок).
private const val INVERSE_LINK_ID_BASE = 100_000

// Ключевой атрибут всех видов в фейке: его значение лежит в product объекта.
private const val KEY_ATTRIBUTE = "Обозначение"

internal data class Obj(
    val id: Int,
    val type: String,
    val product: String,
    val attrs: Map<String, String> = emptyMap()
)

internal data class Edge(
    val parent: Int,
    val child: Int,
    val link: String = COMPOSITION,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
    val attrs: Map<String, String> = emptyMap()
)

// Запросы, которые клиент распараллеливает через семафор: по ним считается число одновременных запросов.
internal val CONCURRENT_PATHS = setOf(
    "/api/v4/ObjectInfo/get-linked-objects-for-objects",
    "/api/v4/ObjectInfo/get-original-link-attributes",
    "/api/v4/ObjectInfo/get-info-about-version-mode-3",
)

/** Запрос связей фейку: объект, id типов связей и признак inverse. */
internal data class LinkRequest(val objectId: Int, val linkTypeIds: List<Int>, val inverse: Boolean)

/** Атрибут Loodsman из справочника get-attribute-list-2. */
internal data class AttributeDef(val id: Int, val name: String)

internal class FakeLoodsman(
    private val objects: List<Obj>,
    private val edges: List<Edge>,
    private val linkNames: List<String> = listOf(COMPOSITION, "Документы"),
    private val requestDelay: Duration = Duration.ZERO,
    private val replacementRows: Map<Int, List<JsonElement>> = emptyMap(),
    /** Ожидание перед ответом на запрос связей, по первому запрошенному объекту: для проверки порядка ответов. */
    private val linkGates: Map<Int, CompletableDeferred<Unit>> = emptyMap(),
    /** Ожидание перед ответом на любой запрос из [CONCURRENT_PATHS]: держит запросы в полёте. */
    private val trackedGate: CompletableDeferred<Unit>? = null,
    /** Число запросов в полёте, при котором завершается [inFlightReached]. */
    private val signalInFlightAt: Int = Int.MAX_VALUE,
    /** Справочник атрибутов: отдаётся в get-attribute-list-2 и в [snapshot]. Пусто — имена не найдены. */
    private val attributes: List<AttributeDef> = emptyList(),
) {
    val inFlightReached = CompletableDeferred<Unit>()
    /** Пачки объектов каждого запроса связей, в порядке прихода. */
    val linkBatches: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf())
    val replacementCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val propsCalls: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf())
    val linkCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val linkRequests: MutableList<LinkRequest> = Collections.synchronizedList(mutableListOf())
    /** Связи, по которым запрошены атрибуты (по одной записи на связь, пачки разворачиваются). */
    val linkAttributeCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    /** Пачки линков каждого запроса атрибутов связей, в порядке прихода. */
    val linkAttributeBatches: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf())
    val infoCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val linkListFailures = AtomicInteger(0)
    /** Параметры каждого запроса атрибутов связей (linkIds, mode, attrIds). */
    val linkAttributeParams: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    val linkAttributeFailures = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val linkIdToEdge = ConcurrentHashMap<Int, Edge>()

    private fun linkTypeIdOf(name: String): Int = linkNames.indexOf(name) + 1

    /** Снимок метаданных, который отдаёт тот же фейк (типы связей по [linkNames], виды по [objects]). */
    fun snapshot(version: Long = 1): MetadataSnapshot = MetadataSnapshot(
        linkTypes = linkNames.mapIndexed { index, name -> LinkTypeInfo(id = index + 1, name = name) },
        objectTypes = objectTypes(),
        attributeNames = attributes.map { it.name }.toSet(),
        version = version,
        attributeIds = attributes.groupBy({ it.name }, { it.id }),
    )

    private fun objectTypes(): List<ObjectTypeInfo> =
        objects.map { it.type }.distinct().mapIndexed { index, type ->
            ObjectTypeInfo(id = index + 1, typeName = type, keyAttribute = KEY_ATTRIBUTE)
        }

    /** Клиент с кэшем метаданных из фейка (загружается при создании) или с заданным [metadata]. */
    suspend fun client(
        mapping: MappingConfig = MappingConfigLoader.load(emptyMap()),
        metadata: MetadataSource? = null,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): LoodsmanProductStructureClient {
        val api = api()
        val source = metadata ?: MetadataCache(api, validate = { emptyList() }).also { it.initialize() }
        return LoodsmanProductStructureClient(api, mapping, concurrency = concurrency, metadata = source)
    }

    private fun api(): LoodsmanApi {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/api/v4/MetaData/get-link-list" &&
                linkListFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
            ) {
                return@MockEngine respond("boom", HttpStatusCode.InternalServerError)
            }
            if (request.url.encodedPath == "/api/v4/ObjectInfo/get-original-link-attributes" &&
                linkAttributeFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
            ) {
                return@MockEngine respond("boom", HttpStatusCode.BadRequest)
            }
            val tracked = request.url.encodedPath in CONCURRENT_PATHS
            if (tracked) {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                if (now >= signalInFlightAt) inFlightReached.complete(Unit)
                trackedGate?.await()
                if (requestDelay > Duration.ZERO) delay(requestDelay)
            }
            val params = request.url.parameters
            if (request.url.encodedPath == "/api/v4/ObjectInfo/get-linked-objects-for-objects") {
                linkGates[params["objectsIds"]!!.split(",").first().toInt()]?.await()
            }
            val body: JsonElement = when (request.url.encodedPath) {
                "/api/v4/Auth/login" -> buildJsonObject {
                    put("sessionId", "s1")
                    put("userId", 1)
                    put("isEditable", true)
                }
                "/api/v4/MetaData/cache/version" -> JsonPrimitive(1)
                "/api/v4/MetaData/get-link-list" -> buildJsonArray {
                    linkNames.forEachIndexed { index, name ->
                        add(buildJsonObject {
                            put("id", index + 1)
                            put("name", name)
                            put("type", 0)
                            put("order", index)
                        })
                    }
                }
                "/api/v4/MetaData/get-attribute-list-2" -> buildJsonArray {
                    attributes.forEach { attr ->
                        add(buildJsonObject {
                            put("id", attr.id)
                            put("name", attr.name)
                            put("attrType", 0)
                            put("system", 0)
                            put("onlyListItems", 0)
                        })
                    }
                }
                "/api/v4/MetaDataCustom/types-links" -> buildJsonArray {}
                "/api/v4/MetaData/get-type-list" -> buildJsonArray {
                    objects.map { it.type }.distinct().forEachIndexed { index, type ->
                        add(buildJsonObject {
                            put("id", index + 1)
                            put("typeName", type)
                            put("attrName", KEY_ATTRIBUTE)
                            put("document", 0)
                        })
                    }
                }
                "/api/v4/ObjectInfo/get-prop-objects" -> {
                    val ids = params["objectList"]!!.split(",").map { it.toInt() }
                    propsCalls.add(ids)
                    JsonArray(ids.mapNotNull { id -> objects.find { it.id == id } }.map { propOf(it) })
                }
                "/api/v4/ObjectInfo/get-info-about-version-mode-3" -> {
                    val obj = objects.first { it.id == params["idVersion"]!!.toInt() }
                    infoCalls.add(obj.id)
                    buildJsonArray {
                        obj.attrs.forEach { (name, value) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("value", value)
                            })
                        }
                    }
                }
                "/api/v4/ObjectInfo/get-linked-objects-for-objects" -> {
                    val requestedIds = params["objectsIds"]!!.split(",").map { it.toInt() }
                    val linkTypeIds = params["linksTypesIds"]!!.split(",").map { it.toInt() }
                    val inverse = params["inverse"]!!.toBoolean()
                    linkBatches.add(requestedIds)
                    // Пачка объектов: ответ по каждому запрошенному объекту, как у Loodsman; для каждого учитывается запрос.
                    JsonArray(requestedIds.flatMap { requested ->
                        linkCalls.add(requested)
                        linkRequests.add(LinkRequest(requested, linkTypeIds, inverse))
                        // Как у Loodsman: inverse смотрит с точки запрошенного объекта, idParent = запрошенный.
                        val matched = edges.filter { edge ->
                            (if (inverse) edge.child else edge.parent) == requested &&
                                    linkTypeIdOf(edge.link) in linkTypeIds
                        }
                        matched.mapIndexed { index, edge ->
                            val neighbor = if (inverse) edge.parent else edge.child
                            // Id связи уникален в фейке: прямые связи — parent*100+порядок, обратные — за пределами.
                            val linkId = if (inverse) INVERSE_LINK_ID_BASE + edges.indexOf(edge) else edge.parent * 100 + index
                            linkIdToEdge[linkId] = edge
                            linkOf(linkId, requested, neighbor, edge.link)
                        }
                    })
                }
                "/api/v4/ObjectInfo/get-info-about-version-mode-20" -> {
                    val id = params["idVersion"]!!.toInt()
                    replacementCalls.add(id)
                    JsonArray(replacementRows[id].orEmpty())
                }
                "/api/v4/ObjectInfo/get-original-link-attributes" -> {
                    val linkIds = params["linkIds"]!!.split(",").map { it.toInt() }
                    linkAttributeBatches.add(linkIds)
                    linkAttributeParams.add(params.names().associateWith { params[it].orEmpty() })
                    linkAttributeCalls.addAll(linkIds)
                    buildJsonArray {
                        for (linkId in linkIds) {
                            linkIdToEdge[linkId]?.attrs?.forEach { (name, value) ->
                                add(buildJsonObject {
                                    put("id", linkId * 10)
                                    put("idLink", linkId)
                                    put("attrId", 1)
                                    put("attrName", name)
                                    put("attrType", 0)
                                    put("isSystem", 0)
                                    put("value", value)
                                })
                            }
                        }
                    }
                }
                else -> error("Unexpected request ${request.url}")
            }
            if (tracked) inFlight.decrementAndGet()
            respond(
                content = body.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
        return LoodsmanApi(config, HttpClient(engine) { installLoodsman() })
    }

    private fun propOf(obj: Obj) = buildJsonObject {
        put("idVersion", obj.id)
        put("type", obj.type)
        put("document", 0)
        put("product", obj.product)
        put("version", "1")
        put("state", "Утверждён")
        put("locked", 0)
        put("accessLevel", 0)
        put("label", 0)
        put("hasLink", 0)
        put("mainId", obj.id)
    }

    private fun linkOf(linkId: Int, parent: Int, child: Int, linkName: String) = buildJsonObject {
        put("idLink", linkId)
        put("idParent", parent)
        put("idChild", child)
        put("idLinkType", linkTypeIdOf(linkName))
        put("linkTypeName", linkName)
        put("idType", 0)
        put("idState", 0)
        val edge = linkIdToEdge.getValue(linkId)
        edge.min?.let { put("minQuantity", it) }
        edge.max?.let { put("maxQuantity", it) }
        edge.unit?.let { put("unit", it) }
    }
}

internal val assembly = Obj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус"))
internal val shaft = Obj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал", "Позиция" to "1", "Масса" to "1.5"))
internal val bolt = Obj(
    3, "Стандартное изделие", "ГОСТ 7798-70",
    mapOf("Позиция" to "2")
)
internal val pin = Obj(4, "Деталь", "АБ.003", mapOf("Наименование" to "Штифт"))

internal val tree = listOf(assembly, shaft, bolt, pin)

// Сборка 2 со связью состава на штифт 4: для проверки обхода замен, где промежуточный узел раскрывается по составу.
internal val subAssembly = Obj(2, "Сборочная единица", "АБ.002", mapOf("Наименование" to "Узел"))
internal val subAssemblyEdges = listOf(
    Edge(1, 2),
    Edge(1, 3, min = 4.0, max = 6.0),
    Edge(2, 4),
)
internal val treeEdges = listOf(
    Edge(1, 2, min = 2.0, max = 2.0, unit = "шт"),
    Edge(1, 3, min = 4.0, max = 6.0),
    Edge(2, 4)
)

internal fun query(
    depth: Int,
    vararg attributes: AttributeKey,
    relationDepth: Map<Relation, Int> = emptyMap(),
    linkAttributes: Set<LinkAttributeKey> = emptySet(),
    includeUnmapped: Boolean = false
) = StructureQuery(
    rootId = PdmNodeId(1),
    depth = depth,
    relationDepth = relationDepth,
    attributes = attributes.toSet(),
    linkAttributes = linkAttributes,
    includeUnmappedAttributes = includeUnmapped
)

// Строка ответа get-info-about-version-mode-20: член варианта группы замен, связь [idLink] объекта [parent].
private fun replacementRow(
    group: Int,
    groupType: Int,
    variant: Int,
    basic: Boolean,
    idLink: Int,
    groupName: String? = "Замена",
    variantName: String? = "Вариант",
) = buildJsonObject {
    put("idGroupChange", group)
    groupName?.let { put("groupChangeName", it) }
    put("groupChangeType", groupType)
    put("groupInCurrentConf", 1)
    put("idVariant", variant)
    variantName?.let { put("variantName", it) }
    put("basic", if (basic) 1 else 0)
    put("variantInCurrentConf", 1)
    put("idLink", idLink)
    put("idParent", 0)
    put("idVersion", 0)
    put("idType", 0)
    put("idState", 0)
    put("idLock", 0)
    put("accessLevel", 0)
    put("label", 0)
}

// Корень-деталь и технологическая деталь, связанная обратной связью "Технологическая ДСЕ для" (от детали).
private val detail = Obj(10, "Деталь", "АБ.010", mapOf("Наименование" to "Деталь"))
private val technological = Obj(11, "Технологическая деталь", "ТД.011", mapOf("Наименование" to "Технологическая"))
private val technologicalObjects = listOf(detail, technological, pin)
private val technologicalEdges = listOf(
    Edge(parent = 11, child = 10, link = TECHNOLOGICAL_PART),
    Edge(parent = 11, child = 4)
)

// Тип "Технологическая деталь" в маппинге видов; правила связей берутся из loodsman-mapping.yaml.
private fun technologicalMapping(): MappingConfig = MappingConfigLoader.load(emptyMap()).let { base ->
    base.copy(kinds = base.kinds + ("Технологическая деталь" to "PART"))
}

private fun technologicalFake() = FakeLoodsman(
    technologicalObjects,
    technologicalEdges,
    linkNames = listOf(COMPOSITION, "Документы", TECHNOLOGICAL_PART),
)

private fun technologicalQuery(depth: Int) = StructureQuery(
    rootId = PdmNodeId(10),
    depth = depth,
    relations = setOf(Relation.Composition, Relation.TechnologicalPart),
)

private fun replacementQuery(depth: Int, replacementDepth: Int, replacements: Boolean = true) = StructureQuery(
    rootId = PdmNodeId(1),
    depth = depth,
    replacements = replacements,
    replacementDepth = replacementDepth,
)

class LoodsmanProductStructureClientTest : ShouldSpec({
    should("fail on start when metadata cannot be loaded") {
        val fake = FakeLoodsman(tree, treeEdges)
        fake.linkListFailures.set(1)

        shouldThrow<LoodsmanApiException> { fake.client() }
    }

    should("read metadata snapshot once per load even when the source changes during traversal") {
        val fake = FakeLoodsman(tree, treeEdges)
        val reads = AtomicInteger(0)
        // Первое чтение отдаёт рабочий снимок, последующие — пустой: обход не должен перечитывать метаданные.
        val metadata = MetadataSource {
            if (reads.incrementAndGet() == 1) fake.snapshot() else fake.snapshot(version = 2).copy(linkTypes = emptyList())
        }

        val root = fake.client(metadata = metadata).loadTree(query(1))

        root.usages.map { it.target.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
        reads.get() shouldBe 1
    }

    should("load only the root and skip link requests when depth is 0") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadTree(query(0))

        root.id shouldBe PdmNodeId(1)
        root.kind shouldBe PdmNodeKind.Assembly
        root.usages shouldBe emptyList()
        fake.linkCalls shouldBe emptyList()
        fake.propsCalls shouldBe listOf(listOf(1))
    }

    should("map designation, name, quantity, unit and position of direct children when depth is 1") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(1, AttributeKey.Designation, AttributeKey.Name))

        root.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.001")
        root.attributes[AttributeKey.Name] shouldBe AttributeValue.Text("Корпус")
        root.usages.map { it.target.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
        root.usages.all { it.relation == Relation.Composition } shouldBe true

        val compositions = root.usages.filterIsInstance<CompositionUsage>()
        compositions.size shouldBe 2

        val shaftUsage = compositions[0]
        shaftUsage.target.kind shouldBe PdmNodeKind.Part
        shaftUsage.target.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.002")
        shaftUsage.target.attributes[AttributeKey.Name] shouldBe AttributeValue.Text("Вал")
        shaftUsage.quantity shouldBe Quantity.Exact(2.0)
        shaftUsage.unit shouldBe Field.Value("шт")
        shaftUsage.position shouldBe Field.Missing
        shaftUsage.target.usages shouldBe emptyList()

        val boltUsage = compositions[1]
        boltUsage.target.kind shouldBe PdmNodeKind.Standard
        boltUsage.target.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("ГОСТ 7798-70")
        boltUsage.target.attributes[AttributeKey.Name] shouldBe AttributeValue.Empty
        boltUsage.quantity shouldBe Quantity.Range(4.0, 6.0)
        boltUsage.unit shouldBe Field.Missing
        boltUsage.position shouldBe Field.Missing
    }

    should("return quantity as missing when the link has no quantity") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(2))

        val composition = root.usages[0].target.usages.single() as CompositionUsage
        composition.quantity shouldBe Quantity.Missing
    }

    should("map equal min and max to exact quantity") {
        val fake = FakeLoodsman(tree, listOf(Edge(1, 2, min = 3.0, max = 3.0)))

        val composition = fake.client().loadTree(query(1)).usages.single() as CompositionUsage
        composition.quantity shouldBe Quantity.Exact(3.0)
    }

    should("map different min and max to quantity range") {
        val fake = FakeLoodsman(tree, listOf(Edge(1, 2, min = 1.0, max = 5.0)))

        val composition = fake.client().loadTree(query(1)).usages.single() as CompositionUsage
        composition.quantity shouldBe Quantity.Range(1.0, 5.0)
    }

    should("reject a quantity range with only min filled") {
        val fake = FakeLoodsman(tree, listOf(Edge(1, 2, min = 2.0)))

        val error = shouldThrow<LoodsmanApiException> { fake.client().loadTree(query(1)) }
        error.message shouldBe "Link 100: quantity range is half-filled (min=2.0, max=null)"
    }

    should("reject a quantity range with only max filled") {
        val fake = FakeLoodsman(tree, listOf(Edge(1, 2, max = 2.0)))

        val error = shouldThrow<LoodsmanApiException> { fake.client().loadTree(query(1)) }
        error.message shouldBe "Link 100: quantity range is half-filled (min=null, max=2.0)"
    }

    should("load grandchildren when depth is 2 and request properties once per level") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadTree(query(2))

        root.usages[0].target.usages.single().target.id shouldBe PdmNodeId(4)
        fake.propsCalls.size shouldBe 3
        fake.propsCalls[1].toSet() shouldBe setOf(2, 3)
        fake.propsCalls[2] shouldBe listOf(4)
    }

    should("cut composition by relationDepth and keep loading documents deeper") {
        // Спецификация подключена документом к валу (уровень 2) и к штифту (уровень 3, за отсечкой состава).
        val spec = Obj(5, "Спецификация", "АБ.005", mapOf("Наименование" to "Спецификация"))
        val edges = listOf(
            Edge(1, 2, min = 2.0, max = 2.0, unit = "шт"),
            Edge(2, 4),
            Edge(2, 5, link = "Документы"),
            Edge(4, 5, link = "Документы"),
        )
        val fake = FakeLoodsman(tree + spec, edges)

        val root = fake.client().loadTree(
            StructureQuery(
                rootId = PdmNodeId(1),
                depth = 3,
                relations = setOf(Relation.Composition, Relation.Document),
                relationDepth = mapOf(Relation.Composition to 1),
            )
        )

        val shaftUsage = root.usages.single() as CompositionUsage
        shaftUsage.target.id shouldBe PdmNodeId(2)

        val specUsage = shaftUsage.target.usages.single()
        specUsage.relation shouldBe Relation.Document
        specUsage.target.id shouldBe PdmNodeId(5)
        specUsage.target.usages shouldBe emptyList()

        // Штифт (состав уровня 2) не запрошен вовсе; у вала и спецификации запрошены только документы.
        fake.propsCalls shouldBe listOf(listOf(1), listOf(2), listOf(5))
        fake.linkRequests.map { it.linkTypeIds } shouldBe listOf(listOf(1, 2), listOf(2), listOf(2))
    }

    should("cap relationDepth by the query depth") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadTree(query(1, relationDepth = mapOf(Relation.Composition to 5)))

        root.usages.map { it.target.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
        root.usages[0].target.usages shouldBe emptyList()
        fake.propsCalls shouldBe listOf(listOf(1), listOf(2, 3))
    }

    should("reject a relationDepth key outside the query relations") {
        val fake = FakeLoodsman(tree, treeEdges)
        val q = StructureQuery(
            rootId = PdmNodeId(1),
            depth = 1,
            relations = setOf(Relation.Composition),
            relationDepth = mapOf(Relation.Document to 1),
        )

        val error = shouldThrow<LoodsmanApiException> { fake.client().loadTree(q) }
        error.message shouldBe "relationDepth contains 'Document' which is not in the query relations"
    }

    should("reject a relationDepth below 1") {
        val fake = FakeLoodsman(tree, treeEdges)
        val q = StructureQuery(
            rootId = PdmNodeId(1),
            depth = 1,
            relationDepth = mapOf(Relation.Composition to 0),
        )

        val error = shouldThrow<LoodsmanApiException> { fake.client().loadTree(q) }
        error.message shouldBe "relationDepth for 'Composition' must be at least 1, got 0"
    }

    should("return empty attributes when not requested") {
        val fake = FakeLoodsman(tree, treeEdges)

        val without = fake.client().loadTree(query(1))

        without.attributes shouldBe emptyMap()
    }

    should("fail on a cycle") {
        val cyclic = treeEdges + Edge(4, 1)

        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, cyclic).client().loadTree(query(Int.MAX_VALUE))
        }
    }

    should("fail when a required link type is absent in metadata") {
        val fake = FakeLoodsman(tree, treeEdges, linkNames = listOf("Документы"))

        shouldThrow<LoodsmanApiException> { fake.client().loadTree(query(1)) }
    }

    should("fail when the root object is not found") {
        val fake = FakeLoodsman(emptyList(), emptyList())

        shouldThrow<LoodsmanApiException> { fake.client().loadTree(query(0)) }
    }

    should("keep an object of unknown type as unmapped by default") {
        val unknown = Obj(5, "Неведомый тип", "XX.001")
        val fake = FakeLoodsman(tree + unknown, treeEdges + Edge(1, 5))

        val root = fake.client().loadTree(query(1))

        root.usages.last().target.kind shouldBe PdmNodeKind.Unmapped("Неведомый тип")
    }

    should("skip an object of unknown type when the policy is SKIP") {
        val unknown = Obj(5, "Неведомый тип", "XX.001")
        val fake = FakeLoodsman(tree + unknown, treeEdges + Edge(1, 5))
        val mapping = MappingConfigLoader.load(emptyMap())
            .copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.SKIP))

        val root = fake.client(mapping).loadTree(query(1))

        root.usages.map { it.target.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
    }

    should("fill version and state from object properties") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(1))

        root.version shouldBe Field.Value("1")
        root.state shouldBe Field.Value("Утверждён")
        root.usages[0].target.version shouldBe Field.Value("1")
        root.usages[0].target.state shouldBe Field.Value("Утверждён")
    }

    should("load position from link attribute when rule exists") {
        val edgeWithPosition = Edge(1, 2, min = 2.0, max = 2.0, unit = "шт", attrs = mapOf("Позиция" to "3"))
        val edges = listOf(edgeWithPosition) + treeEdges.drop(1)
        val fake = FakeLoodsman(tree, edges)

        val root = fake.client().loadTree(query(1))

        val composition = root.usages[0] as CompositionUsage
        composition.position shouldBe Field.Value(3)
    }

    should("load zero link attributes when no position rules and no link attributes requested") {
        val fake = FakeLoodsman(tree, treeEdges)
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            link = LinkMapping()
        )

        fake.client(mapping).loadTree(query(1))

        fake.linkAttributeCalls.shouldBe(emptyList())
    }

    should("load link attributes and populate Usage.attributes") {
        val edgeWithNote = Edge(1, 2, min = 2.0, max = 2.0, unit = "шт", attrs = mapOf("Примечание" to "тест"))
        val edges = listOf(edgeWithNote) + treeEdges.drop(1)
        val fake = FakeLoodsman(tree, edges)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages[0].attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Text("тест"))
    }

    should("return Empty for requested linkAttribute without value") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages[0].attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Empty)
    }

    should("fail when requested link attribute not in mapping") {
        val fake = FakeLoodsman(tree, treeEdges)
        val mapping = MappingConfigLoader.load(emptyMap()).copy(link = LinkMapping())

        shouldThrow<LoodsmanApiException> {
            fake.client(mapping).loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))
        }
    }

    should("not call get-original-link-attributes when depth is 0") {
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client().loadTree(query(0))

        fake.linkAttributeCalls.shouldBe(emptyList())
        fake.linkCalls.shouldBe(emptyList())
    }

    should("fill requested node attributes and mark absent ones as empty") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(1, AttributeKey.Mass))

        root.attributes shouldBe mapOf(AttributeKey.Mass to AttributeValue.Empty)
        root.usages[0].target.attributes shouldBe mapOf(AttributeKey.Mass to AttributeValue.Text("1.5"))
    }

    should("not call the object attribute list when no attributes are requested") {
        val fake = FakeLoodsman(tree, treeEdges)
        val client = fake.client()

        client.loadTree(query(1))
        client.loadNode(PdmNodeId(2), emptySet())

        fake.infoCalls shouldBe emptyList()
    }

    should("not call the object attribute list when only the key-attribute designation is requested") {
        val fake = FakeLoodsman(tree, treeEdges)
        val client = fake.client()

        val root = client.loadTree(query(1, AttributeKey.Designation))
        val shaft = client.loadNode(PdmNodeId(2), setOf(AttributeKey.Designation))

        fake.infoCalls shouldBe emptyList()
        root.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.001")
        shaft.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.002")
    }

    should("call the object attribute list for a non-key attribute only") {
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client().loadNode(PdmNodeId(2), setOf(AttributeKey.Name))

        fake.infoCalls shouldBe listOf(2)
    }

    should("use the attribute name overridden for the object kind") {
        val standard = bolt.copy(attrs = bolt.attrs + mapOf("Масса" to "9", "Масса изделия" to "0.02"))
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            attributes = mapOf("mass" to AttributeRule(name = "Масса", byKind = mapOf("STANDARD" to "Масса изделия")))
        )

        val root = FakeLoodsman(listOf(assembly, shaft, standard, pin), treeEdges).client(mapping)
            .loadTree(query(1, AttributeKey.Mass))

        root.usages[0].target.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
        root.usages[1].target.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("0.02")
    }

    should("read an attribute defined only for some kinds") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            attributes = mapOf("mass" to AttributeRule(byKind = mapOf("PART" to "Масса")))
        )

        val root = FakeLoodsman(tree, treeEdges).client(mapping).loadTree(query(1, AttributeKey.Mass))

        root.usages[0].target.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
        root.usages[1].target.attributes[AttributeKey.Mass] shouldBe AttributeValue.Empty
        root.attributes[AttributeKey.Mass] shouldBe AttributeValue.Empty
    }

    should("fail when a requested node attribute is absent in the mapping") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(attributes = emptyMap())

        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, treeEdges).client(mapping).loadTree(query(1, AttributeKey.Mass))
        }
    }

    should("expose only attributes unknown to the mapping when unmapped ones are requested") {
        val extra = shaft.copy(attrs = shaft.attrs + ("Прочее" to "x"))

        val root = FakeLoodsman(listOf(assembly, extra, bolt, pin), treeEdges).client()
            .loadTree(query(1, includeUnmapped = true))

        val attributes = root.usages[0].target.attributes
        attributes[AttributeKey.Unmapped("Прочее")] shouldBe AttributeValue.Text("x")
        attributes.containsKey(AttributeKey.Unmapped("Масса")) shouldBe false
        attributes.containsKey(AttributeKey.Unmapped("Наименование")) shouldBe false
    }

    should("choose the position rule by the kinds of the linked objects") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            link = LinkMapping(
                attributes = mapOf(
                    "position" to listOf(
                        LinkAttributeRule(relation = "COMPOSITION", name = "Позиция"),
                        LinkAttributeRule(relation = "COMPOSITION", child = setOf("STANDARD"), name = "Позиция изделия")
                    )
                )
            )
        )
        val edges = listOf(
            Edge(1, 2, attrs = mapOf("Позиция" to "1")),
            Edge(1, 3, attrs = mapOf("Позиция" to "7", "Позиция изделия" to "2"))
        )

        val root = FakeLoodsman(tree, edges).client(mapping).loadTree(query(1))

        val compositions = root.usages.filterIsInstance<CompositionUsage>()
        compositions[0].position shouldBe Field.Value(1)
        compositions[1].position shouldBe Field.Value(2)
    }

    should("return missing position when the link has no position attribute") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(1))

        root.usages.filterIsInstance<CompositionUsage>().map { it.position } shouldBe listOf(Field.Missing, Field.Missing)
    }

    should("reject the built-in position key in the link attributes query") {
        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, treeEdges).client()
                .loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Position)))
        }
    }

    should("not duplicate the position in the link attributes of a usage") {
        val edges = listOf(Edge(1, 2, attrs = mapOf("Позиция" to "3", "Примечание" to "n")))

        val root = FakeLoodsman(tree, edges).client()
            .loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        val usage = root.usages.single() as CompositionUsage
        usage.position shouldBe Field.Value(3)
        usage.attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Text("n"))
    }

    should("request link attributes only for links that have a matching rule") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            link = LinkMapping(
                attributes = mapOf(
                    "position" to listOf(
                        LinkAttributeRule(relation = "COMPOSITION", child = setOf("STANDARD"), name = "Позиция")
                    )
                )
            )
        )
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client(mapping).loadTree(query(1))

        fake.linkAttributeCalls.size shouldBe 1
    }

    should("read the same key, designation and name in loadTree and loadNode") {
        val fake = FakeLoodsman(tree, treeEdges)
        val client = fake.client()

        val requested = setOf(AttributeKey.Mass, AttributeKey.Designation, AttributeKey.Name)
        val structure = client.loadTree(query(1, *requested.toTypedArray(), includeUnmapped = true))
        val shaftFromStructure = structure.usages[0].target
        val boltFromStructure = structure.usages[1].target
        val shaftFromLoadNode = client.loadNode(PdmNodeId(2), requested, includeUnmapped = true)
        val boltFromLoadNode = client.loadNode(PdmNodeId(3), requested, includeUnmapped = true)

        shaftFromLoadNode.key shouldBe shaftFromStructure.key
        shaftFromLoadNode.attributes shouldBe shaftFromStructure.attributes
        boltFromLoadNode.key shouldBe boltFromStructure.key
        boltFromLoadNode.attributes shouldBe boltFromStructure.attributes
    }

    should("return NodeKey.Missing and empty designation for an object with empty product") {
        val empty = Obj(6, "Деталь", "", mapOf("Наименование" to "Пустая"))
        val fake = FakeLoodsman(listOf(assembly, empty), listOf(Edge(1, 6)))

        val structure = fake.client().loadTree(query(1, AttributeKey.Designation))
        val nodeById = fake.client().loadNode(PdmNodeId(6), setOf(AttributeKey.Designation))

        structure.usages.single().target.key shouldBe NodeKey.Missing
        structure.usages.single().target.attributes[AttributeKey.Designation] shouldBe AttributeValue.Empty
        nodeById.key shouldBe NodeKey.Missing
        nodeById.attributes[AttributeKey.Designation] shouldBe AttributeValue.Empty
    }

    should("split properties request of one level into batches of at most 100 objects") {
        listOf(101, 250).forEach { count ->
            val children = (2..count + 1).map { Obj(it, "Деталь", "АБ.%03d".format(it)) }
            val fake = FakeLoodsman(listOf(assembly) + children, children.map { Edge(1, it.id) })

            val root = fake.client().loadTree(query(1))

            val batches = fake.propsCalls.drop(1)
            batches.size shouldBe (count + PROPS_BATCH - 1) / PROPS_BATCH
            batches.all { it.size <= PROPS_BATCH } shouldBe true
            batches.flatten() shouldBe children.map { it.id }
            root.usages.map { it.target.id } shouldBe children.map { PdmNodeId(it.id) }
        }
    }

    should("never exceed the configured concurrency for parallel requests") {
        val children = (2..11).map { Obj(it, "Деталь", "АБ.%03d".format(it), mapOf("Наименование" to "Д$it")) }
        val edges = children.map { Edge(1, it.id, attrs = mapOf("Примечание" to "n")) }

        for (concurrency in listOf(1, 3)) {
            val fake = FakeLoodsman(listOf(assembly) + children, edges, requestDelay = 20.milliseconds)

            fake.client(concurrency = concurrency)
                .loadTree(query(1, AttributeKey.Name, linkAttributes = setOf(LinkAttributeKey.Note)))

            fake.maxInFlight.get().shouldBeLessThanOrEqualTo(concurrency)
            if (concurrency > 1) fake.maxInFlight.get().shouldBeGreaterThan(1)
        }
    }

    should("read link attributes of 250 links in batches of 30 with mode 1 and without attrIds when no name is in the snapshot") {
        val children = (2..251).map { Obj(it, "Деталь", "АБ.%03d".format(it), mapOf("Наименование" to "Д$it")) }
        val edges = children.map { Edge(1, it.id, attrs = mapOf("Примечание" to "n${it.id}")) }
        val fake = FakeLoodsman(listOf(assembly) + children, edges)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        fake.linkAttributeBatches.size shouldBe 9
        fake.linkAttributeBatches.map { it.size }.sortedDescending() shouldBe List(8) { 30 } + 10
        // Связи фейка: родитель 1 даёт linkId 100..349 (parent * 100 + порядок).
        fake.linkAttributeBatches.flatten().sorted() shouldBe (100 until 350).toList()
        fake.linkAttributeParams.all { it["mode"] == "1" && "attrIds" !in it } shouldBe true
        root.usages.map { (it as CompositionUsage).attributes } shouldBe children.map {
            mapOf(LinkAttributeKey.Note to AttributeValue.Text("n${it.id}"))
        }
    }

    should("split 65 links into batches of 30, 30 and 5") {
        val children = (2..66).map { Obj(it, "Деталь", "АБ.%03d".format(it)) }
        val edges = children.map { Edge(1, it.id, attrs = mapOf("Примечание" to "n${it.id}")) }
        val fake = FakeLoodsman(listOf(assembly) + children, edges)

        fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        fake.linkAttributeBatches.map { it.size }.sortedDescending() shouldBe listOf(30, 30, 5)
        fake.linkAttributeBatches.flatten().sorted() shouldBe (100 until 165).toList()
    }

    should("pass the ids of the position and requested attributes as attrIds in every batch") {
        val children = (2..66).map { Obj(it, "Деталь", "АБ.%03d".format(it)) }
        val edges = children.map { Edge(1, it.id, attrs = mapOf("Примечание" to "n${it.id}")) }
        // Зоны в справочнике нет: её id не находится и в фильтр не попадает. Масса не запрошена.
        val attributes = listOf(AttributeDef(11, "Позиция"), AttributeDef(12, "Примечание"), AttributeDef(13, "Масса"))
        val fake = FakeLoodsman(listOf(assembly) + children, edges, attributes = attributes)

        fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        fake.linkAttributeParams.size shouldBe 3
        fake.linkAttributeParams.map { it["attrIds"] }.distinct() shouldBe listOf("11,12")
        fake.linkAttributeParams.all { it["mode"] == "1" } shouldBe true
    }

    should("pass every id of a name shared by attributes of different kinds") {
        val children = (2..4).map { Obj(it, "Деталь", "АБ.%03d".format(it)) }
        val edges = children.map { Edge(1, it.id, attrs = mapOf("Примечание" to "n${it.id}")) }
        val attributes = listOf(AttributeDef(11, "Позиция"), AttributeDef(12, "Примечание"), AttributeDef(21, "Примечание"))
        val fake = FakeLoodsman(listOf(assembly) + children, edges, attributes = attributes)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        fake.linkAttributeParams.map { it["attrIds"] }.distinct() shouldBe listOf("11,12,21")
        root.usages.map { it.attributes[LinkAttributeKey.Note] } shouldBe children.map {
            AttributeValue.Text("n${it.id}")
        }
    }

    should("read without attrIds when none of the required names is found in the snapshot") {
        val children = (2..4).map { Obj(it, "Деталь", "АБ.%03d".format(it)) }
        val edges = children.map { Edge(1, it.id, attrs = mapOf("Примечание" to "n${it.id}")) }
        val attributes = listOf(AttributeDef(13, "Масса"))
        val fake = FakeLoodsman(listOf(assembly) + children, edges, attributes = attributes)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        fake.linkAttributeParams.isNotEmpty() shouldBe true
        fake.linkAttributeParams.all { "attrIds" !in it } shouldBe true
        root.usages.map { it.attributes[LinkAttributeKey.Note] } shouldBe children.map {
            AttributeValue.Text("n${it.id}")
        }
    }

    should("keep only the requested attribute and the position when the server returns other attributes of the link") {
        val edges = listOf(Edge(1, 2, attrs = mapOf("Позиция" to "3", "Примечание" to "n", "Масса" to "1.5")))
        val attributes = listOf(AttributeDef(11, "Позиция"), AttributeDef(12, "Примечание"))
        // Фейк отдаёт все атрибуты связи и игнорирует attrIds: отбор по attrName остаётся на клиенте.
        val fake = FakeLoodsman(listOf(assembly, shaft), edges, attributes = attributes)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        val usage = root.usages.single() as CompositionUsage
        usage.position shouldBe Field.Value(3)
        usage.attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Text("n"))
        fake.linkAttributeParams.single()["attrIds"] shouldBe "11,12"
    }

    should("map each batch row to the usage of its own link id in the order of the tree") {
        val children = listOf(
            Obj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
            Obj(3, "Деталь", "АБ.003", mapOf("Наименование" to "Ось")),
            Obj(4, "Деталь", "АБ.004", mapOf("Наименование" to "Палец")),
        )
        val edges = listOf(
            Edge(1, 2, attrs = mapOf("Примечание" to "первое")),
            Edge(1, 3),
            Edge(1, 4, attrs = mapOf("Примечание" to "третье")),
        )
        val fake = FakeLoodsman(listOf(assembly) + children, edges)

        val root = fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages.map { it.target.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3), PdmNodeId(4))
        root.usages.map { it.attributes[LinkAttributeKey.Note] } shouldBe listOf(
            AttributeValue.Text("первое"),
            AttributeValue.Empty,
            AttributeValue.Text("третье"),
        )
        fake.linkAttributeBatches.size shouldBe 1
        fake.linkAttributeBatches.single().sorted() shouldBe listOf(100, 101, 102)
    }

    should("fail the load when a batch of link attributes fails") {
        val fake = FakeLoodsman(tree, treeEdges)
        fake.linkAttributeFailures.set(1)

        shouldThrow<LoodsmanApiException> {
            fake.client().loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))
        }
    }

    should("read only the position when no link attributes are requested") {
        val edges = listOf(Edge(1, 2, attrs = mapOf("Позиция" to "3", "Примечание" to "n")))
        val fake = FakeLoodsman(tree, edges)

        val root = fake.client().loadTree(query(1))

        val usage = root.usages.single() as CompositionUsage
        usage.position shouldBe Field.Value(3)
        usage.attributes shouldBe emptyMap()
        fake.linkAttributeCalls shouldBe listOf(100)
    }

    should("not request link attributes for a link to a skipped object") {
        val unknown = Obj(5, "Неведомый тип", "XX.001")
        val edges = treeEdges.take(2) + Edge(1, 5, attrs = mapOf("Примечание" to "n"))
        val fake = FakeLoodsman(tree + unknown, edges)
        val mapping = MappingConfigLoader.load(emptyMap())
            .copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.SKIP))

        val root = fake.client(mapping).loadTree(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages.map { it.target.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
        fake.linkAttributeCalls.sorted() shouldBe listOf(100, 101)
    }

    should("load a technological part by the inverse rule with the id of the rule link") {
        val fake = technologicalFake()

        val root = fake.client(technologicalMapping()).loadTree(technologicalQuery(1))

        val usage = root.usages.single() as TechnologicalPartUsage
        usage.target.id shouldBe PdmNodeId(11)
        usage.target.kind shouldBe PdmNodeKind.Part
        fake.linkRequests.filter { it.inverse } shouldBe listOf(LinkRequest(objectId = 10, linkTypeIds = listOf(3), inverse = true))
    }

    should("not apply the inverse rule of the root kind to a technological part and expand its composition") {
        val fake = technologicalFake()

        val root = fake.client(technologicalMapping()).loadTree(technologicalQuery(2))

        val technologicalPart = (root.usages.single() as TechnologicalPartUsage).target
        technologicalPart.usages.single().target.id shouldBe PdmNodeId(4)
        fake.linkRequests.filter { it.objectId == 11 } shouldBe
            listOf(LinkRequest(objectId = 11, linkTypeIds = listOf(1), inverse = false))
        fake.linkRequests.filter { it.inverse } shouldBe listOf(LinkRequest(objectId = 10, linkTypeIds = listOf(3), inverse = true))
    }

    should("not request inverse links when the relation of the inverse rule is not queried") {
        val fake = technologicalFake()

        val root = fake.client(technologicalMapping()).loadTree(StructureQuery(rootId = PdmNodeId(10), depth = 1))

        root.usages shouldBe emptyList()
        fake.linkRequests.none { it.inverse } shouldBe true
    }

    should("request only forward links for rules without from") {
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client().loadTree(query(2))

        fake.linkRequests.isNotEmpty() shouldBe true
        fake.linkRequests.map { it.inverse }.distinct() shouldBe listOf(false)
        fake.linkRequests.map { it.linkTypeIds }.distinct() shouldBe listOf(listOf(1))
    }
    should("not request replacements and leave them empty when the replacements flag is off") {
        val rows = mapOf(1 to listOf(replacementRow(240, 2, 271, true, 100)))
        val fake = FakeLoodsman(tree, treeEdges, replacementRows = rows)

        val root = fake.client().loadTree(replacementQuery(depth = 2, replacementDepth = 0, replacements = false))

        fake.replacementCalls shouldBe emptyList()
        root.replacements shouldBe emptyList()
        root.usages.forEach { (it as CompositionUsage).target.replacements shouldBe emptyList() }
    }

    should("request replacements only for the root when replacementDepth is 0") {
        val rows = mapOf(1 to listOf(replacementRow(240, 2, 271, true, 100)))
        val fake = FakeLoodsman(tree, treeEdges, replacementRows = rows)

        val root = fake.client().loadTree(replacementQuery(depth = 2, replacementDepth = 0))

        fake.replacementCalls shouldBe listOf(1)
        root.replacements.single().id shouldBe 240
    }

    should("request replacements for the root and its composition children with composition links when replacementDepth is 1") {
        // Узел 2 — сборка со связью состава (штифт 4); болт (3) без связей состава.
        val fake = FakeLoodsman(listOf(assembly, subAssembly, bolt, pin), subAssemblyEdges)

        fake.client().loadTree(replacementQuery(depth = 3, replacementDepth = 1))

        // Штифт (4) на уровне 2 — за пределом replacementDepth.
        fake.replacementCalls.sorted() shouldBe listOf(1, 2)
    }

    should("not request replacements for documents or for nodes without composition links when replacementDepth is 1") {
        val spec = Obj(5, "Спецификация", "АБ.005", mapOf("Наименование" to "Спецификация"))
        val fake = FakeLoodsman(listOf(assembly, subAssembly, bolt, pin, spec), subAssemblyEdges + Edge(1, 5, link = "Документы"))

        val root = fake.client().loadTree(
            StructureQuery(
                rootId = PdmNodeId(1),
                depth = 3,
                relations = setOf(Relation.Composition, Relation.Document),
                replacements = true,
                replacementDepth = 1,
            )
        )

        // Спецификация (5) — документ корня: в обход замен не входит. Болт (3) — без связей состава.
        fake.replacementCalls.sorted() shouldBe listOf(1, 2)
        root.usages.filter { it.relation == Relation.Document }.single().target.id shouldBe PdmNodeId(5)
    }

    should("request replacements of the root only when the root is the only node with composition links") {
        val fake = FakeLoodsman(
            listOf(assembly, bolt),
            listOf(Edge(1, 3, min = 4.0, max = 6.0)),
        )

        fake.client().loadTree(replacementQuery(depth = 3, replacementDepth = 2))

        // Болт (3) — без связей состава, его замены не запрашиваются.
        fake.replacementCalls shouldBe listOf(1)
    }

    should("request replacements only up to depth - 1 even when replacementDepth is larger") {
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client().loadTree(replacementQuery(depth = 1, replacementDepth = 5))

        fake.replacementCalls shouldBe listOf(1)
    }

    should("attach replacements of a child node to its composition usages and keep all members of the groups") {
        // Композиция раскрывается только из сборочной единицы: промежуточный узел 2 — сборка с состав 4.
        val subAssembly = Obj(2, "Сборочная единица", "АБ.002", mapOf("Наименование" to "Узел"))
        val fake = FakeLoodsman(
            listOf(assembly, subAssembly, pin),
            listOf(Edge(1, 2), Edge(2, 4)),
            replacementRows = mapOf(
                1 to listOf(
                    replacementRow(240, 2, 271, true, 100, groupName = "Замена А", variantName = "Основной"),
                    replacementRow(240, 2, 272, false, 555, groupName = "Замена А", variantName = "Доп"),
                    // Группа без членов в дереве не попадает в результат.
                    replacementRow(239, 0, 269, true, 777),
                ),
                2 to listOf(
                    replacementRow(241, 1, 273, true, 200, groupName = "Замена Б"),
                    replacementRow(241, 1, 274, false, 201, groupName = "Замена Б"),
                ),
            ),
        )

        val root = fake.client().loadTree(replacementQuery(depth = 3, replacementDepth = 1))

        root.replacements shouldBe listOf(
            ReplacementGroup(
                id = 240,
                name = "Замена А",
                type = ReplacementType.Permissible,
                variants = listOf(
                    ReplacementVariant(271, "Основной", basic = true, linkIds = listOf(100), inCurrentConfiguration = true),
                    ReplacementVariant(272, "Доп", basic = false, linkIds = listOf(555), inCurrentConfiguration = true),
                ),
                inCurrentConfiguration = true,
            ),
            ReplacementGroup(
                id = 239,
                name = "Замена",
                type = ReplacementType.Consumer,
                variants = listOf(
                    ReplacementVariant(269, "Вариант", basic = true, linkIds = listOf(777), inCurrentConfiguration = true),
                ),
                inCurrentConfiguration = true,
            ),
        )
        val subUsage = root.usages.single() as CompositionUsage
        subUsage.linkId shouldBe 100
        subUsage.target.replacements shouldBe listOf(
            ReplacementGroup(
                id = 241,
                name = "Замена Б",
                type = ReplacementType.Production,
                variants = listOf(
                    ReplacementVariant(273, "Вариант", basic = true, linkIds = listOf(200), inCurrentConfiguration = true),
                    ReplacementVariant(274, "Вариант", basic = false, linkIds = listOf(201), inCurrentConfiguration = true),
                ),
                inCurrentConfiguration = true,
            ),
        )
        fake.replacementCalls.sorted() shouldBe listOf(1, 2)
    }

    should("set linkId on composition usages even when replacements are not requested") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadTree(query(1))

        root.usages.map { (it as CompositionUsage).linkId } shouldBe listOf(100, 101)
        fake.replacementCalls shouldBe emptyList()
    }

    should("reject a negative replacementDepth and a replacementDepth without the flag") {
        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, treeEdges).client().loadTree(replacementQuery(depth = 2, replacementDepth = -1))
        }
        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, treeEdges).client().loadTree(replacementQuery(depth = 2, replacementDepth = 1, replacements = false))
        }
    }
})
