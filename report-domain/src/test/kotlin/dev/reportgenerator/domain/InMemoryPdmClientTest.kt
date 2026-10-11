package dev.reportgenerator.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryPdmClientTest {

    @Test
    fun `returns exactly the data it was constructed with`() {
        val data = SpecificationData(
            documentDesignation = "A.0",
            documentName = "Изделие",
            items = listOf(SpecificationItem("A.1", "Деталь", ItemKind.PART, 3.0))
        )
        val client = InMemoryPdmClient(data)

        assertEquals(data, client.fetchSpecification("any-id"))
    }
}
