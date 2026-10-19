package dev.reportgenerator.domain

sealed interface Designation {
    @JvmInline
    value class Value(val text: String) : Designation

    data object Missing : Designation

    data object NotLoaded : Designation
}

fun Designation.orElse(default: String): String = when (this) {
    is Designation.Value -> text
    is Designation.Missing -> default
    is Designation.NotLoaded -> throw IllegalStateException(
        "Designation is not loaded: the value was not requested from the PDM client"
    )
}
