package dev.reportgenerator.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SpecificationDataTest {

    private fun item(kind: ItemKind, quantity: Double, unit: String? = null) =
        SpecificationItem(designation = "A.1", name = "Тест", kind = kind, quantity = quantity, unit = unit)

    @Test
    fun `material quantity keeps up to 2 decimals with comma separator`() {
        assertEquals("1,5", item(ItemKind.MATERIAL, 1.5).formattedQuantity())
        assertEquals("1,25", item(ItemKind.MATERIAL, 1.254).formattedQuantity())
        assertEquals("1,26", item(ItemKind.MATERIAL, 1.255).formattedQuantity())
    }

    @Test
    fun `material quantity with no fractional part is shown without decimals`() {
        assertEquals("3", item(ItemKind.MATERIAL, 3.0).formattedQuantity())
    }

    @Test
    fun `non-material kinds round to a whole number regardless of decimals`() {
        assertEquals("2", item(ItemKind.PART, 1.5).formattedQuantity())
        assertEquals("5", item(ItemKind.ASSEMBLY, 5.0).formattedQuantity())
        assertEquals("6", item(ItemKind.STANDARD, 6.4).formattedQuantity())
        assertEquals("6", item(ItemKind.OTHER, 6.4).formattedQuantity())
    }
}
