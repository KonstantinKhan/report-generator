package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.NodeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.orElse
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.loodsmanHttpClient
import khan366kos.loodsman.api.dsl.pdmNode
import khan366kos.loodsman.api.dsl.pdmTree
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.whileSelect
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("loodsman")

// Ручная проверка клиента против живого Loodsman (запускать из IDE). Не входит ни в одну поставку.
// Настройки берутся из переменных окружения LOODSMAN_BASE_URL, LOODSMAN_DB_NAME, LOODSMAN_USERNAME, LOODSMAN_PASSWORD.
fun main(): Unit = runBlocking {
    val config = LoodsmanConfig(
        baseUrl = withScheme(requiredEnv("LOODSMAN_BASE_URL")),
        dbName = requiredEnv("LOODSMAN_DB_NAME"),
        username = requiredEnv("LOODSMAN_USERNAME"),
        password = requiredEnv("LOODSMAN_PASSWORD")
    )
    val api = LoodsmanApi(config, loodsmanHttpClient())
    try {
        // Как в сервере: проверенный конфиг + снимок метаданных; при несовпадении конфига с Loodsman здесь будет ошибка.
        val context = LoodsmanContext.bootstrap(api, MappingConfigLoader.load())
        val client = context.client

//        val root = pdmTree {
//            id = 5697
//            pdmClient = client
//            depth = 2
//            relations = setOf(Relation.Composition, Relation.Document)
//            attributeKeys = setOf(
//                AttributeKey.Designation,
//                AttributeKey.Name,
//                AttributeKey.Mass,
//                AttributeKey.CompanyName,
//            )
//            linkAttributeKeys = setOf(LinkAttributeKey.Note)
//        }
//
//        println("Корень: ${root.title()} [${root.kind}] ключ: ${root.key.text()}")
//        printComposition(root, level = 0)

        // Один узел без обхода дерева:
//        val single = pdmNode {
//            id = 5697
//            pdmClient = client
//            attributeKeys = setOf(AttributeKey.Designation, AttributeKey.Name)
//        }
//        println("Узел: ${single.title()} [${single.kind}] ключ: ${single.key.text()}")

        val tree = pdmTree {
            id = 5697
            pdmClient = client
            // depth = уровни обхода: 2 — документы у корня и у объектов состава первого уровня.
            depth = Int.MAX_VALUE
            // Состав раскрывается только до детей корня (уровень 1), глубже — только документы.
            relationDepth = mapOf(Relation.Composition to 1)
            relations = setOf(Relation.Composition, Relation.Document)
            attributeKeys = setOf(AttributeKey.Designation, AttributeKey.Name, AttributeKey.Format)
            linkAttributeKeys = setOf(LinkAttributeKey.Note)
        }

        println("Объект: ${tree.title()} [${tree.kind}]")
        tree.printStructure(level = 0)

    } catch (e: LoodsmanApiException) {
        logger.error(e.message)
        logger.debug("Details", e)
    } finally {
        api.logout()
    }
}

private fun PdmNode.title(): String =
    "${attributes[AttributeKey.Designation].orElse("—")} / ${attributes[AttributeKey.Name].orElse("—")}"

private fun PdmNode.nameText(): String = attributes[AttributeKey.Name].orElse("—")

private fun PdmNode.formatText(): String = attributes[AttributeKey.Format].orElse("—")

// Объект и его данные: документы по связи DOCUMENT (имя, вид, формат) и входящие объекты по COMPOSITION —
// рекурсивно в пределах загруженной глубины. У входящих объектов — позиция, количество и единица: это поля
// связи (позиция — встроенный ключ, quantity/unit из get-linked-objects-for-objects), грузятся со списком
// связей без отдельных запросов.
// Примечание — атрибут связи COMPOSITION (link.attributes.note), грузится при linkAttributeKeys в запросе.
// Формат замаплен для SPECIFICATION, DETAIL_DRAWING и ASSEMBLY_DRAWING, у остальных видов будет «—».
private fun PdmNode.printStructure(level: Int) {
    usages.filterIsInstance<DocumentUsage>().forEach { usage ->
        val target = usage.target
        val indent = "  ".repeat(level + 1)
        println("$indent Документ: ${target.nameText()} [${target.kind}] Формат: ${target.formatText()}")
    }
    usages.filterIsInstance<CompositionUsage>().forEach { usage ->
        val target = usage.target
        val indent = "  ".repeat(level + 1)
        val unit = usage.unit.text().takeIf { it != "—" }?.let { " $it" }.orEmpty()
        val note = usage.attributes[LinkAttributeKey.Note].orElse("—")
        println("$indent Объект: Поз.${usage.position.text()} ${target.title()} [${target.kind}] ×${usage.quantity.orElse("—")}$unit Прим.: $note")
        target.printStructure(level + 1)
    }
}

private fun NodeKey.text(): String = when (this) {
    is NodeKey.Value -> "${attribute}=$text"
    NodeKey.Missing -> "—"
    NodeKey.NotLoaded -> "не загружен"
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
                    "${usage.target.title()} " +
                    "×${usage.quantity.orElse("—")} ${usage.unit.text()} " +
                    "[${usage.target.kind}] " +
                    "ключ: ${usage.target.key.text()} " +
                    "Масса: ${usage.target.attributes[AttributeKey.Mass].orElse("—")} " +
                    "Предприятие: ${usage.target.attributes[AttributeKey.CompanyName].orElse("—")} " +
                    "Прим.: ${usage.attributes[LinkAttributeKey.Note].orElse("—")}"
        )
        printComposition(usage.target, level + 1)
    }
}

private fun withScheme(url: String): String {
    val trimmed = url.trim()
    return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

private fun requiredEnv(name: String): String =
    System.getenv(name) ?: error("Environment variable $name is required")
