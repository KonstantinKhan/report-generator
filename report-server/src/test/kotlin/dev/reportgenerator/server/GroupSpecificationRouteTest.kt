package dev.reportgenerator.server

import dev.reportgenerator.layout.DefaultFontRegistry
import io.github.konstantinkhan.loodsman.LoodsmanApiException
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

class GroupSpecificationRouteTest {
    private val tempDir: File = Files.createTempDirectory("report-server-group-test").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `group specification request builds the pdf from the loaded structure`() = testApplication {
        val structure = FakeProductStructureClient()
        application {
            reportServerModule(structure, tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/group-specifications/7")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"path\""))
        assertEquals(1, tempDir.listFiles()!!.count { it.extension == "pdf" })
        assertEquals(7, structure.queries.single().rootId.asInt())
        assertEquals(2, structure.queries.single().depth)
    }

    @Test
    fun `upstream loodsman error of the group specification maps to 502`() = testApplication {
        application {
            reportServerModule(FakeProductStructureClient(LoodsmanApiException("upstream down")), tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/group-specifications/7")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(0, tempDir.listFiles()!!.size)
    }

    @Test
    fun `a non-integer version id of the group specification is a bad request`() = testApplication {
        application {
            reportServerModule(FakeProductStructureClient(), tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/group-specifications/abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
