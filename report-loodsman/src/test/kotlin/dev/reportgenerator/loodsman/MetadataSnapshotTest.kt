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

    should("find link type id by exact name") {
        snapshot.linkTypeId("Состоит из ...") shouldBe 1
        snapshot.linkTypeId("Документы") shouldBe 2
    }

    should("not find link type id when case, whitespace differ") {
        snapshot.linkTypeId("  Состоит из ...  ") shouldBe null
        snapshot.linkTypeId("  СОСТОИТ   из ...  ") shouldBe null
        snapshot.linkTypeId("документы") shouldBe null
    }

    should("not find link type id when ё is replaced by е") {
        val withYo = MetadataSnapshot(
            linkTypes = listOf(LinkTypeInfo(id = 3, name = "Ёмкость связи")),
            objectTypes = emptyList(),
            attributeNames = emptySet(),
            version = 1,
        )
        withYo.linkTypeId("Ёмкость связи") shouldBe 3
        withYo.linkTypeId("Емкость связи") shouldBe null
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

        should("return key attribute only for the exact type name") {
            withTypes.keyAttributeOf("Деталь") shouldBe "Обозначение"
            withTypes.keyAttributeOf("Комплект") shouldBe "GID"
        }

        should("return null when case, ё or whitespace of the type name differ") {
            withTypes.keyAttributeOf("  Деталь ") shouldBe null
            withTypes.keyAttributeOf("ДЕТАЛЬ") shouldBe null
            withTypes.keyAttributeOf("Елка") shouldBe null
            withTypes.keyAttributeOf("комплект") shouldBe null
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

    context("linkDirections") {
        val withTypeLinks = MetadataSnapshot(
            linkTypes = listOf(LinkTypeInfo(id = 10, name = "Технологическая ДСЕ для")),
            objectTypes = listOf(
                ObjectTypeInfo(id = 1, typeName = "Деталь", keyAttribute = "Обозначение"),
                ObjectTypeInfo(id = 2, typeName = "Комплект", keyAttribute = "Обозначение"),
            ),
            attributeNames = emptySet(),
            version = 1,
            typeLinks = listOf(
                TypeLinkInfo(typeId1 = 1, typeId2 = 3, idLinkType = 10, direction = -1),
                TypeLinkInfo(typeId1 = 1, typeId2 = 4, idLinkType = 10, direction = 1),
                TypeLinkInfo(typeId1 = 2, typeId2 = 3, idLinkType = 11, direction = 0),
            ),
        )

        should("return all directions of rows for the type name and link type id") {
            withTypeLinks.linkDirections("Деталь", 10) shouldBe setOf(-1, 1)
        }

        should("return empty set when no row matches the type or the link type") {
            withTypeLinks.linkDirections("Комплект", 10) shouldBe emptySet()
            withTypeLinks.linkDirections("Деталь", 11) shouldBe emptySet()
            withTypeLinks.linkDirections("Неизвестный", 10) shouldBe emptySet()
        }

        should("match type name exactly") {
            withTypeLinks.linkDirections("деталь", 10) shouldBe emptySet()
        }
    }

    should("return sorted distinct ids of the given names and skip names missing in the snapshot") {
        val withIds = MetadataSnapshot(
            linkTypes = emptyList(),
            objectTypes = emptyList(),
            attributeNames = setOf("Примечание", "Позиция"),
            version = 1,
            attributeIds = mapOf("Примечание" to listOf(21, 12), "Позиция" to listOf(11)),
        )

        withIds.attributeIdsOf(listOf("Примечание", "Позиция", "Зона")) shouldBe listOf(11, 12, 21)
        withIds.attributeIdsOf(listOf("Зона")) shouldBe emptyList()
    }
})
