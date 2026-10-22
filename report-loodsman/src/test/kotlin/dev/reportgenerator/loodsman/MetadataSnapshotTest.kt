package dev.reportgenerator.loodsman

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class MetadataSnapshotTest : ShouldSpec({

    val snapshot = MetadataSnapshot(
        linkTypes = listOf(
            LinkTypeInfo(id = 1, name = "Состоит из ..."),
            LinkTypeInfo(id = 2, name = "Документы"),
        ),
        objectTypes = emptyList(),
        attributeNames = emptySet(),
        version = 1,
    )

    should("find link type id ignoring case and extra whitespace") {
        snapshot.linkTypeId("  СОСТОИТ   из ...  ") shouldBe 1
        snapshot.linkTypeId("документы") shouldBe 2
    }

    should("find link type id ignoring ё") {
        val withYo = MetadataSnapshot(
            linkTypes = listOf(LinkTypeInfo(id = 3, name = "Ёмкость связи")),
            objectTypes = emptyList(),
            attributeNames = emptySet(),
            version = 1,
        )
        withYo.linkTypeId("емкость связи") shouldBe 3
    }

    should("return null for an unknown link type") {
        snapshot.linkTypeId("Неизвестная связь") shouldBe null
    }

    context("keyAttributeOf") {
        val withTypes = MetadataSnapshot(
            linkTypes = emptyList(),
            objectTypes = listOf(
                ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "Обозначение"),
                ObjectTypeInfo(id = 2, typeName = "Ёлка", keyAttribute = "Обозначение"),
                ObjectTypeInfo(id = 3, typeName = "Комплект", keyAttribute = "GID"),
                ObjectTypeInfo(id = 4, typeName = "Материал по КД", keyAttribute = null),
                ObjectTypeInfo(id = 5, typeName = null, keyAttribute = "Наименование"),
            ),
            attributeNames = emptySet(),
            version = 1,
        )

        should("return key attribute ignoring case, ё and extra whitespace of the type name") {
            withTypes.keyAttributeOf("  ДЕТАЛЬ ") shouldBe "Обозначение"
            withTypes.keyAttributeOf("елка") shouldBe "Обозначение"
            withTypes.keyAttributeOf("комплект") shouldBe "GID"
        }

        should("return null for an unknown type") {
            withTypes.keyAttributeOf("Неизвестный тип") shouldBe null
        }

        should("return null when the key attribute is not set for the type") {
            withTypes.keyAttributeOf("Материал по КД") shouldBe null
        }

        should("not match a type with a null name") {
            withTypes.keyAttributeOf("") shouldBe null
        }
    }
})
