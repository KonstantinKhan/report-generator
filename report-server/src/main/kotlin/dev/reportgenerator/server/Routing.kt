package dev.reportgenerator.server

import dev.reportgenerator.api.PdmClient
import dev.reportgenerator.data.mapToSpecificationData
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.loodsman.LoodsmanApiException
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.reports.specification.specification
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.util.UUID

@Serializable
data class SpecificationResponse(val id: String, val path: String)

@Serializable
data class ErrorResponse(val error: String)

fun Application.reportServerModule(
    pdmClient: PdmClient,
    outputDir: File,
    fonts: DefaultFontRegistry,
) {
    install(ContentNegotiation) {
        json()
    }

    install(StatusPages) {
        // report-loodsman throws LoodsmanApiException for upstream Loodsman failures (document
        // not found, upstream unavailable, etc). Map it to 502 Bad Gateway.
        exception<LoodsmanApiException> { call, cause ->
            call.application.log.error("Upstream Loodsman error", cause)
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "upstream error"))
        }
        // Fallback for network-level failures not wrapped in LoodsmanApiException.
        exception<IOException> { call, cause ->
            call.application.log.error("Upstream I/O error", cause)
            call.respond(HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "upstream error"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error while processing request", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error"))
        }
    }

    val textMeasurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    routing {
        get("/health") {
            call.respondText("OK")
        }

        post("/specifications/{versionId}") {
            val versionId = call.parameters["versionId"]?.toIntOrNull()
            if (versionId == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("versionId must be an integer"))
                return@post
            }

            val customerRepresentative = call.parameters["customerRepresentative"]?.toBooleanStrictOrNull() ?: true

            val id = UUID.randomUUID().toString()
            val file = withContext(Dispatchers.IO) {
                val dto = pdmClient.fetchSpecification(versionId.toString())

                val data = mapToSpecificationData(dto)
                val document = specification(data, customerRepresentative = customerRepresentative)
                val laidOut = layOut(document, textMeasurer, fonts::resolve)
                val pdfBytes = renderToPdf(laidOut, fonts.registry)

                outputDir.mkdirs()
                File(outputDir, "$id.pdf").apply { writeBytes(pdfBytes) }
            }

            call.respond(HttpStatusCode.OK, SpecificationResponse(id = id, path = file.absolutePath))
        }
    }
}
