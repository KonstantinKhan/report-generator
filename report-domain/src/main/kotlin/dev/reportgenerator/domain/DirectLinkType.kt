package dev.reportgenerator.domain

sealed interface DirectLinkType {
    data object Composition : DirectLinkType
    data class Unused(val value: String) : DirectLinkType
}

fun directLinkType(name: String?): DirectLinkType =
    name?.trim()?.takeIf { it.isNotEmpty() }?.let {
        when (it) {
            "Состоит из ..." -> DirectLinkType.Composition
            else -> DirectLinkType.Unused(it)
        }
    } ?: throw IllegalArgumentException("directLinkType cannot be null")
