package dev.reportgenerator.domain

sealed interface AttributeKey {
    data object Mass : AttributeKey

    data object CompanyName: AttributeKey

    data object Designation : AttributeKey

    data object Name : AttributeKey

    data object Format: AttributeKey

    data class Unmapped(val pdmName: String) : AttributeKey
}
