package dev.reportgenerator.domain

sealed interface Field<out T> {
    @JvmInline
    value class Value<T>(val value: T) : Field<T>

    data object Missing : Field<Nothing>

    data object NotLoaded : Field<Nothing>
}

fun <T> Field<T>.orElse(default: T): T = when (this) {
    is Field.Value -> value
    is Field.Missing -> default
    is Field.NotLoaded -> throw IllegalStateException(
        "Field is not loaded: the value was not requested from the PDM client"
    )
}
