package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.MadeFromUsage
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.reports.specification.AssemblyItem
import dev.reportgenerator.reports.specification.DocumentationItem
import dev.reportgenerator.reports.specification.MaterialItem
import dev.reportgenerator.reports.specification.OtherItem
import dev.reportgenerator.reports.specification.PartItem
import dev.reportgenerator.reports.specification.SpecificationData
import dev.reportgenerator.reports.specification.SpecificationDataException
import dev.reportgenerator.reports.specification.StandardItem
import dev.reportgenerator.reports.specification.specificationQuery
import dev.reportgenerator.reports.specification.toSpecificationData
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman

// Regression test for the production specification pipeline:
// LoodsmanProductStructureClient.loadTree(specificationQuery(...)) -> toSpecificationData().
// Expected values were fixed against the output of the legacy path (removed) on the same Loodsman responses.

private const val COMPOSITION_LINK = "Состоит из ..."
private const val DOCUMENT_LINK = "Документы"
private const val MADE_FROM_LINK = "Изготавливается из ..."
private const val COMPOSITION_LINK_ID = 1
private const val DOCUMENT_LINK_ID = 2
private const val MADE_FROM_LINK_ID = 3

// Ключевой атрибут всех видов: значение лежит в product объекта.
private const val KEY_ATTRIBUTE = "Обозначение"

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
    val note: String? = null,
)

