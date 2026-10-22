package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.ItemKind
import dev.reportgenerator.domain.SpecificationData
import dev.reportgenerator.domain.SpecificationItem
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import kotlin.test.Test
import kotlin.test.assertEquals

// The `quantity` / `note` cells of the `body` table (format + cases in gost-spec.yaml).
// Expected texts are literals: MATERIAL keeps up to 2 decimals (HALF_UP, trailing zeros trimmed, comma separator),
// every other kind is a whole count (HALF_UP, no fractional part).
//
// Known, intentional differences from the pre-YAML code (none is reachable with Loodsman data: quantities are positive, finite):
//   * negative values: the old non-MATERIAL code used roundToLong (half toward +inf: -2.5 -> -2), the YAML
//     rounding is HALF_UP (away from zero: -2.5 -> -3);
//   * a MATERIAL value in (-0.005, 0): the old code gave "0" (BigDecimal has no negative zero), DecimalFormat gives "-0";
//   * NaN / Infinity: the old code gave "0" / Long.MAX_VALUE, the adapter's BigDecimal.valueOf throws;
//   * non-MATERIAL values above Long.MAX_VALUE: the old code saturated, the YAML prints the exact whole number.
// The tests below pin the expected texts and these differences, so any other change is a failure.
class SpecificationQuantityFormatTest {
    private val materialCases = listOf(
        0.0 to "0", 0.004 to "0", 0.005 to "0,01", 0.0049999 to "0", 0.01 to "0,01", 0.35 to "0,35", 0.349 to "0,35",
        0.351 to "0,35", 0.5 to "0,5", 0.4999 to "0,5", 1.0 to "1", 1.004 to "1", 1.005 to "1,01", 1.254 to "1,25",
        1.255 to "1,26", 1.5 to "1,5", 2.0 to "2", 2.5 to "2,5", 3.5 to "3,5", 6.4 to "6,4", 8.0 to "8", 9.995 to "10",
        10.255 to "10,26", 12.0 to "12", 99.999 to "100", 100.0 to "100", 1000.0 to "1000", 1234567.891 to "1234567,89",
        123456789012.0 to "123456789012", 1.0E15 to "1000000000000000", 0.1 + 0.2 to "0,3", 1.0 / 3.0 to "0,33",
        2.0 / 3.0 to "0,67",
    )

    private val wholeCases = listOf(
        0.0 to "0", 0.004 to "0", 0.005 to "0", 0.0049999 to "0", 0.01 to "0", 0.35 to "0", 0.349 to "0", 0.351 to "0",
        0.5 to "1", 0.4999 to "0", 1.0 to "1", 1.004 to "1", 1.005 to "1", 1.254 to "1", 1.255 to "1", 1.5 to "2",
        2.0 to "2", 2.5 to "3", 3.5 to "4", 6.4 to "6", 8.0 to "8", 9.995 to "10", 10.255 to "10", 12.0 to "12",
        99.999 to "100", 100.0 to "100", 1000.0 to "1000", 1234567.891 to "1234568", 123456789012.0 to "123456789012",
        1.0E15 to "1000000000000000", 0.1 + 0.2 to "0", 1.0 / 3.0 to "0", 2.0 / 3.0 to "1",
    )

    private fun item(kind: ItemKind, quantity: Double, unit: String? = null) = SpecificationItem("A.1", "Тест", kind, quantity, unit)

    private fun cells(item: SpecificationItem): List<String> {
        val table = specification(SpecificationData("X", "Y", listOf(item))).elements.filterIsInstance<IrTable>().single()
        return (table.content.single() as IrGroup).rows.single().cells.map { it.text }
    }

    private fun quantity(item: SpecificationItem) = cells(item)[5]

    @Test
    fun `material quantity keeps up to two decimals`() {
        for ((value, expected) in materialCases) assertEquals(expected, quantity(item(ItemKind.MATERIAL, value)), "MATERIAL $value")
    }

    @Test
    fun `every other kind is a whole count`() {
        for (kind in ItemKind.entries - ItemKind.DOCUMENTATION - ItemKind.MATERIAL) for ((value, expected) in wholeCases) {
            assertEquals(expected, quantity(item(kind, value)), "$kind $value")
        }
    }

    @Test
    fun `documentation has empty quantity and the new whole-count kinds round like parts`() {
        assertEquals("", quantity(item(ItemKind.DOCUMENTATION, 1.0)))
        assertEquals("", quantity(item(ItemKind.DOCUMENTATION, 0.0)))
        assertEquals("2", quantity(item(ItemKind.COMPLEX, 1.5)))
        assertEquals("3", quantity(item(ItemKind.SET, 3.0)))
    }

    @Test
    fun `legacy expectations of the old unit test`() {
        assertEquals("1,5", quantity(item(ItemKind.MATERIAL, 1.5)))
        assertEquals("1,25", quantity(item(ItemKind.MATERIAL, 1.254)))
        assertEquals("1,26", quantity(item(ItemKind.MATERIAL, 1.255)))
        assertEquals("3", quantity(item(ItemKind.MATERIAL, 3.0)))
        assertEquals("0", quantity(item(ItemKind.MATERIAL, 0.004)))
        assertEquals("0,01", quantity(item(ItemKind.MATERIAL, 0.005)))
        assertEquals("10,26", quantity(item(ItemKind.MATERIAL, 10.255)))
        assertEquals("1000", quantity(item(ItemKind.MATERIAL, 1000.0)))
        assertEquals("2", quantity(item(ItemKind.PART, 1.5)))
        assertEquals("5", quantity(item(ItemKind.ASSEMBLY, 5.0)))
        assertEquals("6", quantity(item(ItemKind.STANDARD, 6.4)))
        assertEquals("6", quantity(item(ItemKind.OTHER, 6.4)))
        assertEquals("0", quantity(item(ItemKind.PART, 0.4999)))
        assertEquals("1", quantity(item(ItemKind.PART, 0.5)))
    }

    @Test
    fun `intentional differences are limited to negative values`() {
        // negative non-material: HALF_UP away from zero (-2.5 -> -3), the old roundToLong gave -2
        assertEquals("-3", quantity(item(ItemKind.PART, -2.5)))
        // tiny negative material: "-0", the old code gave "0"
        assertEquals("-0", quantity(item(ItemKind.MATERIAL, -0.004)))
        // other negative finite values keep the material format
        assertEquals("-1,5", quantity(item(ItemKind.MATERIAL, -1.5)))
    }

    @Test
    fun `the unit is shown for materials only`() {
        assertEquals("кг", cells(item(ItemKind.MATERIAL, 1.0, "кг"))[6])
        assertEquals("", cells(item(ItemKind.MATERIAL, 1.0, null))[6])
        for (kind in ItemKind.entries - ItemKind.MATERIAL) assertEquals("", cells(item(kind, 1.0, "шт"))[6], "$kind")
    }
}
