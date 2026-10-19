package dev.reportgenerator.domain

sealed interface AttributeKey {
    data object Mass : AttributeKey

    data object Material : AttributeKey

    data class Unmapped(val pdmName: String) : AttributeKey
}
