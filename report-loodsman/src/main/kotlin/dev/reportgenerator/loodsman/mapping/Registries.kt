package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.AttributeKey

object Registries {
    val itemKinds = Registry(
        "itemKind",
        mapOf(
            "ASSEMBLY" to ProductKind.Assembly,
            "PART" to ProductKind.Part,
            "STANDARD" to ProductKind.Standard,
            "OTHER" to ProductKind.Other,
            "MATERIAL" to ProductKind.Material,
            "DOCUMENT" to ProductKind.Document,
            "COMPLEX" to ProductKind.Complex,
            "KIT" to ProductKind.Kit
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
            "version" to AttributeKey.Version,
            "state" to AttributeKey.State,
            "mass" to AttributeKey.Mass,
            "material" to AttributeKey.Material
        )
    )
}
