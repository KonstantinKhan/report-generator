package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import kotlin.test.Test
import kotlin.test.assertEquals

// The `quantity` / `note` cells of the `body` table (format + cases in gost-spec.yaml) against the legacy code
// formatter (legacyFormattedQuantity, the oracle), over a set of values and every kind.
//
// Known, intentional differences (none is reachable with Loodsman data: quantities are positive, finite):
//   * negative values: legacy non-MATERIAL used Kotlin roundToLong (half toward +inf: -2.5 -> -2), the YAML
//     rounding is HALF_UP (away from zero: -2.5 -> -3);
//   * a MATERIAL value in (-0.005, 0): legacy gave "0" (BigDecimal has no negative zero), DecimalFormat gives "-0";
//   * NaN / Infinity: legacy gave "0" / Long.MAX_VALUE, the adapter's BigDecimal.valueOf throws;
//   * non-MATERIAL values above Long.MAX_VALUE: legacy saturated, the YAML prints the exact whole number.
// The tests below pin the equal part and these differences, so any other change is a failure.
class SpecificationQuantityFormatTest {
    private val values = listOf(
        0.0, 0.004, 0.005, 0.0049999, 0.01, 0.35, 0.349, 0.351, 0.5, 0.4999, 1.0, 1.004, 1.005, 1.254, 1.255, 1.5, 2.0, 2.5, 3.5,
        6.4, 8.0, 9.995, 10.255, 12.0, 99.999, 100.0, 1000.0, 1234567.891, 123456789012.0, 1.0E15, 0.1 + 0.2, 1.0 / 3.0, 2.0 / 3.0
    )

    private fun item(kind: ItemKind, quantity: Double, unit: String? = null) = SpecificationItem("A.1", "Тест", kind, quantity, unit)

    private fun cells(item: SpecificationItem): List<String> {
        val table = specification(SpecificationData("X", "Y", listOf(item))).elements.filterIsInstance<IrTable>().single()
        return (table.content.single() as IrGroup).rows.single().cells.map { it.text }
    }

    private fun quantity(item: SpecificationItem) = cells(item)[5]

    @Test
    fun `yaml quantity format equals the legacy formatter for every kind`() {
        for (kind in ItemKind.entries) for (v in values) {
            val i = item(kind, v)
            assertEquals(i.legacyFormattedQuantity(), quantity(i), "$kind $v")
        }
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
    fun `intentional differences are limited to negative and non finite values`() {
        // negative non-material: half toward +inf (legacy) vs half up (yaml)
        assertEquals("-2", item(ItemKind.PART, -2.5).legacyFormattedQuantity())
        assertEquals("-3", quantity(item(ItemKind.PART, -2.5)))
        // negative zero of a tiny negative material
        assertEquals("0", item(ItemKind.MATERIAL, -0.004).legacyFormattedQuantity())
        assertEquals("-0", quantity(item(ItemKind.MATERIAL, -0.004)))
        // everything else negative and finite agrees
        assertEquals(item(ItemKind.MATERIAL, -1.5).legacyFormattedQuantity(), quantity(item(ItemKind.MATERIAL, -1.5)))
    }

    @Test
    fun `the unit is shown for materials only`() {
        assertEquals("кг", cells(item(ItemKind.MATERIAL, 1.0, "кг"))[6])
        assertEquals("", cells(item(ItemKind.MATERIAL, 1.0, null))[6])
        for (kind in ItemKind.entries - ItemKind.MATERIAL) assertEquals("", cells(item(kind, 1.0, "шт"))[6], "$kind")
    }
}
