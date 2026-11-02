package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.LinkAttributeKey
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class RegistriesTest : ShouldSpec({
    context("itemKinds registry") {
        should("contain known ProductKind variants") {
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Assembly)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Part)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Standard)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Other)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Material)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.AssemblyDrawing)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Complex)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Kit)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.Specification)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.DetailDrawing)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.MaterialForm)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.DocumentSet)
            Registries.itemKinds.values.shouldContain(PdmNodeKind.OperationalDocumentsList)
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
            Registries.relations.values.shouldContain(Relation.MadeFrom)
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
            Registries.attributeKeys.values.shouldContain(AttributeKey.Mass)
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

    context("linkAttributeKeys registry") {
        should("contain known LinkAttributeKey variants") {
            Registries.linkAttributeKeys.values.shouldContain(LinkAttributeKey.Note)
            Registries.linkAttributeKeys.values.shouldContain(LinkAttributeKey.Zone)
            Registries.linkAttributeKeys.values.shouldContain(LinkAttributeKey.ApplicationMethod)
            Registries.linkAttributeKeys.values.shouldContain(LinkAttributeKey.KitPurpose)
        }

        should("resolve the application method id of the mapping to its domain key") {
            Registries.linkAttributeKeys.resolve("applicationMethod") shouldBe LinkAttributeKey.ApplicationMethod
        }

        should("resolve the kit purpose id of the mapping to its domain key") {
            Registries.linkAttributeKeys.resolve("kitPurpose") shouldBe LinkAttributeKey.KitPurpose
        }

        should("throw error for unknown link attribute key") {
            try {
                Registries.linkAttributeKeys.resolve("unknown")
                throw AssertionError("Should have thrown IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                e.message!! shouldContain "Unknown linkAttributeKey"
            }
        }
    }
})
