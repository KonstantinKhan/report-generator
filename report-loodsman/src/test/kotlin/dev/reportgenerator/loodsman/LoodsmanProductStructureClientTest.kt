package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.ProductKind
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
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections

private const val COMPOSITION = "Состоит из ..."

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

private class FakeLoodsman(
    private val objects: List<Obj>,
    private val edges: List<Edge>,
    private val linkNames: List<String> = listOf(COMPOSITION, "Документы")
) {
    val propsCalls: MutableList<List<Int>> = Collections.synchronizedList(mutableListOf())
    val linkCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    val linkAttributeCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    fun client(
        mapping: MappingConfig = MappingConfigLoader.load(emptyMap())
    ): LoodsmanProductStructureClient {
        val linkIdToEdge = mutableMapOf<Int, Edge>()
        val engine = MockEngine { request ->
            val params = request.url.parameters
            val body: JsonElement = when (request.url.encodedPath) {
                "/api/v4/Auth/login" -> buildJsonObject {
                    put("sessionId", "s1")
                    put("userId", 1)
                    put("isEditable", true)
                }
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
                "/api/v4/ObjectInfo/get-prop-objects" -> {
                    val ids = params["objectList"]!!.split(",").map { it.toInt() }
                    propsCalls.add(ids)
                    JsonArray(ids.mapNotNull { id -> objects.find { it.id == id } }.map { propOf(it) })
                }
                "/api/v4/ObjectInfo/get-info-about-version-mode-3" -> {
                    val obj = objects.first { it.id == params["idVersion"]!!.toInt() }
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
            respond(
                content = body.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpCookies)
        }
        val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
        return LoodsmanProductStructureClient(config, mapping, http)
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
    3, "Стандартное изделие", "Болт М6",
    mapOf("Обозначение изделия" to "ГОСТ 7798-70", "Позиция" to "2")
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
    rootId = ProductId("1"),
    depth = depth,
    attributes = attributes.toSet(),
    linkAttributes = linkAttributes,
    includeUnmappedAttributes = includeUnmapped
)

class LoodsmanProductStructureClientTest : ShouldSpec({
    should("load only the root and skip link requests when depth is 0") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadStructure(query(0))

        root.id shouldBe ProductId("1")
        root.kind shouldBe ProductKind.Assembly
        root.usages shouldBe emptyList()
        fake.linkCalls shouldBe emptyList()
        fake.propsCalls shouldBe listOf(listOf(1))
    }

    should("map identity, quantity, unit and position of direct children when depth is 1") {
        val root = FakeLoodsman(tree, treeEdges).client().loadStructure(query(1))

        root.designation shouldBe Designation.Value("АБ.001")
        root.name shouldBe Name.Value("Корпус")
        root.usages.map { it.child.id } shouldBe listOf(ProductId("2"), ProductId("3"))
        root.usages.all { it.relation == Relation.Composition } shouldBe true

        val (shaftUsage, boltUsage) = root.usages
        shaftUsage.child.kind shouldBe ProductKind.Part
        shaftUsage.child.designation shouldBe Designation.Value("АБ.002")
        shaftUsage.child.name shouldBe Name.Value("Вал")
        shaftUsage.quantity shouldBe Quantity.Exact(2.0)
        shaftUsage.unit shouldBe Field.Value("шт")
        shaftUsage.position shouldBe Field.Missing
        shaftUsage.child.usages shouldBe emptyList()

        boltUsage.child.kind shouldBe ProductKind.Standard
        boltUsage.child.designation shouldBe Designation.Value("ГОСТ 7798-70")
        boltUsage.child.name shouldBe Name.Value("Болт М6")
        boltUsage.quantity shouldBe Quantity.Range(4.0, 6.0)
        boltUsage.unit shouldBe Field.Missing
        boltUsage.position shouldBe Field.Missing
    }

    should("return quantity as missing when the link has no quantity") {
        val root = FakeLoodsman(tree, treeEdges).client().loadStructure(query(2))

        root.usages[0].child.usages.single().quantity shouldBe Quantity.Missing
    }

    should("load grandchildren when depth is 2 and request properties once per level") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadStructure(query(2))

        root.usages[0].child.usages.single().child.id shouldBe ProductId("4")
        fake.propsCalls.size shouldBe 3
        fake.propsCalls[1].toSet() shouldBe setOf(2, 3)
        fake.propsCalls[2] shouldBe listOf(4)
    }

    should("return empty attributes when not requested") {
        val fake = FakeLoodsman(tree, treeEdges)

        val without = fake.client().loadStructure(query(1))

        without.attributes shouldBe emptyMap()
    }

    should("fail on a cycle") {
        val cyclic = treeEdges + Edge(4, 1)

        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, cyclic).client().loadStructure(query(Int.MAX_VALUE))
        }
    }

    should("fail when a required link type is absent in metadata") {
        val fake = FakeLoodsman(tree, treeEdges, linkNames = listOf("Документы"))

        shouldThrow<LoodsmanApiException> { fake.client().loadStructure(query(1)) }
    }

    should("fail when the root object is not found") {
        val fake = FakeLoodsman(emptyList(), emptyList())

        shouldThrow<LoodsmanApiException> { fake.client().loadStructure(query(0)) }
    }

    should("keep an object of unknown type as unmapped by default") {
        val unknown = Obj(5, "Неведомый тип", "XX.001")
        val fake = FakeLoodsman(tree + unknown, treeEdges + Edge(1, 5))

        val root = fake.client().loadStructure(query(1))

        root.usages.last().child.kind shouldBe ProductKind.Unmapped("Неведомый тип")
    }

    should("skip an object of unknown type when the policy is SKIP") {
        val unknown = Obj(5, "Неведомый тип", "XX.001")
        val fake = FakeLoodsman(tree + unknown, treeEdges + Edge(1, 5))
        val mapping = MappingConfigLoader.load(emptyMap())
            .copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.SKIP))

        val root = fake.client(mapping).loadStructure(query(1))

        root.usages.map { it.child.id } shouldBe listOf(ProductId("2"), ProductId("3"))
    }

    should("fill version and state from object properties") {
        val root = FakeLoodsman(tree, treeEdges).client().loadStructure(query(1))

        root.version shouldBe Field.Value("1")
        root.state shouldBe Field.Value("Утверждён")
        root.usages[0].child.version shouldBe Field.Value("1")
        root.usages[0].child.state shouldBe Field.Value("Утверждён")
    }

    should("load position from link attribute when rule exists") {
        val edgeWithPosition = Edge(1, 2, min = 2.0, max = 2.0, unit = "шт", attrs = mapOf("Позиция" to "3"))
        val edges = listOf(edgeWithPosition) + treeEdges.drop(1)
        val fake = FakeLoodsman(tree, edges)

        val root = fake.client().loadStructure(query(1))

        root.usages[0].position shouldBe Field.Value(3)
    }

    should("load zero link attributes when no position rules and no link attributes requested") {
        val fake = FakeLoodsman(tree, treeEdges)
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            link = LinkMapping()
        )

        fake.client(mapping).loadStructure(query(1))

        fake.linkAttributeCalls.shouldBe(emptyList())
    }

    should("load link attributes and populate Usage.attributes") {
        val edgeWithNote = Edge(1, 2, min = 2.0, max = 2.0, unit = "шт", attrs = mapOf("Примечание" to "тест"))
        val edges = listOf(edgeWithNote) + treeEdges.drop(1)
        val fake = FakeLoodsman(tree, edges)

        val root = fake.client().loadStructure(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages[0].attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Text("тест"))
    }

    should("return Empty for requested linkAttribute without value") {
        val fake = FakeLoodsman(tree, treeEdges)

        val root = fake.client().loadStructure(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages[0].attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Empty)
    }

    should("fail when requested link attribute not in mapping") {
        val fake = FakeLoodsman(tree, treeEdges)
        val mapping = MappingConfigLoader.load(emptyMap()).copy(link = LinkMapping())

        shouldThrow<LoodsmanApiException> {
            fake.client(mapping).loadStructure(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))
        }
    }

    should("not call get-link-attributes when depth is 0") {
        val fake = FakeLoodsman(tree, treeEdges)

        fake.client().loadStructure(query(0))

        fake.linkAttributeCalls.shouldBe(emptyList())
        fake.linkCalls.shouldBe(emptyList())
    }

    should("fill requested node attributes and mark absent ones as empty") {
        val root = FakeLoodsman(tree, treeEdges).client().loadStructure(query(1, AttributeKey.Mass))

        root.attributes shouldBe mapOf(AttributeKey.Mass to AttributeValue.Empty)
        root.usages[0].child.attributes shouldBe mapOf(AttributeKey.Mass to AttributeValue.Text("1.5"))
    }

    should("use the attribute name overridden for the object kind") {
        val standard = bolt.copy(attrs = bolt.attrs + mapOf("Масса" to "9", "Масса изделия" to "0.02"))
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            attributes = mapOf("mass" to AttributeRule(name = "Масса", byKind = mapOf("STANDARD" to "Масса изделия")))
        )

        val root = FakeLoodsman(listOf(assembly, shaft, standard, pin), treeEdges).client(mapping)
            .loadStructure(query(1, AttributeKey.Mass))

        root.usages[0].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
        root.usages[1].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("0.02")
    }

    should("read an attribute defined only for some kinds") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(
            attributes = mapOf("mass" to AttributeRule(byKind = mapOf("PART" to "Масса")))
        )

        val root = FakeLoodsman(tree, treeEdges).client(mapping).loadStructure(query(1, AttributeKey.Mass))

        root.usages[0].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
        root.usages[1].child.attributes[AttributeKey.Mass] shouldBe AttributeValue.Empty
        root.attributes[AttributeKey.Mass] shouldBe AttributeValue.Empty
    }

    should("fail when a requested node attribute is absent in the mapping") {
        val mapping = MappingConfigLoader.load(emptyMap()).copy(attributes = emptyMap())

        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, treeEdges).client(mapping).loadStructure(query(1, AttributeKey.Mass))
        }
    }

    should("expose only attributes unknown to the mapping when unmapped ones are requested") {
        val extra = shaft.copy(attrs = shaft.attrs + ("Прочее" to "x"))

        val root = FakeLoodsman(listOf(assembly, extra, bolt, pin), treeEdges).client()
            .loadStructure(query(1, includeUnmapped = true))

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

        val root = FakeLoodsman(tree, edges).client(mapping).loadStructure(query(1))

        root.usages[0].position shouldBe Field.Value(1)
        root.usages[1].position shouldBe Field.Value(2)
    }

    should("return missing position when the link has no position attribute") {
        val root = FakeLoodsman(tree, treeEdges).client().loadStructure(query(1))

        root.usages.map { it.position } shouldBe listOf(Field.Missing, Field.Missing)
    }

    should("reject the built-in position key in the link attributes query") {
        shouldThrow<LoodsmanApiException> {
            FakeLoodsman(tree, treeEdges).client()
                .loadStructure(query(1, linkAttributes = setOf(LinkAttributeKey.Position)))
        }
    }

    should("not duplicate the position in the link attributes of a usage") {
        val edges = listOf(Edge(1, 2, attrs = mapOf("Позиция" to "3", "Примечание" to "n")))

        val root = FakeLoodsman(tree, edges).client()
            .loadStructure(query(1, linkAttributes = setOf(LinkAttributeKey.Note)))

        root.usages.single().position shouldBe Field.Value(3)
        root.usages.single().attributes shouldBe mapOf(LinkAttributeKey.Note to AttributeValue.Text("n"))
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

        fake.client(mapping).loadStructure(query(1))

        fake.linkAttributeCalls.size shouldBe 1
    }
})
