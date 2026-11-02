package dev.reportgenerator.domain

sealed interface Relation {
    data object Composition : Relation

    data object Document : Relation

    data object TechnologicalPart : Relation

    data object MadeFrom : Relation

    /** Исполнение изделия: связь от основного исполнения к остальным (обратное имя «Базовое исполнение»). */
    data object Variant : Relation

    data class Unmapped(val pdmName: String) : Relation
}
