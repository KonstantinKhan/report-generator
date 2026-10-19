package dev.reportgenerator.domain

sealed interface AttributeKey {
    data object Version : AttributeKey

    data object State : AttributeKey

    data object Mass : AttributeKey

    data object Material : AttributeKey

    data class Unmapped(val pdmName: String) : AttributeKey
}
