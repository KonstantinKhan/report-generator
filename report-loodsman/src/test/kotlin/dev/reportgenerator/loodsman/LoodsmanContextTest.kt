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

// Срез живых метаданных Loodsman (id и имена как в базе), достаточный для default loodsman-mapping.yaml.
// Согласован с MappingConfigValidatorTest.
private val confirmedLinkTypes = listOf(
    1 to "Документы", 6 to "Состоит из ...", 7 to "Исполнения", 8 to "Изготавливается из ...",
    11 to "Технологическая ДСЕ для",
)
private val confirmedObjectTypes = listOf(
    16 to "Комплект документов", 22 to "Материал по КД", 58 to "Комплекс", 59 to "Сборочная единица",
    60 to "Деталь", 61 to "Комплект", 62 to "Стандартное изделие", 64 to "Прочее изделие",
    107 to "Ведомость эксплуатационных документов", 121 to "Чертеж детали", 122 to "Спецификация",
    126 to "Сборочный чертеж", 354 to "Форма материала",
)
private val confirmedAttributes = listOf(
    "Наименование", "Обозначение", "Масса", "Наименование предприятия", "Позиция", "Примечание", "Формат",
    "Обозначение изделия", "Наименование изделия", "Вид материала по ЕСКД", "Обозначение стандарта",
    "Вид изделия", "Типоразмер", "Зона", "Способ применения", "Назначение комплекта",
)
// Направления types-links, нужные правилам yaml: (тип, связь, направление).
private val confirmedTypeLinks = listOf(
    Triple(59, 6, 1), Triple(61, 6, 1),
    Triple(59, 1, 1), Triple(60, 1, 1), Triple(64, 1, 1), Triple(61, 1, 1), Triple(16, 1, 1),
    Triple(60, 11, -1), Triple(354, 8, 1), Triple(59, 7, 1),
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
                confirmedLinkTypes.forEachIndexed { index, (id, name) ->
                    add(buildJsonObject {
                        put("id", id)
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
                confirmedTypeLinks.forEach { (type, link, direction) ->
                    add(buildJsonObject {
                        put("typeId1", type)
                        put("typeId2", type)
                        put("idLinkType", link)
                        put("direction", direction)
                    })
                }
            }
            "/api/v4/MetaData/get-type-list" -> {
                if (typeListStatus != HttpStatusCode.OK) {
                    return@MockEngine respond("boom", typeListStatus)
                }
                buildJsonArray {
                    confirmedObjectTypes.forEach { (id, name) ->
                        add(buildJsonObject {
                            put("id", id)
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
