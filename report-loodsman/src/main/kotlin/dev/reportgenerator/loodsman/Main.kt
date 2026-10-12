package dev.reportgenerator.loodsman

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("loodsman")

fun main(): Unit = runBlocking {
    val loodsmanClient = LoodsmanKtorPdmClient(
        config = LoodsmanConfig(
            baseUrl = "https://impulsively-undamaged-amberjack.cloudpub.ru/",
            dbName = "loodsman",
            username = "Администратор",
            password = "Администратор"
        ),
    )
    try {
        println("Пользователь является админом: ${loodsmanClient.isAdmin()}")
    } catch (e: LoodsmanApiException) {
        logger.error(e.message)
        logger.debug("Details", e)
    }
}
