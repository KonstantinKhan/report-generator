package dev.reportgenerator.reports.purchaseditems

import java.math.BigDecimal

enum class PurchasedItemGroup { BOLT, SCREW, RING, BEARING, OTHER, WASHER, KEY, DOCUMENT_REFERENCE }

fun purchasedItemGroup(name: String): PurchasedItemGroup = when (name.trim().substringBefore(' ').lowercase()) {
    "болт" -> PurchasedItemGroup.BOLT
    "винт" -> PurchasedItemGroup.SCREW
    "кольцо" -> PurchasedItemGroup.RING
    "подшипник" -> PurchasedItemGroup.BEARING
    "шайба" -> PurchasedItemGroup.WASHER
    "шпонка" -> PurchasedItemGroup.KEY
    else -> PurchasedItemGroup.OTHER
}

data class PurchasedItemsData(
    val documentDesignation: String,
    val documentName: String,
    val companyName: String? = null,
    val developedBy: String? = null,
    val primaryApplicability: String? = null,
    val version: String? = null,
    val state: String? = null,
    val lastModifiedNumber: String? = null,
    val rows: List<PurchasedItemRow>,
    val references: List<PurchasedDocumentReference> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class PurchasedItemRow(
    val itemId: Int,
    val group: PurchasedItemGroup,
    val name: String,
    val productCode: String? = null,
    val supplyDocument: String? = null,
    val supplier: String? = null,
    val inclusion: String,
    val unit: String,
    val quantityOnProduct: BigDecimal,
    val quantityInKits: BigDecimal,
    val quantityAdjustment: BigDecimal,
    val note: String? = null,
    val selectionRelated: Boolean = false,
) {
    val quantityTotal: BigDecimal get() = quantityOnProduct + quantityInKits + quantityAdjustment
}

data class PurchasedDocumentReference(
    val parentId: Int,
    val name: String,
    val documentDesignation: String,
)

class PurchasedItemsDataException(message: String) : IllegalArgumentException(message)
