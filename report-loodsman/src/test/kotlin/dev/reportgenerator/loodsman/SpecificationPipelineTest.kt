package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.SpecificationData
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.reports.specification.specificationQuery
import dev.reportgenerator.reports.specification.toSpecificationData
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman

// Regression test for the production specification pipeline:
// LoodsmanProductStructureClient.loadStructure(specificationQuery(...)) -> toSpecificationData().
// Expected values were fixed against the output of the legacy path (removed) on the same Loodsman responses.

private const val COMPOSITION_LINK = "Состоит из ..."
private const val DOCUMENT_LINK = "Документы"
private const val COMPOSITION_LINK_ID = 1
private const val DOCUMENT_LINK_ID = 2

private data class ParityObj(
    val id: Int,
    val type: String,
    val product: String,
    val attrs: Map<String, String> = emptyMap(),
)

private data class ParityEdge(
    val parent: Int,
    val child: Int,
    val link: String = COMPOSITION_LINK,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
)

private class ParityLoodsman(
    private val objects: List<ParityObj>,
    private val edges: List<ParityEdge>,
) {
    fun httpClient(): HttpClient {
        val engine = MockEngine { request ->
            val params = request.url.parameters
            val body: JsonElement = when (request.url.encodedPath) {
                "/api/v4/Auth/login" -> buildJsonObject {
                    put("sessionId", "s1")
                    put("userId", 1)
                    put("isEditable", true)
                }
                "/api/v4/MetaData/get-link-list" -> buildJsonArray {
                    listOf(COMPOSITION_LINK_ID to COMPOSITION_LINK, DOCUMENT_LINK_ID to DOCUMENT_LINK).forEach { (id, name) ->
                        add(buildJsonObject {
                            put("id", id)
                            put("name", name)
                            put("type", 0)
                            put("order", id)
                        })
                    }
                }
                "/api/v4/ObjectInfo/get-prop-objects" -> {
                    val ids = params["objectList"]!!.split(",").map { it.toInt() }
                    JsonArray(ids.mapNotNull { id -> objects.find { it.id == id }?.let(::propOf) })
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
                    val linkTypes = params["linksTypesIds"]!!.split(",").map { it.toInt() }.toSet()
                    JsonArray(edges.withIndex()
                        .filter { (_, edge) -> edge.parent == parent && linkTypeId(edge.link) in linkTypes }
                        .map { (index, edge) -> linkOf(edge, index + 1) })
                }
                // Link attributes (position, note) are not part of SpecificationItem: no values needed.
                "/api/v4/ObjectInfo/get-link-attributes" -> buildJsonArray {}
                else -> error("Unexpected request ${request.url}")
            }
            respond(
                content = body.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) { installLoodsman() }
    }

    private fun linkTypeId(name: String): Int = when (name) {
        COMPOSITION_LINK -> COMPOSITION_LINK_ID
        DOCUMENT_LINK -> DOCUMENT_LINK_ID
        else -> error("Unknown link $name")
    }

    private fun propOf(obj: ParityObj) = buildJsonObject {
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

    private fun linkOf(edge: ParityEdge, idLink: Int) = buildJsonObject {
        put("idLink", idLink)
        put("idParent", edge.parent)
        put("idChild", edge.child)
        put("idLinkType", linkTypeId(edge.link))
        put("linkTypeName", edge.link)
        put("idType", 0)
        put("idState", 0)
        edge.min?.let { put("minQuantity", it) }
        edge.max?.let { put("maxQuantity", it) }
        edge.unit?.let { put("unit", it) }
    }
}

private suspend fun loadPipeline(
    objects: List<ParityObj>,
    edges: List<ParityEdge>,
): SpecificationData {
    val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
    val httpClient = ParityLoodsman(objects, edges).httpClient()
    return LoodsmanProductStructureClient(LoodsmanApi(config, httpClient))
        .loadStructure(specificationQuery(PdmNodeId(1)))
        .toSpecificationData()
}

class SpecificationPipelineTest : ShouldSpec({
    should("match assembly with a part and an assembly unit") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
                ParityObj(3, "Сборочная единица", "АБ.003", mapOf("Наименование" to "Крышка")),
            ),
            edges = listOf(
                ParityEdge(1, 2, min = 2.0, max = 2.0, unit = "шт"),
                ParityEdge(1, 3, min = 1.0, max = 1.0, unit = "шт"),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("АБ.002", "Вал", ItemKind.PART, 2.0, "шт"),
                SpecificationItem("АБ.003", "Крышка", ItemKind.ASSEMBLY, 1.0, "шт"),
            ),
        )
    }

    should("match standard and other products with the designation attribute") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(4, "Стандартное изделие", "Болт М6", mapOf("Обозначение изделия" to "ГОСТ 7798-70")),
                ParityObj(5, "Прочее изделие", "Заглушка", mapOf("Обозначение изделия" to "ИЗД.5")),
            ),
            edges = listOf(
                ParityEdge(1, 4, min = 4.0, max = 4.0, unit = "шт"),
                ParityEdge(1, 5, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("ГОСТ 7798-70", "Болт М6", ItemKind.STANDARD, 4.0, "шт"),
                SpecificationItem("ИЗД.5", "Заглушка", ItemKind.OTHER, 1.0, null),
            ),
        )
    }

    should("match material with quantity and unit") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(6, "Материал по КД", "Сталь 09Г2С", mapOf("Наименование" to "Лист")),
            ),
            edges = listOf(ParityEdge(1, 6, min = 1.5, max = 1.5, unit = "кг")),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem(null, "Сталь 09Г2С", ItemKind.MATERIAL, 1.5, "кг"),
            ),
        )
    }

    should("match document by the Документы link without quantity") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(7, "Сборочный чертеж", "АБ.001 СБ", mapOf("Наименование" to "Корпус. Сборочный чертеж")),
            ),
            edges = listOf(ParityEdge(1, 7, link = DOCUMENT_LINK)),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("АБ.001 СБ", "Корпус. Сборочный чертеж", ItemKind.DOCUMENTATION, 0.0, null),
            ),
        )
    }

    should("match document of the Сборочный чертёж type written with ё") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(10, "Сборочный чертёж", "АБ.010 СБ", mapOf("Наименование" to "Корпус. Сборочный чертёж")),
            ),
            edges = listOf(ParityEdge(1, 10, link = DOCUMENT_LINK)),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("АБ.010 СБ", "Корпус. Сборочный чертёж", ItemKind.DOCUMENTATION, 0.0, null),
            ),
        )
    }

    should("exclude an assembly drawing written with ё from the composition") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
                ParityObj(11, "Сборочный чертёж", "АБ.011 СБ", mapOf("Наименование" to "Чертёж в составе")),
            ),
            edges = listOf(
                ParityEdge(1, 2, min = 1.0, max = 1.0),
                ParityEdge(1, 11, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("АБ.002", "Вал", ItemKind.PART, 1.0, null),
            ),
        )
        // Observable exclusion: only the part stays in items, the drawing written with ё is dropped.
        actual.items.map { it.name } shouldBe listOf("Вал")
    }

    should("exclude an assembly drawing from the composition") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
                ParityObj(8, "Сборочный чертеж", "АБ.008 СБ", mapOf("Наименование" to "Чертеж в составе")),
            ),
            edges = listOf(
                ParityEdge(1, 2, min = 1.0, max = 1.0),
                ParityEdge(1, 8, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("АБ.002", "Вал", ItemKind.PART, 1.0, null),
            ),
        )
    }

    should("skip an object of unknown type") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
                ParityObj(9, "Неведомый тип", "XX.009"),
            ),
            edges = listOf(
                ParityEdge(1, 2, min = 1.0, max = 1.0),
                ParityEdge(1, 9, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            items = listOf(
                SpecificationItem("АБ.002", "Вал", ItemKind.PART, 1.0, null),
            ),
        )
    }
})
