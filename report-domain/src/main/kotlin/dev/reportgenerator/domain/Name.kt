package dev.reportgenerator.domain

sealed interface Name {
    @JvmInline
    value class Value(val text: String) : Name

    data object Missing : Name

    data object NotLoaded : Name
}

fun Name.orNull(): String? = when (this) {
    is Name.Value -> text
    is Name.Missing -> null
    is Name.NotLoaded -> null
}
