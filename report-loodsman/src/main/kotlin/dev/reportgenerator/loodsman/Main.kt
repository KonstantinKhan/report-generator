package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.ProductId
import dev.reportgenerator.domain.StructureQuery
import dev.reportgenerator.domain.orNull
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
                depth = 1,
                attributes = setOf(AttributeKey.Version, AttributeKey.State)
            )
        )

        println("Корень: ${root.designation.orNull()} / ${root.name.orNull()}")
        root.usages.forEach { usage ->
            val child = usage.child
            println("  Связь: ${usage.relation}, Kind: ${child.kind}, Позиция: ${usage.position}, " +
                    "Количество: ${usage.quantity}, Единица: ${usage.unit}, " +
                    "Обозначение: ${child.designation.orNull()}, Наименование: ${child.name.orNull()}")
        }
    } catch (e: LoodsmanApiException) {
        logger.error(e.message)
        logger.debug("Details", e)
    } finally {
        client.logout()
    }
}
