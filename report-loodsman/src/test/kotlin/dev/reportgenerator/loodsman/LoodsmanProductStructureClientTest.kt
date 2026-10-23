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
import dev.reportgenerator.domain.Relation
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

private const val COMPOSITION = "Состоит из ..."

// Размер пачки получения свойств объектов, как в клиенте.
private const val PROPS_BATCH = 100

// Ключевой атрибут всех видов в фейке: его значение лежит в product объекта.
private const val KEY_ATTRIBUTE = "Обозначение"

private data class Obj(
    val id: Int,
    val type: String,
    val product: String,
    val attrs: Map<String, String> = emptyMap()
)

private data class Edge(
    val parent: Int,
    val child: Int,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
    val attrs: Map<String, String> = emptyMap()
)

// Запросы, которые клиент распараллеливает через семафор: по ним считается число одновременных запросов.
private val CONCURRENT_PATHS = setOf(
    "/api/v4/ObjectInfo/get-linked-objects-for-objects",
    "/api/v4/ObjectInfo/get-link-attributes",
    "/api/v4/ObjectInfo/get-info-about-version-mode-3",
)

private class FakeLoodsman(
    private val objects: List<Obj>,
    private val edges: List<Edge>,
    private val linkNames: List<String> = listOf(COMPOSITION, "Документы"),
    private val requestDelay: Duration = Duration.ZERO,
) {
    val propsCalls: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf())
    val linkCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val linkAttributeCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val infoCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val linkListFailures = AtomicInteger(0)
    val maxInFlight = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)
    private val linkIdToEdge = ConcurrentHashMap<Int, Edge>()

    /** Снимок метаданных, который отдаёт тот же фейк (типы связей по [linkNames], виды по [objects]). */
    fun snapshot(version: Long = 1): MetadataSnapshot = MetadataSnapshot(
        linkTypes = linkNames.mapIndexed { index, name -> LinkTypeInfo(id = index + 1, name = name) },
        objectTypes = objectTypes(),
        attributeNames = emptySet(),
        version = version,
    )

    private fun objectTypes(): List<ObjectTypeInfo> =
        objects.map { it.type }.distinct().mapIndexed { index, type ->
            ObjectTypeInfo(id = index + 1, typeName = type, keyAttribute = KEY_ATTRIBUTE)
        }

    /** Клиент с кэшем метаданных из фейка (загружается при создании) или с заданным [metadata]. */
    suspend fun client(
        mapping: MappingConfig = MappingConfigLoader.load(emptyMap()),
        metadata: MetadataSource? = null,
        concurrency: Int = 8,
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
            val tracked = request.url.encodedPath in CONCURRENT_PATHS
            if (tracked) {
                val now = inFlight.incrementAndGet()
                maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                if (requestDelay > Duration.ZERO) delay(requestDelay)
            }
            val params = request.url.parameters
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
                "/api/v4/MetaData/get-attribute-list-2" -> buildJsonArray {}
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
                    val parent = params["objectsIds"]!!.toInt()
                    linkCalls.add(parent)
                    JsonArray(edges.filter { it.parent == parent }.mapIndexed { index, edge ->
                        val linkId = edge.parent * 100 + index
                        linkIdToEdge[linkId] = edge
                        linkOf(edge, index)
                    })
                }
                "/api/v4/ObjectInfo/get-link-attributes" -> {
                    val linkId = params["linkId"]!!.toInt()
                    linkAttributeCalls.add(linkId)
                    val edge = linkIdToEdge[linkId]
                    buildJsonArray {
                        edge?.attrs?.forEach { (name, value) ->
                            add(buildJsonObject {
                                put("id", 1)
                                put("name", name)
                                put("value", value)
                                put("type", 0)
                                put("accessLevel", 0)
                            })
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

    private fun linkOf(edge: Edge, index: Int) = buildJsonObject {
        put("idLink", edge.parent * 100 + index)
        put("idParent", edge.parent)
        put("idChild", edge.child)
        put("idLinkType", 1)
        put("linkTypeName", COMPOSITION)
        put("idType", 0)
        put("idState", 0)
        edge.min?.let { put("minQuantity", it) }
        edge.max?.let { put("maxQuantity", it) }
        edge.unit?.let { put("unit", it) }
    }
}

private val assembly = Obj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус"))
private val shaft = Obj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал", "Позиция" to "1", "Масса" to "1.5"))
private val bolt = Obj(
    3, "Стандартное изделие", "ГОСТ 7798-70",
    mapOf("Позиция" to "2")
)
private val pin = Obj(4, "Деталь", "АБ.003", mapOf("Наименование" to "Штифт"))

private val tree = listOf(assembly, shaft, bolt, pin)
private val treeEdges = listOf(
    Edge(1, 2, min = 2.0, max = 2.0, unit = "шт"),
    Edge(1, 3, min = 4.0, max = 6.0),
    Edge(2, 4)
)

private fun query(
    depth: Int,
    vararg attributes: AttributeKey,
    linkAttributes: Set<LinkAttributeKey> = emptySet(),
    includeUnmapped: Boolean = false
) = StructureQuery(
    rootId = PdmNodeId(1),
    depth = depth,
    attributes = attributes.toSet(),
    linkAttributes = linkAttributes,
    includeUnmappedAttributes = includeUnmapped
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

        root.usages.map { it.child.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
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
        root.usages.map { it.child.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
        root.usages.all { it.relation == Relation.Composition } shouldBe true

        val compositions = root.usages.filterIsInstance<CompositionUsage>()
        compositions.size shouldBe 2

        val shaftUsage = compositions[0]
        shaftUsage.child.kind shouldBe PdmNodeKind.Part
        shaftUsage.child.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.002")
        shaftUsage.child.attributes[AttributeKey.Name] shouldBe AttributeValue.Text("Вал")
        shaftUsage.quantity shouldBe Quantity.Exact(2.0)
        shaftUsage.unit shouldBe Field.Value("шт")
        shaftUsage.position shouldBe Field.Missing
        shaftUsage.child.usages shouldBe emptyList()

        val boltUsage = compositions[1]
        boltUsage.child.kind shouldBe PdmNodeKind.Standard
        boltUsage.child.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("ГОСТ 7798-70")
        boltUsage.child.attributes[AttributeKey.Name] shouldBe AttributeValue.Empty
        boltUsage.quantity shouldBe Quantity.Range(4.0, 6.0)
        boltUsage.unit shouldBe Field.Missing
        boltUsage.position shouldBe Field.Missing
    }

    should("return quantity as missing when the link has no quantity") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(2))

        val composition = root.usages[0].child.usages.single() as CompositionUsage
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

        root.usages[0].child.usages.single().child.id shouldBe PdmNodeId(4)
        fake.propsCalls.size shouldBe 3
        fake.propsCalls[1].toSet() shouldBe setOf(2, 3)
        fake.propsCalls[2] shouldBe listOf(4)
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

        root.usages.last().child.kind shouldBe PdmNodeKind.Unmapped("Неведомый тип")
    }

    should("skip an object of unknown type when the policy is SKIP") {
        val unknown = Obj(5, "Неведомый тип", "XX.001")
        val fake = FakeLoodsman(tree + unknown, treeEdges + Edge(1, 5))
        val mapping = MappingConfigLoader.load(emptyMap())
            .copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.SKIP))

        val root = fake.client(mapping).loadTree(query(1))

        root.usages.map { it.child.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
    }

    should("fill version and state from object properties") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(1))

        root.version shouldBe Field.Value("1")
        root.state shouldBe Field.Value("Утверждён")
        root.usages[0].child.version shouldBe Field.Value("1")
        root.usages[0].child.state shouldBe Field.Value("Утверждён")
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

    should("not call get-link-attributes when depth is 0") {
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client().loadTree(query(0))

        fake.linkAttributeCalls.shouldBe(emptyList())
        fake.linkCalls.shouldBe(emptyList())
    }

    should("fill requested node attributes and mark absent ones as empty") {
        val root = FakeLoodsman(tree, treeEdges).client().loadTree(query(1, AttributeKey.Mass))

        root.attributes shouldBe mapOf(AttributeKey.Mass to AttributeValue.Empty)
        root.usages[0].child.attributes shouldBe mapOf(AttributeKey.Mass to AttributeValue.Text("1.5"))
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

        root.usages[0].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
        root.usages[1].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("0.02")
    }

    should("read an attribute defined only for some kinds") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            attributes = mapOf("mass" to AttributeRule(byKind = mapOf("PART" to "Масса")))
        )

        val root = FakeLoodsman(tree, treeEdges).client(mapping).loadTree(query(1, AttributeKey.Mass))

        root.usages[0].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
        root.usages[1].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Empty
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

        val attributes = root.usages[0].child.attributes
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
        val shaftFromStructure = structure.usages[0].child
        val boltFromStructure = structure.usages[1].child
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

        structure.usages.single().child.key shouldBe NodeKey.Missing
        structure.usages.single().child.attributes[AttributeKey.Designation] shouldBe AttributeValue.Empty
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
            root.usages.map { it.child.id } shouldBe children.map { PdmNodeId(it.id) }
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

        root.usages.map { it.child.id } shouldBe listOf(PdmNodeId(2), PdmNodeId(3))
        fake.linkAttributeCalls.sorted() shouldBe listOf(100, 101)
    }
})
