package dev.reportgenerator.domain

sealed interface Field<out T> {
    @JvmInline
    value class Value<T>(val value: T) : Field<T>

    data object Missing : Field<Nothing>

    data object NotLoaded : Field<Nothing>
}

fun <T> Field<T>.orNull(): T? = when (this) {
    is Field.Value -> value
    is Field.Missing -> null
    is Field.NotLoaded -> null
}

fun <T> Field<T>.orElse(default: T): T = when (this) {
    is Field.Value -> value
    is Field.Missing -> default
    is Field.NotLoaded -> default
}
