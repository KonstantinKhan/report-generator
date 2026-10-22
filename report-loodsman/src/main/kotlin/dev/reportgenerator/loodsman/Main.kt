package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.orElse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.loodsmanHttpClient
import khan366kos.loodsman.api.dsl.pdmTree

private val logger = LoggerFactory.getLogger("loodsman")

fun main(): Unit = runBlocking {
    val config = LoodsmanConfig(
        baseUrl = requiredEnv("LOODSMAN_BASE_URL"),
        dbName = requiredEnv("LOODSMAN_DB_NAME"),
        username = requiredEnv("LOODSMAN_USERNAME"),
        password = requiredEnv("LOODSMAN_PASSWORD")
    )
    val api = LoodsmanApi(config, loodsmanHttpClient())
    val client = LoodsmanProductStructureClient(api)
    try {

        val root = pdmTree {
            id = 5697
            pdmClient = client
            depth = 2
            relations =  setOf(Relation.Composition, Relation.Document)
            attributeKeys = setOf(AttributeKey.Mass, AttributeKey.CompanyName)
            linkAttributeKeys = setOf(LinkAttributeKey.Note)
        }
        root.usages.forEach { usage ->
            println(usage.relation)
        }

        println("Корень: ${root.designation.orElse("—")} / ${root.name.orElse("—")} [${root.kind}]")
        printComposition(root, level = 0)


//        val root = client.loadStructure(
//            StructureQuery(
//                rootId = PdmNodeId(5697),
////                rootId = ProductId("5693"),
//                depth = 1,
//                linkAttributes = setOf(LinkAttributeKey.Note),
//                attributes = setOf(AttributeKey.Mass, AttributeKey.CompanyName),
//                relations = setOf(Relation.Composition, Relation.Document)
//            )
//        )

//        println("Корень: ${root.designation.orElse("—")} / ${root.name.orElse("—")} (version: ${root.version.orElse("—")}, state: ${root.state.orElse("—")}, " +
//                "Наименование предприятия: ${root.attributes[AttributeKey.CompanyName].orElse("—")})")
//        root.usages.forEach { usage ->
//            val child = usage.child
//            val positionStr = when (usage) {
//                is CompositionUsage -> usage.position.orElse("—")
//                else -> "—"
//            }
//            val quantityStr = when (usage) {
//                is CompositionUsage -> usage.quantity.orElse("—")
//                else -> "—"
//            }
//            val unitStr = when (usage) {
//                is CompositionUsage -> usage.unit.orElse("—")
//                else -> "—"
//            }
//            println("  Связь: ${usage.relation}, Kind: ${child.kind}, Позиция: $positionStr, " +
//                    "Количество: $quantityStr, Единица: $unitStr, " +
//                    "Обозначение: ${child.designation.orElse("—")}, Наименование: ${child.name.orElse("—")}, " +
//                    "version: ${child.version.orElse("—")}, state: ${child.state.orElse("—")}, " +
//                    "Примечание: ${usage.attributes[LinkAttributeKey.Note].orElse("—")}, " +
//                    "Наименование предприятия: ${child.attributes[AttributeKey.CompanyName].orElse("—")}")
//        }
    } catch (e: LoodsmanApiException) {
        logger.error(e.message)
        logger.debug("Details", e)
    } finally {
        api.logout()
    }
}

private fun <T> Field<T>.text(): String = when (this) {
    is Field.Value -> value.toString()
    else -> "—"
}

private fun printComposition(node: PdmNode, level: Int) {
    node.usages.filterIsInstance<CompositionUsage>().forEach { usage ->
        val indent = "  ".repeat(level + 1)
        println(
            "$indent Поз.${usage.position.text()} " +
                "${usage.child.designation.orElse("—")} / ${usage.child.name.orElse("—")} " +
                "×${usage.quantity.orElse("—")} ${usage.unit.text()} " +
                "[${usage.child.kind}] " +
                "Масса: ${usage.child.attributes[AttributeKey.Mass].orElse("—")} " +
                "Предприятие: ${usage.child.attributes[AttributeKey.CompanyName].orElse("—")} " +
                "Прим.: ${usage.attributes[LinkAttributeKey.Note].orElse("—")}"
        )
        printComposition(usage.child, level + 1)
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name) ?: error("Environment variable $name is required")
