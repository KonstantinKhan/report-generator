package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation

object Registries {
    val itemKinds = Registry(
        "itemKind",
        mapOf(
            "ASSEMBLY" to PdmNodeKind.Assembly,
            "PART" to PdmNodeKind.Part,
            "STANDARD" to PdmNodeKind.Standard,
            "OTHER" to PdmNodeKind.Other,
            "MATERIAL" to PdmNodeKind.Material,
            "ASSEMBLY_DRAWING" to PdmNodeKind.AssemblyDrawing,
            "COMPLEX" to PdmNodeKind.Complex,
            "KIT" to PdmNodeKind.Kit
        )
    )

    val relations = Registry(
        "relation",
        mapOf(
            "COMPOSITION" to Relation.Composition,
            "DOCUMENT" to Relation.Document
        )
    )

    val attributeKeys = Registry(
        "attributeKey",
        mapOf(
            "mass" to AttributeKey.Mass,
            "companyName" to AttributeKey.CompanyName
        )
    )

    val linkAttributeKeys = Registry<LinkAttributeKey>(
        "linkAttributeKey",
        mapOf(
            "position" to LinkAttributeKey.Position,
            "note" to LinkAttributeKey.Note
        )
    )
}
