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

    data object Specification: PdmNodeKind

    data object DetailDrawing: PdmNodeKind

    data object MaterialForm : PdmNodeKind

    data object DocumentSet : PdmNodeKind

    data object OperationalDocumentsList : PdmNodeKind

    data class Unmapped(val pdmName: String) : PdmNodeKind
}