// Link attribute of the composition: the note (Примечание) of the link.
private const val NOTE_ATTRIBUTE = "Примечание"

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
                "/api/v4/MetaData/cache/version" -> JsonPrimitive(1)
                "/api/v4/MetaData/get-attribute-list-2" -> buildJsonArray {}
                "/api/v4/MetaData/get-link-list" -> buildJsonArray {
                    listOf(COMPOSITION_LINK_ID to COMPOSITION_LINK, DOCUMENT_LINK_ID to DOCUMENT_LINK, MADE_FROM_LINK_ID to MADE_FROM_LINK).forEach { (id, name) ->
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
                // Link attributes: the note of the edge (linkId = index of the edge + 1). The position is not in the mock,
                // it stays Missing.
                "/api/v4/ObjectInfo/get-link-attributes" -> {
                    val edge = edges[params["linkId"]!!.toInt() - 1]
                    buildJsonArray {
                        edge.note?.let { note ->
                            add(buildJsonObject {
                                put("id", 1)
                                put("name", NOTE_ATTRIBUTE)
                                put("value", note)
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
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine) { installLoodsman() }
    }

    private fun linkTypeId(name: String): Int = when (name) {
        COMPOSITION_LINK -> COMPOSITION_LINK_ID
        DOCUMENT_LINK -> DOCUMENT_LINK_ID
        MADE_FROM_LINK -> MADE_FROM_LINK_ID
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

// The tree of the specification query of the root 1, as the production pipeline loads it.
private suspend fun loadSpecificationTree(
    objects: List<ParityObj>,
    edges: List<ParityEdge>,
): PdmNode {
    val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
    val httpClient = ParityLoodsman(objects, edges).httpClient()
    val api = LoodsmanApi(config, httpClient)
    val metadata = MetadataCache(api, validate = { emptyList() }).also { it.initialize() }
    return LoodsmanProductStructureClient(api, MappingConfigLoader.load(emptyMap()), metadata = metadata)
        .loadTree(specificationQuery(PdmNodeId(1)))
}

private suspend fun loadPipeline(
    objects: List<ParityObj>,
    edges: List<ParityEdge>,
): SpecificationData = loadSpecificationTree(objects, edges).toSpecificationData()

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
            assemblies = listOf(AssemblyItem(designation = "АБ.003", name = "Крышка", quantity = 1.0)),
            parts = listOf(PartItem(designation = "АБ.002", name = "Вал", quantity = 2.0)),
        )
    }

    should("match standard and other products with the key attribute in the designation column moved to the name") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(4, "Стандартное изделие", "ГОСТ 7798-70"),
                ParityObj(5, "Прочее изделие", "ИЗД.5"),
            ),
            edges = listOf(
                ParityEdge(1, 4, min = 4.0, max = 4.0, unit = "шт"),
                ParityEdge(1, 5, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            standards = listOf(StandardItem(name = "ГОСТ 7798-70", quantity = 4.0)),
            others = listOf(OtherItem(name = "ИЗД.5", quantity = 1.0)),
        )
    }

    should("match standard and other products with the product designation attribute in the designation column") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(4, "Стандартное изделие", "ГОСТ 7798-70", mapOf("Обозначение изделия" to "БОЛТ-1")),
                ParityObj(5, "Прочее изделие", "ИЗД.5", mapOf("Обозначение изделия" to "ИЗД.5-П")),
            ),
            edges = listOf(
                ParityEdge(1, 4, min = 4.0, max = 4.0, unit = "шт"),
                ParityEdge(1, 5, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            standards = listOf(StandardItem(designation = "БОЛТ-1", name = "ГОСТ 7798-70", quantity = 4.0)),
            others = listOf(OtherItem(designation = "ИЗД.5-П", name = "ИЗД.5", quantity = 1.0)),
        )
    }

    should("match material with quantity and unit") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(6, "Материал по КД", "Сталь 09Г2С", mapOf("Наименование" to "Лист")),
            ),
            edges = listOf(ParityEdge(1, 6, min = 1.5, max = 1.5, unit = "кг", note = "Лист")),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            materials = listOf(MaterialItem(name = "Сталь 09Г2С", quantity = 1.5, notes = listOf("кг", "Лист"))),
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
            documentation = listOf(DocumentationItem("АБ.001 СБ", "Корпус. Сборочный чертеж")),
        )
    }

    should("match document of the Сборочный чертеж type with the exact name from mapping") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(10, "Сборочный чертеж", "АБ.010 СБ", mapOf("Наименование" to "Корпус. Сборочный чертёж")),
            ),
            edges = listOf(ParityEdge(1, 10, link = DOCUMENT_LINK)),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            documentation = listOf(DocumentationItem("АБ.010 СБ", "Корпус. Сборочный чертёж")),
        )
    }

    should("exclude an assembly drawing from the composition when its type name is in mapping") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
                ParityObj(11, "Сборочный чертеж", "АБ.011 СБ", mapOf("Наименование" to "Чертёж в составе")),
            ),
            edges = listOf(
                ParityEdge(1, 2, min = 1.0, max = 1.0),
                ParityEdge(1, 11, min = 1.0, max = 1.0),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            parts = listOf(PartItem(designation = "АБ.002", name = "Вал", quantity = 1.0)),
        )
        // Observable exclusion: only the part stays in the parts, the assembly drawing is dropped.
        actual.documentation shouldBe emptyList()
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
            parts = listOf(PartItem(designation = "АБ.002", name = "Вал", quantity = 1.0)),
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
            parts = listOf(PartItem(designation = "АБ.002", name = "Вал", quantity = 1.0)),
        )
    }

    should("take the format of an assembly from its specification and of a part from its detail drawing") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(2, "Деталь", "АБ.002", mapOf("Наименование" to "Вал")),
                ParityObj(3, "Сборочная единица", "АБ.003", mapOf("Наименование" to "Крышка")),
                ParityObj(20, "Чертеж детали", "АБ.002 ЧЕРТ", mapOf("Наименование" to "Вал. Чертеж", "Формат" to "А3х3")),
                ParityObj(21, "Спецификация", "АБ.003 СП", mapOf("Наименование" to "Крышка. Спецификация", "Формат" to "A4")),
            ),
            edges = listOf(
                ParityEdge(1, 2, min = 1.0, max = 1.0, note = "Закалить"),
                ParityEdge(1, 3, min = 1.0, max = 1.0),
                ParityEdge(2, 20, link = DOCUMENT_LINK),
                ParityEdge(3, 21, link = DOCUMENT_LINK),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            assemblies = listOf(AssemblyItem(designation = "АБ.003", name = "Крышка", quantity = 1.0, format = "A4")),
            parts = listOf(
                PartItem(
                    designation = "АБ.002",
                    name = "Вал",
                    quantity = 1.0,
                    format = "*)",
                    notes = listOf("*)А3х3", "Закалить"),
                ),
            ),
        )
    }

    should("match a material form by the material it is made from with the quantity, unit and note of the composition") {
        val actual = loadPipeline(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(5, "Форма материала", "ФОРМА 001", mapOf("Наименование" to "Форма")),
                ParityObj(6, "Материал по КД", "Пенополиуретан ППУ 202-1", mapOf("Наименование" to "ППУ")),
            ),
            edges = listOf(
                ParityEdge(1, 5, min = 2.5, max = 2.5, unit = "мл", note = "Вспенить"),
                ParityEdge(5, 6, link = MADE_FROM_LINK),
            ),
        )

        actual shouldBe SpecificationData(
            documentDesignation = "АБ.001",
            documentName = "Корпус",
            materials = listOf(
                MaterialItem(name = "Пенополиуретан ППУ 202-1", quantity = 2.5, notes = listOf("мл", "Вспенить")),
            ),
        )
    }

    should("load the material of a material form at depth two with its attributes") {
        val tree = loadSpecificationTree(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(5, "Форма материала", "ФОРМА 001"),
                ParityObj(6, "Материал по КД", "Пенополиуретан ППУ 202-1", mapOf("Наименование" to "ППУ")),
            ),
            edges = listOf(
                ParityEdge(1, 5, min = 1.0, max = 1.0, unit = "мл"),
                ParityEdge(5, 6, link = MADE_FROM_LINK),
            ),
        )

        val form = tree.usages.filterIsInstance<CompositionUsage>().single().target
        form.kind shouldBe PdmNodeKind.MaterialForm
        val material = form.usages.filterIsInstance<MadeFromUsage>().single().target
        material.kind shouldBe PdmNodeKind.Material
        material.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("Пенополиуретан ППУ 202-1")
    }

    should("fail on a material form without a material") {
        val failure = shouldThrow<SpecificationDataException> {
            loadPipeline(
                objects = listOf(
                    ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                    ParityObj(5, "Форма материала", "ФОРМА 001"),
                ),
                edges = listOf(ParityEdge(1, 5, min = 1.0, max = 1.0, unit = "мл")),
            )
        }

        failure.message shouldContain "5"
    }

    should("load the composition of the root only and the documents of its children") {
        val tree = loadSpecificationTree(
            objects = listOf(
                ParityObj(1, "Сборочная единица", "АБ.001", mapOf("Наименование" to "Корпус")),
                ParityObj(3, "Сборочная единица", "АБ.003", mapOf("Наименование" to "Крышка")),
                ParityObj(5, "Деталь", "АБ.005", mapOf("Наименование" to "Шайба")),
                ParityObj(21, "Спецификация", "АБ.003 СП", mapOf("Наименование" to "Крышка. Спецификация", "Формат" to "A4")),
            ),
            edges = listOf(
                ParityEdge(1, 3, min = 1.0, max = 1.0),
                ParityEdge(3, 5, min = 2.0, max = 2.0),
                ParityEdge(3, 21, link = DOCUMENT_LINK),
            ),
        )

        val child = tree.usages.filterIsInstance<CompositionUsage>().single().target
        // The composition of the child (level 2) is not expanded, its document (level 2) is read.
        child.usages.filterIsInstance<CompositionUsage>() shouldBe emptyList()
        child.usages.filterIsInstance<DocumentUsage>().single().target.attributes[AttributeKey.Format] shouldBe
            AttributeValue.Text("A4")
    }
})
