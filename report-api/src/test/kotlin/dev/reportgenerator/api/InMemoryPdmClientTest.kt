package dev.reportgenerator.api

import kotlin.test.Test
import kotlin.test.assertEquals

class InMemoryPdmClientTest {

    @Test
    fun `returns exactly the data it was constructed with`() {
        val dto = SpecificationDto(
            documentDesignation = "A.0",
            documentName = "Изделие",
            items = listOf(ItemDto("A.1", "Деталь", "PART", 3))
        )
        val client = InMemoryPdmClient(dto)

        assertEquals(dto, client.fetchSpecification("any-id"))
    }
}
