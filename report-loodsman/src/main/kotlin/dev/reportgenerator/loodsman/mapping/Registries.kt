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
            "KIT" to PdmNodeKind.Kit,
            "SPECIFICATION" to PdmNodeKind.Specification,
            "DETAIL_DRAWING" to PdmNodeKind.DetailDrawing,
            "MATERIAL_FORM" to PdmNodeKind.MaterialForm,
        )
    )

    val relations = Registry(
        "relation",
        mapOf(
            "COMPOSITION" to Relation.Composition,
            "DOCUMENT" to Relation.Document,
            "TECHNOLOGICAL_PART" to Relation.TechnologicalPart,
            "MADE_FROM" to Relation.MadeFrom,
            "VARIANT" to Relation.Variant,
        )
    )

    val attributeKeys = Registry(
        "attributeKey",
        mapOf(
            "mass" to AttributeKey.Mass,
            "companyName" to AttributeKey.CompanyName,
            "designation" to AttributeKey.Designation,
            "productDesignation" to AttributeKey.ProductDesignation,
            "name" to AttributeKey.Name,
            "format" to AttributeKey.Format
        )
    )

    val linkAttributeKeys = Registry<LinkAttributeKey>(
        "linkAttributeKey",
        mapOf(
            "position" to LinkAttributeKey.Position,
            "note" to LinkAttributeKey.Note,
            "zone" to LinkAttributeKey.Zone,
            "applicationMethod" to LinkAttributeKey.ApplicationMethod,
            "kitPurpose" to LinkAttributeKey.KitPurpose
        )
    )
}
