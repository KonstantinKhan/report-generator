package dev.reportgenerator.server

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.Quantity
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
            reportServerModule(structure, tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/specifications/7")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"path\""))
        assertEquals(1, tempDir.listFiles()!!.count { it.extension == "pdf" })
        assertEquals(7, structure.queries.single().rootId.asInt())
        assertEquals(1, structure.queries.single().depth)
    }

    @Test
    fun `upstream loodsman error maps to 502`() = testApplication {
        application {
            reportServerModule(FakeProductStructureClient(LoodsmanApiException("upstream down")), tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/specifications/7")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals(0, tempDir.listFiles()!!.size)
    }

    @Test
    fun `invalid product data maps to 502 with the data error message`() = testApplication {
        val root = PdmNode(
            id = PdmNodeId(1),
            kind = PdmNodeKind.Assembly,
            designation = Designation.Value("ИЗД.001"),
            name = Name.Value("Изделие"),
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ИЗД.001"),
                AttributeKey.Name to AttributeValue.Text("Изделие"),
            ),
            usages = listOf(
                CompositionUsage(
                    child = PdmNode(
                        PdmNodeId(2),
                        PdmNodeKind.Part,
                        Designation.Value("АБ.002"),
                        Name.Value("Вал"),
                        attributes = mapOf(
                            AttributeKey.Designation to AttributeValue.Text("АБ.002"),
                            AttributeKey.Name to AttributeValue.Text("Вал"),
                        ),
                    ),
                    position = Field.Missing,
                    quantity = Quantity.Missing,
                    unit = Field.Missing,
                ),
            ),
        )
        application {
            reportServerModule(FakeProductStructureClient(structure = root), tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/specifications/7")

        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertTrue(response.bodyAsText().contains("quantity is missing"))
        assertEquals(0, tempDir.listFiles()!!.size)
    }

    @Test
    fun `non-integer version id is rejected before loading the structure`() = testApplication {
        val structure = FakeProductStructureClient()
        application {
            reportServerModule(structure, tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        val response = client.post("/specifications/abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(structure.queries.isEmpty())
    }

    @Test
    fun `other exception maps to 500`() = testApplication {
        application {
            reportServerModule(FakeProductStructureClient(IllegalStateException("boom")), tempDir, DefaultFontRegistry.load(), FakeRemoteMetadata().cache())
        }

        assertEquals(HttpStatusCode.InternalServerError, client.post("/specifications/7").status)
    }
}
