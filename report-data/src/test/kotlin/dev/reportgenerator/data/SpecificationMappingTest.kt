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
                ItemDto("A.1", "Корпус", "ASSEMBLY", 1.0),
                ItemDto("A.2", "Вал", "PART", 2.0),
                ItemDto("ГОСТ 123", "Болт", "STANDARD", 5.0),
                ItemDto("A.4", "Прокладка", "OTHER", 6.0),
                ItemDto("A.3", "Шайба", "unknown-kind", 10.0)
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
    fun `maps new kinds`() {
        val dto = SpecificationDto(
            "A.0", "Изделие",
            listOf("DOCUMENTATION", "COMPLEX", "SOFTWARE", "SET").map { ItemDto("x", "y", it, 1.0) }
        )

        val kinds = mapToSpecificationData(dto).items.map { it.kind }

        assertEquals(listOf(ItemKind.DOCUMENTATION, ItemKind.COMPLEX, ItemKind.SOFTWARE, ItemKind.SET), kinds)
    }

    @Test
    fun `carries document designation and name through unchanged`() {
        val dto = SpecificationDto(documentDesignation = "X.001", documentName = "Тест", items = emptyList())
        val data = mapToSpecificationData(dto)

        assertEquals("X.001", data.documentDesignation)
        assertEquals("Тест", data.documentName)
    }

    @Test
    fun `carries fractional quantity and unit through unchanged`() {
        val dto = SpecificationDto(
            documentDesignation = "A.0",
            documentName = "Изделие",
            items = listOf(ItemDto("A.5", "Клей", "MATERIAL", 1.5, "кг"))
        )

        val data = mapToSpecificationData(dto)

        assertEquals(1.5, data.items[0].quantity)
        assertEquals("кг", data.items[0].unit)
    }
}
