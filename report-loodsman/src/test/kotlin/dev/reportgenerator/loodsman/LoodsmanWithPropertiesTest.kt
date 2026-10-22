package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.mapping.FieldSource
import dev.reportgenerator.loodsman.mapping.IdentityMapping
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import dev.reportgenerator.loodsman.mapping.OnUnmapped
import dev.reportgenerator.loodsman.mapping.UnmappedPolicy
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.PropField
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

// Default mapping, but the name comes from the PDM prop, so no attribute is needed for identity.
private val propNameMapping: MappingConfig = MappingConfigLoader.load(emptyMap()).let { default ->
    default.copy(
        identity = IdentityMapping(
            designation = FieldSource.Prop(PropField.PRODUCT),
            name = FieldSource.Prop(PropField.PRODUCT),
        )
    )
}

class LoodsmanWithPropertiesTest : ShouldSpec({
    should("map the loodsman type to the node kind and read identity from props and attributes") {
        val fake = SingleObjectFake("Деталь")
        val client = LoodsmanProductStructureClient(fake.api(), metadata = unusedMetadata)

        val node = client.withProperties(PdmNodeId(2), emptySet())

        node.id shouldBe PdmNodeId(2)
        node.kind shouldBe PdmNodeKind.Part
        node.designation shouldBe Designation.Value("АБ.002")
        node.name shouldBe Name.Value("Вал")
    }

    should("not call the attribute endpoint when no attributes are asked and identity needs none") {
        val fake = SingleObjectFake("Деталь")
        val client = LoodsmanProductStructureClient(fake.api(), propNameMapping, metadata = unusedMetadata)

        val node = client.withProperties(PdmNodeId(2), emptySet(), includeUnmapped = false)

        node.name shouldBe Name.Value("АБ.002")
        fake.infoCalls.get() shouldBe 0
    }

    should("call the attribute endpoint when a mapped attribute key is asked") {
        val fake = SingleObjectFake("Деталь")
        val client = LoodsmanProductStructureClient(fake.api(), propNameMapping, metadata = unusedMetadata)

        val node = client.withProperties(PdmNodeId(2), setOf(AttributeKey.Mass))

        fake.infoCalls.get() shouldBe 1
        node.attributes[AttributeKey.Mass] shouldBe AttributeValue.Text("1.5")
    }

    should("call the attribute endpoint when unmapped attributes are asked") {
        val fake = SingleObjectFake("Деталь")
        val client = LoodsmanProductStructureClient(fake.api(), propNameMapping, metadata = unusedMetadata)

        client.withProperties(PdmNodeId(2), emptySet(), includeUnmapped = true)

        fake.infoCalls.get() shouldBe 1
    }

    should("call the attribute endpoint when identity is read from an attribute") {
        val fake = SingleObjectFake("Деталь")
        val client = LoodsmanProductStructureClient(fake.api(), metadata = unusedMetadata)

        client.withProperties(PdmNodeId(2), emptySet())

        fake.infoCalls.get() shouldBe 1
    }

    should("fail when an asked attribute key is not configured in mapping.attributes") {
        val fake = SingleObjectFake("Деталь")
        val client = LoodsmanProductStructureClient(
            fake.api(),
            MappingConfigLoader.load(emptyMap()).copy(attributes = emptyMap()),
            metadata = unusedMetadata,
        )

        shouldThrow<LoodsmanApiException> {
            client.withProperties(PdmNodeId(2), setOf(AttributeKey.Mass))
        }
    }

    should("keep an unknown loodsman type as unmapped kind with the default KEEP policy") {
        val fake = SingleObjectFake("Неизвестный тип")
        val client = LoodsmanProductStructureClient(fake.api(), metadata = unusedMetadata)

        val node = client.withProperties(PdmNodeId(2), emptySet())

        node.kind shouldBe PdmNodeKind.Unmapped("Неизвестный тип")
    }

    should("fail when the loodsman object type is unmapped and the policy is SKIP") {
        val fake = SingleObjectFake("Неизвестный тип")
        val client = LoodsmanProductStructureClient(
            fake.api(),
            MappingConfigLoader.load(emptyMap()).copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.SKIP)),
            metadata = unusedMetadata,
        )

        shouldThrow<IllegalStateException> {
            client.withProperties(PdmNodeId(2), emptySet())
        }
    }

    should("fail when the loodsman object type is unmapped and the policy is FAIL") {
        val fake = SingleObjectFake("Неизвестный тип")
        val client = LoodsmanProductStructureClient(
            fake.api(),
            MappingConfigLoader.load(emptyMap()).copy(onUnmapped = UnmappedPolicy(kind = OnUnmapped.FAIL)),
            metadata = unusedMetadata,
        )

        shouldThrow<IllegalArgumentException> {
            client.withProperties(PdmNodeId(2), emptySet())
        }
    }
})
