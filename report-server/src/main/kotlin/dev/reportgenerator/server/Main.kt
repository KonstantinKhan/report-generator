package dev.reportgenerator.server

import dev.reportgenerator.api.PdmClient
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.loodsman.LoodsmanConfig
import dev.reportgenerator.loodsman.LoodsmanPdmClient
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

fun main() {
    // AppConfig's ApplicationEnvironment constructor fails fast with a clear message if required
    // config (Loodsman credentials) is missing, before the engine binds its port.
    val config = AppConfig(applicationEnvironment {})

    val pdmClient: PdmClient = LoodsmanPdmClient(
        LoodsmanConfig(
            baseUrl = config.loodsmanBaseUrl,
            dbName = config.loodsmanDbName,
            username = config.loodsmanUsername,
            password = config.loodsmanPassword,
        )
    )

    val outputDir = File(config.outputDir).apply { mkdirs() }
    val fonts = DefaultFontRegistry.load()

    embeddedServer(
        factory = Netty,
        port = config.serverPort,
        module = { reportServerModule(pdmClient, outputDir, fonts) },
    ).start(wait = true)
}
