package dev.reportgenerator.data

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

enum class ItemKind { ASSEMBLY, PART, STANDARD, OTHER, MATERIAL }

data class SpecificationItem(
    val designation: String?,
    val name: String,
    val kind: ItemKind,
    val quantity: Double,
    val unit: String? = null
)

data class SpecificationData(
    val documentDesignation: String,
    val documentName: String,
    val items: List<SpecificationItem>
)

// MATERIAL quantities can be fractional (up to 2 decimals); every other kind is always
// a whole count in Loodsman, so it's rounded and shown without a fractional part.
fun SpecificationItem.formattedQuantity(): String = when (kind) {
    ItemKind.MATERIAL -> formatMaterialQuantity(quantity)
    else -> quantity.roundToLong().toString()
}

private fun formatMaterialQuantity(value: Double): String {
    val plain = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
    val trimmed = if (plain.contains('.')) plain.trimEnd('0').trimEnd('.') else plain
    return trimmed.replace('.', ',')
}
