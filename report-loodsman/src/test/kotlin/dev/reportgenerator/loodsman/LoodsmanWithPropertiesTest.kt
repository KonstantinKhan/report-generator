package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.NodeKey
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.loodsman.mapping.OnUnmapped
import dev.reportgenerator.loodsman.mapping.UnmappedPolicy
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman
import io.kotest.assertions.throwables.shouldThrow
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
import java.util.concurrent.atomic.AtomicInteger

// One object with its attributes; counts how often the attribute endpoint is called.
private class SingleObjectFake(
    private val type: String,
    private val product: String = "АБ.002",
    private val attrs: Map<String, String> = mapOf("Наименование" to "Вал", "Масса" to "1.5"),
) {
    val infoCalls = AtomicInteger(0)

    fun api(): LoodsmanApi {
        val engine = MockEngine { request ->
            val params = request.url.parameters
            val body: JsonElement = when (request.url.encodedPath) {
                "/api/v4/Auth/login" -> buildJsonObject {
                    put("sessionId", "s1")
                    put("userId", 1)
                    put("isEditable", true)
                }
                "/api/v4/ObjectInfo/get-prop-objects" -> {
                    require(params["objectList"] == "2") { "unexpected objectList ${params["objectList"]}" }
                    JsonArray(listOf(propOf()))
                }
                "/api/v4/ObjectInfo/get-info-about-version-mode-3" -> {
                    infoCalls.incrementAndGet()
                    buildJsonArray {
                        attrs.forEach { (name, value) ->
                            add(buildJsonObject {
                                put("name", name)
                                put("value", value)
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
        val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
        return LoodsmanApi(config, HttpClient(engine) { installLoodsman() })
    }

    private fun propOf() = buildJsonObject {
        put("idVersion", 2)
        put("type", type)
        put("document", 0)
        put("product", product)
        put("version", "1")
        put("state", "Утверждён")
        put("locked", 0)
        put("accessLevel", 0)
        put("label", 0)
        put("hasLink", 0)
        put("mainId", 2)
    }
}

/** Метаданные с указанными типами объектов; у каждого типа ключевой атрибут «Обозначение». */
private fun metadataFor(vararg types: String) = MetadataSource {
    MetadataSnapshot(
        linkTypes = emptyList(),
        objectTypes = types.mapIndexed { index, name ->
            ObjectTypeInfo(id = index + 1, typeName = name, keyAttribute = "Обозначение")
        },
        attributeNames = setOf("Обозначение", "Наименование", "Масса"),
        version = 1,
    )
}

private val defaultMapping: MappingConfig = MappingConfigLoader.load(emptyMap())

private val eightKinds = listOf(
    "Деталь", "Сборочная единица", "Сборочный чертеж", "Комплекс", "Стандартное изделие",
    "Прочее изделие", "Материал по КД", "Комплект",
)

private fun clientFor(
    fake: SingleObjectFake,
    mapping: MappingConfig = defaultMapping,
    metadata: MetadataSource = metadataFor(*eightKinds.toTypedArray()),
) = LoodsmanProductStructureClient(fake.api(), mapping, metadata = metadata)

class LoodsmanWithPropertiesTest : ShouldSpec({
    should("map the loodsman type to the node kind and read designation and name") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake)

        val node = client.withProperties(PdmNodeId(2), setOf(AttributeKey.Designation, AttributeKey.Name))

        node.id shouldBe PdmNodeId(2)
        node.kind shouldBe PdmNodeKind.Part
        node.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.002")
        node.attributes[AttributeKey.Name] shouldBe AttributeValue.Text("Вал")
        node.key shouldBe NodeKey.Value(AttributeKey.Designation, "АБ.002")
    }

    should("not call the attribute endpoint when nothing is asked") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake)

        client.withProperties(PdmNodeId(2), emptySet(), includeUnmapped = false)

        fake.infoCalls.get() shouldBe 0
    }

    should("not call the attribute endpoint when only the key-attribute designation is asked") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake)

        val node = client.withProperties(PdmNodeId(2), setOf(AttributeKey.Designation))

        fake.infoCalls.get() shouldBe 0
        node.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text("АБ.002")
    }

    should("call the attribute endpoint when a non-key name is asked") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake)

        val node = client.withProperties(PdmNodeId(2), setOf(AttributeKey.Name))

        fake.infoCalls.get() shouldBe 1
        node.attributes[AttributeKey.Name] shouldBe AttributeValue.Text("Вал")
    }

    should("call the attribute endpoint when a mapped attribute key is asked") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake)

        val node = client.withProperties(PdmNodeId(2), setOf(AttributeKey.Mass))

        fake.infoCalls.get() shouldBe 1
        node.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
    }

    should("call the attribute endpoint when unmapped attributes are asked") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake)

        client.withProperties(PdmNodeId(2), emptySet(), includeUnmapped = true)

        fake.infoCalls.get() shouldBe 1
    }

    should("fail when an asked attribute key is not configured in mapping.attributes") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(
            fake,
            MappingConfigLoader.load(emptyMap()).copy(attributes = emptyMap()),
        )

        shouldThrow<LoodsmanApiException> {
            client.withProperties(PdmNodeId(2), setOf(AttributeKey.Mass))
        }
    }

    should("keep an unknown loodsman type as unmapped kind with the default KEEP policy") {
        val fake = SingleObjectFake("Неизвестный тип")
        val client = clientFor(fake, metadata = metadataFor("Неизвестный тип"))

        val node = client.withProperties(PdmNodeId(2), emptySet())

        node.kind shouldBe PdmNodeKind.Unmapped("Неизвестный тип")
    }

    should("fail when the loodsman object type is missing in the metadata") {
        val fake = SingleObjectFake("Деталь")
        val client = clientFor(fake, metadata = metadataFor("Сборочная единица"))

        val error = shouldThrow<LoodsmanApiException> {
            client.withProperties(PdmNodeId(2), emptySet())
        }

        error.message shouldBe "Object 2: type 'Деталь' not found in Loodsman metadata"
    }

    should("fail when the loodsman object type is unmapped and the policy is SKIP") {
        val fake = SingleObjectFake("Неизвестный тип")
        val client = clientFor(
            fake,
            MappingConfigLoader.load(emptyMap()).copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.SKIP)),
            metadataFor("Неизвестный тип"),
        )

        shouldThrow<IllegalStateException> {
            client.withProperties(PdmNodeId(2), emptySet())
        }
    }

    should("fail when the loodsman object type is unmapped and the policy is FAIL") {
        val fake = SingleObjectFake("Неизвестный тип")
        val client = clientFor(
            fake,
            MappingConfigLoader.load(emptyMap()).copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.FAIL)),
            metadataFor("Неизвестный тип"),
        )

        shouldThrow<IllegalArgumentException> {
            client.withProperties(PdmNodeId(2), emptySet())
        }
    }

    should("return NodeKey.Missing and empty designation when the product is empty") {
        val fake = SingleObjectFake("Деталь", product = "   ")
        val client = clientFor(fake)

        val node = client.withProperties(PdmNodeId(2), setOf(AttributeKey.Designation))

        node.key shouldBe NodeKey.Missing
        node.attributes[AttributeKey.Designation] shouldBe AttributeValue.Empty
    }

    // Синтетические объекты по структуре, подтверждённой на сервере (значения выдуманы).
    // У видов Деталь/СЕ/Сборочный чертёж/Комплекс/Комплект product = обозначение, «Наименование» есть в списке.
    // У Стандартного/Прочего изделия и Материала по КД product = обозначение, «Наименование» в списке нет.
    val cases = listOf(
        Triple("Деталь", mapOf("Наименование" to "Наименование детали"), "ОБ.001" to "Наименование детали"),
        Triple("Сборочная единица", mapOf("Наименование" to "Наименование СЕ"), "ОБ.002" to "Наименование СЕ"),
        Triple("Сборочный чертеж", mapOf("Наименование" to "Наименование чертежа"), "ОБ.003 СБ" to "Наименование чертежа"),
        Triple("Комплекс", mapOf("Наименование" to "Наименование комплекса"), "ОБ.004" to "Наименование комплекса"),
        Triple("Комплект", mapOf("Наименование" to "Наименование комплекта"), "ОБ.005" to "Наименование комплекта"),
        Triple("Стандартное изделие", emptyMap(), "ОБ.006" to null),
        Triple("Прочее изделие", emptyMap(), "ОБ.007" to null),
        Triple("Материал по КД", emptyMap(), "ОБ.008" to null),
    )
    cases.forEach { (type, attrs, expected) ->
        should("read key, designation and name of '$type' from product and attributes") {
            val (designation, name) = expected
            val fake = SingleObjectFake(type, product = designation, attrs = attrs)

            val node = clientFor(fake).withProperties(PdmNodeId(2), setOf(AttributeKey.Designation, AttributeKey.Name))

            node.key shouldBe NodeKey.Value(AttributeKey.Designation, designation)
            node.attributes[AttributeKey.Designation] shouldBe AttributeValue.Text(designation)
            node.attributes[AttributeKey.Name] shouldBe (name?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty)
        }
    }
})
