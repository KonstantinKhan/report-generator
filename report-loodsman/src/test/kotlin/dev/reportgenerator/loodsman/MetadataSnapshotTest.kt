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
})
