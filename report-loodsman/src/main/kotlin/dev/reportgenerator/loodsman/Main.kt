package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.orElse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("loodsman")

fun main(): Unit = runBlocking {
    val client = LoodsmanProductStructureClient(
        config = LoodsmanConfig(
            baseUrl = "https://impulsively-undamaged-amberjack.cloudpub.ru/",
            dbName = "loodsman",
            username = "Администратор",
            password = "Администратор"
        )
    )
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
            println("  Связь: ${usage.relation}, Kind: ${child.kind}, Позиция: ${usage.position.orElse("—")}, " +
                    "Количество: ${usage.quantity.orElse("—")}, Единица: ${usage.unit.orElse("—")}, " +
                    "Обозначение: ${child.designation.orElse("—")}, Наименование: ${child.name.orElse("—")}, " +
                    "version: ${child.version.orElse("—")}, state: ${child.state.orElse("—")}, " +
                    "Примечание: ${usage.attributes[LinkAttributeKey.Note].orElse("—")}, " +
                    "Наименование предприятия: ${child.attributes[AttributeKey.CompanyName].orElse("—")}")
        }
    } catch (e: LoodsmanApiException) {
        logger.error(e.message)
        logger.debug("Details", e)
    } finally {
        client.logout()
    }
}
