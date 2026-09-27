package dev.reportgenerator.server

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.PdmClient
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.layout.DefaultFontRegistry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
}
