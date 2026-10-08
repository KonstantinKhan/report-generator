package dev.reportgenerator.server

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.PdmClient
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.layout.DefaultFontRegistry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.readBytes
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class StubPdmClient : PdmClient {
    override fun fetchSpecification(documentId: String): SpecificationDto =
        SpecificationDto(
            documentDesignation = "AAA.00.000",
            documentName = "Test",
            items = listOf(ItemDto("AAA.01.000", "Part", "PART", 1.0)),
        )
}

class HealthCheckTest {
    private val tempDir: File = Files.createTempDirectory("report-server-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `health endpoint returns OK`() = testApplication {
        application {
            reportServerModule(StubPdmClient(), tempDir, DefaultFontRegistry.load())
        }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `report download returns generated pdf`() = testApplication {
        application {
            reportServerModule(StubPdmClient(), tempDir, DefaultFontRegistry.load())
        }
        val id = "123e4567-e89b-12d3-a456-426614174000"
        File(tempDir, "$id.pdf").writeBytes("%PDF-test".toByteArray())

        val response = client.get("/reports/$id")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Pdf, response.contentType()?.withoutParameters())
        assertEquals("%PDF-test", String(response.readBytes()))
    }

    @Test
    fun `report download rejects non-uuid id and unknown id`() = testApplication {
        application {
            reportServerModule(StubPdmClient(), tempDir, DefaultFontRegistry.load())
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/reports/not-a-uuid").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/reports/123e4567-e89b-12d3-a456-426614174000").status)
    }
}
