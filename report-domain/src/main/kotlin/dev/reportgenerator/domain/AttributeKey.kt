package dev.reportgenerator.domain

sealed interface AttributeKey {
    data object Mass : AttributeKey

    data object CompanyName: AttributeKey

    data object Designation : AttributeKey

    data object ProductDesignation : AttributeKey

    data object Name : AttributeKey

    data object Format: AttributeKey

    // Вид материала по ЕСКД (материалы).
    data object MaterialKind : AttributeKey

    // Обозначение стандарта (стандартные и прочие изделия).
    data object StandardDesignation : AttributeKey

    // Вид изделия (стандартные и прочие изделия).
    data object ProductVariety : AttributeKey

    // Типоразмер (стандартные и прочие изделия).
    data object SizeType : AttributeKey

    // Индекс заказчика (корневой объект отчёта; выводится в основную надпись с представителем заказчика).
    data object CustomerIndex : AttributeKey

    // Код документа (ведомость эксплуатационных документов: «ВС» — ведомость спецификаций).
    data object DocumentCode : AttributeKey
    data object SourceReceipt : AttributeKey
    data object ProductCode : AttributeKey
    data object AlternativeProductCode : AttributeKey
    data object SupplyDocument : AttributeKey
    data object Supplier : AttributeKey
    data object AdjustmentQuantity : AttributeKey
    data object DevelopedBy : AttributeKey
    data object LastModifiedNumber : AttributeKey
    data object PrimaryApplicability : AttributeKey
    data object Department : AttributeKey

    data class Unmapped(val pdmName: String) : AttributeKey
}
