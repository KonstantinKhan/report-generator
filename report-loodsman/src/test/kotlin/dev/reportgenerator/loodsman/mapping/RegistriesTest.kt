package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.ProductKind
import dev.reportgenerator.domain.Relation
import dev.reportgenerator.domain.AttributeKey
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain

class RegistriesTest : ShouldSpec({
    context("itemKinds registry") {
        should("contain known ProductKind variants") {
            Registries.itemKinds.values.shouldContain(ProductKind.Assembly)
            Registries.itemKinds.values.shouldContain(ProductKind.Part)
            Registries.itemKinds.values.shouldContain(ProductKind.Standard)
            Registries.itemKinds.values.shouldContain(ProductKind.Other)
            Registries.itemKinds.values.shouldContain(ProductKind.Material)
            Registries.itemKinds.values.shouldContain(ProductKind.Document)
            Registries.itemKinds.values.shouldContain(ProductKind.Complex)
            Registries.itemKinds.values.shouldContain(ProductKind.Kit)
        }

        should("throw error for unknown item kind") {
            try {
                Registries.itemKinds.resolve("PRAT")
                throw AssertionError("Should have thrown IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                e.message!! shouldContain "Unknown itemKind"
                e.message!! shouldContain "Known:"
            }
        }
    }

    context("relations registry") {
        should("contain known Relation variants") {
            Registries.relations.values.shouldContain(Relation.Composition)
            Registries.relations.values.shouldContain(Relation.Document)
        }

        should("throw error for unknown relation") {
            try {
                Registries.relations.resolve("INVALID")
                throw AssertionError("Should have thrown IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                e.message!! shouldContain "Unknown relation"
            }
        }
    }

    context("attributeKeys registry") {
        should("contain known AttributeKey variants") {
            Registries.attributeKeys.values.shouldContain(AttributeKey.Version)
            Registries.attributeKeys.values.shouldContain(AttributeKey.State)
            Registries.attributeKeys.values.shouldContain(AttributeKey.Mass)
            Registries.attributeKeys.values.shouldContain(AttributeKey.Material)
        }

        should("throw error for unknown attribute key") {
            try {
                Registries.attributeKeys.resolve("unknown")
                throw AssertionError("Should have thrown IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                e.message!! shouldContain "Unknown attributeKey"
            }
        }
    }
})
