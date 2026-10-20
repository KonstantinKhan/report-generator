package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.orElse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("loodsman")

fun main(): Unit = runBlocking {
    val config = LoodsmanConfig(
        baseUrl = requiredEnv("LOODSMAN_BASE_URL"),
        dbName = requiredEnv("LOODSMAN_DB_NAME"),
        username = requiredEnv("LOODSMAN_USERNAME"),
        password = requiredEnv("LOODSMAN_PASSWORD")
    )
    val api = KtorLoodsmanApi(config, loodsmanHttpClient())
    val client = LoodsmanProductStructureClient(api)
    try {
        val root = client.loadStructure(
            StructureQuery(
                rootId = ProductId("5697"),
//                rootId = ProductId("5693"),
                depth = 1,
                linkAttributes = setOf(LinkAttributeKey.Note),
                attributes = setOf(AttributeKey.Mass, AttributeKey.CompanyName),
                relations = setOf(Relation.Composition, Relation.Document)
            )
        )

        println("Корень: ${root.designation.orElse("—")} / ${root.name.orElse("—")} (version: ${root.version.orElse("—")}, state: ${root.state.orElse("—")}, " +
                "Наименование предприятия: ${root.attributes[AttributeKey.CompanyName].orElse("—")})")
        root.usages.forEach { usage ->
            val child = usage.child
            val positionStr = when (usage) {
                is CompositionUsage -> usage.position.orElse("—")
                else -> "—"
            }
            val quantityStr = when (usage) {
                is CompositionUsage -> usage.quantity.orElse("—")
                else -> "—"
            }
            val unitStr = when (usage) {
                is CompositionUsage -> usage.unit.orElse("—")
                else -> "—"
            }
            println("  Связь: ${usage.relation}, Kind: ${child.kind}, Позиция: $positionStr, " +
                    "Количество: $quantityStr, Единица: $unitStr, " +
                    "Обозначение: ${child.designation.orElse("—")}, Наименование: ${child.name.orElse("—")}, " +
                    "version: ${child.version.orElse("—")}, state: ${child.state.orElse("—")}, " +
                    "Примечание: ${usage.attributes[LinkAttributeKey.Note].orElse("—")}, " +
                    "Наименование предприятия: ${child.attributes[AttributeKey.CompanyName].orElse("—")}")
        }
    } catch (e: LoodsmanApiException) {
        logger.error(e.message)
        logger.debug("Details", e)
    } finally {
        api.logout()
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name) ?: error("Environment variable $name is required")
