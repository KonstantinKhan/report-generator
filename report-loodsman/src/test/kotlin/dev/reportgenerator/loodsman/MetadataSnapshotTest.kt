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
    )

    should("find link type id ignoring case and extra whitespace") {
        snapshot.linkTypeId("  СОСТОИТ   из ...  ") shouldBe 1
        snapshot.linkTypeId("документы") shouldBe 2
    }

    should("return null for an unknown link type") {
        snapshot.linkTypeId("Неизвестная связь") shouldBe null
    }
})
