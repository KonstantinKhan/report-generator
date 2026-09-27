package dev.reportgenerator.data

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.SpecificationDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecificationMappingTest {

    @Test
    fun `maps DTO items to typed ItemKind by kind string`() {
        val dto = SpecificationDto(
            documentDesignation = "A.0",
            documentName = "Изделие",
            items = listOf(
                ItemDto("A.1", "Корпус", "ASSEMBLY", 1),
                ItemDto("A.2", "Вал", "PART", 2),
                ItemDto("ГОСТ 123", "Болт", "STANDARD", 5),
                ItemDto("A.4", "Прокладка", "OTHER", 6),
                ItemDto("A.3", "Шайба", "unknown-kind", 10)
            )
        )

        val data = mapToSpecificationData(dto)

        assertEquals(ItemKind.ASSEMBLY, data.items[0].kind)
        assertEquals(ItemKind.PART, data.items[1].kind)
        assertEquals(ItemKind.STANDARD, data.items[2].kind)
        assertEquals(ItemKind.OTHER, data.items[3].kind)
        assertEquals(ItemKind.PART, data.items[4].kind, "unknown kind strings default to PART")
    }

    @Test
    fun `carries document designation and name through unchanged`() {
        val dto = SpecificationDto(documentDesignation = "X.001", documentName = "Тест", items = emptyList())
        val data = mapToSpecificationData(dto)

        assertEquals("X.001", data.documentDesignation)
        assertEquals("Тест", data.documentName)
    }
}
