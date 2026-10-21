package dev.reportgenerator.domain

sealed interface PdmNodeKind {
    data object Assembly : PdmNodeKind

    data object Part : PdmNodeKind

    data object Standard : PdmNodeKind

    data object Other : PdmNodeKind

    data object Material : PdmNodeKind

    data object AssemblyDrawing : PdmNodeKind

    data object Complex : PdmNodeKind

    data object Kit : PdmNodeKind

    data class Unmapped(val pdmName: String) : PdmNodeKind
}
