package dev.reportgenerator.server

import dev.reportgenerator.domain.ProductStructureClient
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.loodsman.KtorLoodsmanApi
import dev.reportgenerator.loodsman.LoodsmanConfig
import dev.reportgenerator.loodsman.LoodsmanProductStructureClient
import dev.reportgenerator.loodsman.loodsmanHttpClient
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() {
    // AppConfig's ApplicationEnvironment constructor fails fast with a clear message if required
    // config (Loodsman credentials) is missing, before the engine binds its port.
    val config = AppConfig(applicationEnvironment {})

    val loodsmanApi = KtorLoodsmanApi(
        LoodsmanConfig(
            baseUrl = config.loodsmanBaseUrl,
            dbName = config.loodsmanDbName,
            username = config.loodsmanUsername,
            password = config.loodsmanPassword,
        ),
        loodsmanHttpClient(),
    )
    val structureClient: ProductStructureClient = LoodsmanProductStructureClient(loodsmanApi)

    val outputDir = File(config.outputDir).apply { mkdirs() }
    val fonts = DefaultFontRegistry.load()

    embeddedServer(
        factory = Netty,
        port = config.serverPort,
        module = {
            reportServerModule(structureClient, outputDir, fonts)
            // Closes the Loodsman session on shutdown. A failed logout must not break the stop.
            monitor.subscribe(ApplicationStopped) {
                runBlocking {
                    try {
                        loodsmanApi.logout()
                    } catch (e: Exception) {
                        log.warn("Loodsman logout failed on shutdown", e)
                    }
                }
            }
        },
    ).start(wait = true)
}
