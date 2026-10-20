package dev.reportgenerator.server

import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.loodsman.LoodsmanApiException
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecificationRouteTest {
    private val tempDir: File = Files.createTempDirectory("report-server-spec-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `specification request builds the pdf from the loaded structure`() = testApplication {
        val structure = FakeProductStructureClient()
        application {
            reportServerModule(structure, tempDir, DefaultFontRegistry.load())
        }

        val response = client.post("/specifications/7")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"path\""))
        assertEquals(1, tempDir.listFiles()!!.count { it.extension == "pdf" })
        assertEquals("7", structure.queries.single().rootId.value)
        assertEquals(1, structure.queries.single().depth)
    }

    @Test
    fun `upstream loodsman error maps to 502`() = testApplication {
        application {
            reportServerModule(FakeProductStructureClient(LoodsmanApiException("upstream down")), tempDir, DefaultFontRegistry.load())
        }

        val response = client.post("/specifications/7")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(0, tempDir.listFiles()!!.size)
    }

    @Test
    fun `non-integer version id is rejected before loading the structure`() = testApplication {
        val structure = FakeProductStructureClient()
        application {
            reportServerModule(structure, tempDir, DefaultFontRegistry.load())
        }

        val response = client.post("/specifications/abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(structure.queries.isEmpty())
    }

    @Test
    fun `other exception maps to 500`() = testApplication {
        application {
            reportServerModule(FakeProductStructureClient(IllegalStateException("boom")), tempDir, DefaultFontRegistry.load())
        }

        assertEquals(HttpStatusCode.InternalServerError, client.post("/specifications/7").status)
    }
}
