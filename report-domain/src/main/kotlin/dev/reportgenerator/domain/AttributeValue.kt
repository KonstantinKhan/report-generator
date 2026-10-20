package dev.reportgenerator.domain

sealed interface AttributeValue {
    @JvmInline
    value class Text(val value: String) : AttributeValue

    @JvmInline
    value class Number(val value: Double) : AttributeValue

    data object Empty : AttributeValue
}

fun AttributeValue?.orElse(default: String): String = when (this) {
    is AttributeValue.Text -> value
    is AttributeValue.Number -> value.toString()
    AttributeValue.Empty -> default
    null -> default
}
