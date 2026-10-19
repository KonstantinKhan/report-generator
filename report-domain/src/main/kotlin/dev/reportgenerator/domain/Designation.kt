package dev.reportgenerator.domain

sealed interface Designation {
    @JvmInline
    value class Value(val text: String) : Designation

    data object Missing : Designation

    data object NotLoaded : Designation
}

fun Designation.orNull(): String? = when (this) {
    is Designation.Value -> text
    is Designation.Missing -> null
    is Designation.NotLoaded -> null
}
