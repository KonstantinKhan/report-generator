package dev.reportgenerator.loodsman

import dev.reportgenerator.loodsman.mapping.AttributeRule
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.installLoodsman
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Имена совпадают с подтверждёнными метаданными Loodsman из MappingConfigValidatorTest.
private val confirmedLinkTypes = listOf("Состоит из ...", "Документы", "Технологическая ДСЕ для")
private val confirmedObjectTypes = listOf(
    "Деталь", "Сборочная единица", "Сборочный чертеж", "Комплекс",
    "Стандартное изделие", "Прочее изделие", "Материал по КД", "Комплект",
    "Спецификация", "Чертеж детали",
)
private val confirmedAttributes = listOf(
    "Наименование", "Обозначение", "Масса", "Наименование предприятия", "Позиция", "Примечание", "Формат",
)

private fun api(typeListStatus: HttpStatusCode = HttpStatusCode.OK): LoodsmanApi {
    val engine = MockEngine { request ->
        val body = when (request.url.encodedPath) {
            "/api/v4/Auth/login" -> buildJsonObject {
                put("sessionId", "s1")
                put("userId", 1)
                put("isEditable", true)
            }
            "/api/v4/MetaData/cache/version" -> JsonPrimitive(1)
            "/api/v4/MetaData/get-link-list" -> buildJsonArray {
                confirmedLinkTypes.forEachIndexed { index, name ->
                    add(buildJsonObject {
                        put("id", index + 6)
                        put("name", name)
                        put("type", 0)
                        put("order", index)
                    })
                }
            }
            "/api/v4/MetaData/get-attribute-list-2" -> buildJsonArray {
                confirmedAttributes.forEachIndexed { index, name ->
                    add(buildJsonObject {
                        put("id", index + 1)
                        put("name", name)
                        put("attrType", 0)
                        put("system", 0)
                        put("onlyListItems", 0)
                    })
                }
            }
            "/api/v4/MetaDataCustom/types-links" -> buildJsonArray {
                // Деталь (id 1) читает «Технологическая ДСЕ для» (id 8, см. get-link-list) только в обратном направлении.
                add(buildJsonObject {
                    put("typeId1", 1)
                    put("typeId2", 2)
                    put("idLinkType", 8)
                    put("direction", -1)
                })
            }
            "/api/v4/MetaData/get-type-list" -> {
                if (typeListStatus != HttpStatusCode.OK) {
                    return@MockEngine respond("boom", typeListStatus)
                }
                buildJsonArray {
                    confirmedObjectTypes.forEachIndexed { index, name ->
                        add(buildJsonObject {
                            put("id", index + 1)
                            put("typeName", name)
                            put("attrName", "Обозначение")
                            put("document", 0)
                        })
                    }
                }
            }
            else -> JsonArray(emptyList())
        }
        respond(
            content = body.toString(),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val config = LoodsmanConfig("http://localhost", "db", "user", "pass")
    return LoodsmanApi(config, HttpClient(engine) { installLoodsman() })
}

private val validMapping: MappingConfig = MappingConfigLoader.load(emptyMap())

class LoodsmanContextTest : ShouldSpec({

    should("bootstrap a working context when the mapping matches Loodsman metadata") {
        val context = LoodsmanContext.bootstrap(api(), validMapping)

        context.client.shouldBeInstanceOf<LoodsmanProductStructureClient>()
        context.metadata.snapshot().version shouldBe 1
        context.metadata.snapshot().objectTypes.size shouldBe confirmedObjectTypes.size
        context.metadata.refresh() shouldBe RefreshResult.Unchanged(1)
    }

    should("fail bootstrap and list every mapping error when the mapping does not match Loodsman metadata") {
        val invalid = validMapping.copy(
            kinds = validMapping.kinds + ("Несуществующий тип" to "PART"),
            attributes = validMapping.attributes + ("mass" to AttributeRule(name = "Несуществующий атрибут")),
        )

        val error = shouldThrow<IllegalStateException> {
            LoodsmanContext.bootstrap(api(), invalid)
        }

        error.message shouldContain "kinds: type 'Несуществующий тип' not found in Loodsman"
        error.message shouldContain "attributes.mass.name: attribute 'Несуществующий атрибут' not found in Loodsman"
    }

    should("fail bootstrap when metadata cannot be loaded") {
        shouldThrowAny {
            LoodsmanContext.bootstrap(api(typeListStatus = HttpStatusCode.InternalServerError), validMapping)
        }
    }
})
