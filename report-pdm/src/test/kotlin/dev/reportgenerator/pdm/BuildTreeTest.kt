package dev.reportgenerator.pdm

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import dev.reportgenerator.domain.Assembly
import dev.reportgenerator.domain.Component
import dev.reportgenerator.domain.Components
import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.DirectLink
import dev.reportgenerator.domain.DirectLinkType
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.InMemoryPdmClient
import dev.reportgenerator.domain.Name
import dev.reportgenerator.domain.Part
import dev.reportgenerator.domain.PdmClient
import dev.reportgenerator.domain.PdmObject
import dev.reportgenerator.domain.PdmObjectResult
import dev.reportgenerator.domain.PdmType
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.SpecificationData
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue


class BuildTreeTest : ShouldSpec({

    should("build assembly with detail and nested assembly (2 levels)") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detailObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Value(1)
        )
        val nestedAssemblyObj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Nested Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Value(2)
        )
        val nestedDetailObj = PdmObject(
            id = 4,
            designation = Designation.Value("A.4"),
            name = Name.Value("Nested Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Value(1)
        )

        val objects = mapOf(
            1 to rootObj,
            2 to detailObj,
            3 to nestedAssemblyObj,
            4 to nestedDetailObj
        )
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Value("шт")),
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(2.0), unit = Field.Missing)
            ),
            3 to listOf(
                DirectLink(linkId = 12, childId = 4, type = DirectLinkType.Composition, quantity = Quantity.Exact(3.0), unit = Field.Value("шт"))
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result shouldBe Assembly(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root Assembly"),
            state = "active",
            version = "1.0",
            components = Components.Loaded(
                parts = listOf(
                    Component(
                        item = Part(2, Designation.Value("A.2"), Name.Value("Detail"), "active", "1.0"),
                        quantity = Quantity.Exact(1.0),
                        unit = Field.Value("шт"),
                        position = Field.Value(1)
                    )
                ),
                assemblies = listOf(
                    Component(
                        item = Assembly(
                            id = 3,
                            designation = Designation.Value("A.3"),
                            name = Name.Value("Nested Assembly"),
                            state = "active",
                            version = "1.0",
                            components = Components.Loaded(
                                parts = listOf(
                                    Component(
                                        item = Part(4, Designation.Value("A.4"), Name.Value("Nested Detail"), "active", "1.0"),
                                        quantity = Quantity.Exact(3.0),
                                        unit = Field.Value("шт"),
                                        position = Field.Value(1)
                                    )
                                ),
                                assemblies = emptyList(),
                                standardProducts = emptyList(),
                                otherProducts = emptyList()
                            )
                        ),
                        quantity = Quantity.Exact(2.0),
                        unit = Field.Missing,
                        position = Field.Value(2)
                    )
                ),
                standardProducts = emptyList(),
                otherProducts = emptyList()
            )
        )
    }

    should("take quantity and unit from link, position from child object") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detailObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Value(5)
        )

        val objects = mapOf(1 to rootObj, 2 to detailObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(2.5), unit = Field.Value("кг"))
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.parts shouldHaveSize 1
        result.parts[0].quantity shouldBe Quantity.Exact(2.5)
        result.parts[0].unit shouldBe Field.Value("кг")
        result.parts[0].position shouldBe Field.Value(5)
    }

    should("allow same detail in two assemblies with different quantities") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly 1"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detailObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val assembly2Obj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Assembly 2"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to detailObj, 3 to assembly2Obj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(3.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            3 to listOf(
                DirectLink(linkId = 12, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(5.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.parts shouldHaveSize 1
        result.assemblies shouldHaveSize 1
        result.parts[0].quantity shouldBe Quantity.Exact(3.0)
        result.assemblies[0].item.parts[0].quantity shouldBe Quantity.Exact(5.0)
    }

    should("skip links with type Unused") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detailObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to detailObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Unused("some-type"), quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.parts shouldHaveSize 1
    }

    should("skip children with PdmType.Unused") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detailObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val unusedObj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Unused"),
            type = PdmType.Unused("unknown-type"),
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to detailObj, 3 to unusedObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.parts shouldHaveSize 1
        result.parts[0].item.id shouldBe 2
    }

    should("return assembly with empty components list when assembly has no children") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Empty Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.all.shouldBeEmpty()
    }

    should("preserve order of components") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detail2Obj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail 2"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detail3Obj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Detail 3"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detail1Obj = PdmObject(
            id = 4,
            designation = Designation.Value("A.4"),
            name = Name.Value("Detail 1"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to detail2Obj, 3 to detail3Obj, 4 to detail1Obj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 12, childId = 4, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.parts shouldHaveSize 3
        result.parts[0].item.designation shouldBe Designation.Value("A.2")
        result.parts[1].item.designation shouldBe Designation.Value("A.3")
        result.parts[2].item.designation shouldBe Designation.Value("A.4")
    }

    should("throw IllegalArgumentException when root not found") {
        val objects = mapOf<Int, PdmObject>()
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(999)
        }

        exception.message shouldContain "999"
    }

    should("throw IllegalArgumentException when root is not Assembly") {
        val detailObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to detailObj)
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(1)
        }

        exception.message shouldContain "Assembly"
    }

    should("throw IllegalArgumentException when child object not found") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 999, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(1)
        }

        exception.message shouldContain "999"
    }

    should("detect cycle A -> B -> A") {
        val assemblyAObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly A"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val assemblyBObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Assembly B"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to assemblyAObj, 2 to assemblyBObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            2 to listOf(
                DirectLink(linkId = 11, childId = 1, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalStateException> {
            client.buildTree(1)
        }

        exception.message shouldContain "Cycle"
    }

    should("not consider same assembly in different branches as cycle") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val branch1Obj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Branch 1"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val branch2Obj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Branch 2"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val sharedObj = PdmObject(
            id = 4,
            designation = Designation.Value("A.4"),
            name = Name.Value("Shared Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to branch1Obj, 3 to branch2Obj, 4 to sharedObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            2 to listOf(
                DirectLink(linkId = 12, childId = 4, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            3 to listOf(
                DirectLink(linkId = 13, childId = 4, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1)

        result.assemblies shouldHaveSize 2
        result.assemblies[0].item.assemblies shouldHaveSize 1
        result.assemblies[1].item.assemblies shouldHaveSize 1
    }

    should("levels = 1: root loads direct children, nested assembly has NotLoaded") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detailObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val nestedAssemblyObj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Nested Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val deepDetailObj = PdmObject(
            id = 4,
            designation = Designation.Value("A.4"),
            name = Name.Value("Deep Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to detailObj, 3 to nestedAssemblyObj, 4 to deepDetailObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            3 to listOf(
                DirectLink(linkId = 12, childId = 4, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val callCounter = CallCountingPdmClient(InMemoryPdmClient(data, objects, links))
        val result = callCounter.buildTree(1, levels = 1)

        result.all shouldHaveSize 2

        val nestedAssembly = result.assemblies[0].item
        nestedAssembly.components.shouldBeInstanceOf<Components.NotLoaded>()

        callCounter.getDirectLinksCallCount shouldBe 1
        callCounter.getDirectLinksCalledWithIds.toSet() shouldBe setOf(1)

        callCounter.getObjectCallCount shouldBe 3
        callCounter.getObjectCalledWithIds.toSet() shouldBe setOf(1, 2, 3)
    }

    should("levels = 2: assembly of level 2 is returned with NotLoaded children") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val level1AssemblyObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Level 1 Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val level2AssemblyObj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Level 2 Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val level3AssemblyObj = PdmObject(
            id = 4,
            designation = Designation.Value("A.4"),
            name = Name.Value("Level 3 Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to level1AssemblyObj, 3 to level2AssemblyObj, 4 to level3AssemblyObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            2 to listOf(
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            3 to listOf(
                DirectLink(linkId = 12, childId = 4, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val result = InMemoryPdmClient(data, objects, links).buildTree(1, levels = 2)

        result.all shouldHaveSize 1

        val level1Assembly = result.assemblies[0].item
        level1Assembly.assemblies shouldHaveSize 1

        val level2Assembly = level1Assembly.assemblies[0].item
        level2Assembly.components.shouldBeInstanceOf<Components.NotLoaded>()
    }

    should("levels = 0 throws IllegalArgumentException") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(1, levels = 0)
        }

        exception.message shouldContain "levels must be >= 1"
    }

    should("levels = -1 throws IllegalArgumentException") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(1, levels = -1)
        }

        exception.message shouldContain "levels must be >= 1"
    }

    should("default levels gives same result as Int.MAX_VALUE") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val nestedAssemblyObj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Nested"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val deepDetailObj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Deep Detail"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to nestedAssemblyObj, 3 to deepDetailObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            ),
            2 to listOf(
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client1 = InMemoryPdmClient(data, objects, links)
        val client2 = InMemoryPdmClient(data, objects, links)

        val resultDefault = client1.buildTree(1)
        val resultMaxInt = client2.buildTree(1, levels = Int.MAX_VALUE)

        resultDefault shouldBe resultMaxInt
    }

    should("assembly without children at levels=1 in root has Loaded(emptyList(), emptyList())") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val result = client.buildTree(1, levels = 1)

        result.all.shouldBeEmpty()
    }

    should("concurrency = 0 throws IllegalArgumentException") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = emptyMap<Int, List<DirectLink>>()

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(1, concurrency = 0)
        }

        exception.message shouldContain "concurrency must be >= 1"
    }

    should("error in one child cancels other requests") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 999, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val client = InMemoryPdmClient(data, objects, links)
        val exception = shouldThrow<IllegalArgumentException> {
            client.buildTree(1)
        }

        exception.message shouldContain "999"
    }

    should("preserve order when responses arrive out of order") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detail1Obj = PdmObject(
            id = 2,
            designation = Designation.Value("A.2"),
            name = Name.Value("Detail 1"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val detail2Obj = PdmObject(
            id = 3,
            designation = Designation.Value("A.3"),
            name = Name.Value("Detail 2"),
            type = PdmType.Detail,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val objects = mapOf(1 to rootObj, 2 to detail1Obj, 3 to detail2Obj)
        val links = mapOf(
            1 to listOf(
                DirectLink(linkId = 10, childId = 2, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing),
                DirectLink(linkId = 11, childId = 3, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            )
        )

        val data = SpecificationData("", "", emptyList())
        val delayedClient = DelayedPdmClient(InMemoryPdmClient(data, objects, links), mapOf(2 to 100, 3 to 10))
        val result = delayedClient.buildTree(1)

        result.parts shouldHaveSize 2
        result.parts[0].item.id shouldBe 2
        result.parts[1].item.id shouldBe 3
    }

    should("parallelism: 5 children with 100ms each, concurrency=8, total time < 400ms") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val childIds = (2..6)
        val objects = mapOf(1 to rootObj) + childIds.associateWith { id ->
            PdmObject(
                id = id,
                designation = Designation.Value("A.$id"),
                name = Name.Value("Detail $id"),
                type = PdmType.Detail,
                state = "active",
                version = "1.0",
                position = Field.Missing
            )
        }

        val links = mapOf(
            1 to childIds.map { id ->
                DirectLink(linkId = 10 + id, childId = id, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            }
        )

        val data = SpecificationData("", "", emptyList())
        val delayedClient = DelayedPdmClient(InMemoryPdmClient(data, objects, links), childIds.associateWith { 100 })

        val start = System.currentTimeMillis()
        val result = delayedClient.buildTree(1, concurrency = 8)
        val elapsed = System.currentTimeMillis() - start

        result.parts shouldHaveSize 5
        (elapsed < 400).shouldBe(true)
    }

    should("parallelism: 5 children with 100ms each, concurrency=1, total time >= 500ms") {
        val rootObj = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Assembly"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val childIds = (2..6)
        val objects = mapOf(1 to rootObj) + childIds.associateWith { id ->
            PdmObject(
                id = id,
                designation = Designation.Value("A.$id"),
                name = Name.Value("Detail $id"),
                type = PdmType.Detail,
                state = "active",
                version = "1.0",
                position = Field.Missing
            )
        }

        val links = mapOf(
            1 to childIds.map { id ->
                DirectLink(linkId = 10 + id, childId = id, type = DirectLinkType.Composition, quantity = Quantity.Exact(1.0), unit = Field.Missing)
            }
        )

        val data = SpecificationData("", "", emptyList())
        val delayedClient = DelayedPdmClient(InMemoryPdmClient(data, objects, links), childIds.associateWith { 100 })

        val start = System.currentTimeMillis()
        val result = delayedClient.buildTree(1, concurrency = 1)
        val elapsed = System.currentTimeMillis() - start

        result.parts shouldHaveSize 5
        (elapsed >= 500).shouldBe(true)
    }

    should("put details into parts and assemblies into assemblies, keeping link order within each list") {
        fun obj(id: Int, type: PdmType, position: Field<Int> = Field.Missing) = PdmObject(
            id = id,
            designation = Designation.Value("D.$id"),
            name = Name.Value("N$id"),
            type = type,
            state = "active",
            version = "1.0",
            position = position
        )
        fun link(id: Int, childId: Int) = DirectLink(
            linkId = id,
            childId = childId,
            type = DirectLinkType.Composition,
            quantity = Quantity.Exact(1.0),
            unit = Field.Missing
        )

        val objects = listOf(
            obj(1, PdmType.Assembly),
            obj(2, PdmType.Detail),
            obj(3, PdmType.Assembly),
            obj(4, PdmType.Detail),
            obj(5, PdmType.Assembly),
            obj(6, PdmType.Unused("other")),
        ).associateBy { it.id }
        val links = mapOf(1 to listOf(link(10, 4), link(11, 3), link(12, 6), link(13, 2), link(14, 5)))

        val result = InMemoryPdmClient(SpecificationData("", "", emptyList()), objects, links).buildTree(1)

        result.parts.map { it.item.id } shouldBe listOf(4, 2)
        result.assemblies.map { it.item.id } shouldBe listOf(3, 5)
        result.all shouldHaveSize 4
    }

    should("give an empty assembly Loaded with both lists empty") {
        val root = PdmObject(
            id = 1,
            designation = Designation.Value("A.1"),
            name = Name.Value("Root"),
            type = PdmType.Assembly,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )

        val result = InMemoryPdmClient(SpecificationData("", "", emptyList()), mapOf(1 to root), emptyMap()).buildTree(1)

        result.parts.shouldBeEmpty()
        result.assemblies.shouldBeEmpty()
    }

    should("put standard products into standardProducts keeping link order and take quantity from link") {
        fun obj(id: Int, type: PdmType) = PdmObject(
            id = id,
            designation = Designation.Missing,
            name = Name.Value("N$id"),
            type = type,
            state = "active",
            version = "1.0",
            position = Field.Value(id)
        )
        fun link(id: Int, childId: Int, qty: Double) = DirectLink(
            linkId = id,
            childId = childId,
            type = DirectLinkType.Composition,
            quantity = Quantity.Exact(qty),
            unit = Field.Value("шт")
        )

        val objects = listOf(
            obj(1, PdmType.Assembly),
            obj(2, PdmType.StandardProduct),
            obj(3, PdmType.Detail),
            obj(4, PdmType.StandardProduct),
        ).associateBy { it.id }
        val links = mapOf(1 to listOf(link(10, 4, 8.0), link(11, 3, 1.0), link(12, 2, 4.0)))

        val result = InMemoryPdmClient(SpecificationData("", "", emptyList()), objects, links).buildTree(1)

        result.standardProducts.map { it.item.id } shouldBe listOf(4, 2)
        result.standardProducts[0].quantity shouldBe Quantity.Exact(8.0)
        result.standardProducts[0].position shouldBe Field.Value(4)
        result.parts.map { it.item.id } shouldBe listOf(3)
        result.assemblies.shouldBeEmpty()
        result.all.map { it.item.id } shouldBe listOf(2, 3, 4)
    }

    should("load standard products when children of nested assembly are limited by levels") {
        fun obj(id: Int, type: PdmType) = PdmObject(
            id = id,
            designation = Designation.Missing,
            name = Name.Value("N$id"),
            type = type,
            state = "active",
            version = "1.0",
            position = Field.Missing
        )
        val objects = listOf(
            obj(1, PdmType.Assembly),
            obj(2, PdmType.Assembly),
            obj(3, PdmType.StandardProduct),
        ).associateBy { it.id }
        val composition = { id: Int, childId: Int ->
            DirectLink(id, childId, DirectLinkType.Composition, Quantity.Exact(1.0), Field.Missing)
        }
        val links = mapOf(1 to listOf(composition(10, 2), composition(11, 3)), 2 to listOf(composition(12, 3)))

        val result = InMemoryPdmClient(SpecificationData("", "", emptyList()), objects, links).buildTree(1, levels = 1)

        result.standardProducts.map { it.item.id } shouldBe listOf(3)
        result.assemblies.single().item.isLoaded shouldBe false
    }

    should("put every supported child type into its own list") {
        fun obj(id: Int, type: PdmType) = PdmObject(
            id = id,
            designation = Designation.Missing,
            name = Name.Value("N$id"),
            type = type,
            state = "active",
            version = "1.0",
            position = Field.Value(id)
        )
        fun link(id: Int, childId: Int) = DirectLink(
            linkId = id,
            childId = childId,
            type = DirectLinkType.Composition,
            quantity = Quantity.Exact(2.0),
            unit = Field.Value("шт")
        )

        val objects = listOf(
            obj(1, PdmType.Assembly),
            obj(2, PdmType.Detail),
            obj(3, PdmType.Assembly),
            obj(4, PdmType.StandardProduct),
            obj(5, PdmType.OtherProduct),
            obj(6, PdmType.OtherProduct),
            obj(7, PdmType.Unused("Материал")),
        ).associateBy { it.id }
        val links = mapOf(1 to listOf(link(10, 6), link(11, 7), link(12, 2), link(13, 3), link(14, 4), link(15, 5)))

        val result = InMemoryPdmClient(SpecificationData("", "", emptyList()), objects, links).buildTree(1)

        result.parts.map { it.item.id } shouldBe listOf(2)
        result.assemblies.map { it.item.id } shouldBe listOf(3)
        result.standardProducts.map { it.item.id } shouldBe listOf(4)
        result.otherProducts.map { it.item.id } shouldBe listOf(6, 5)
        result.otherProducts[0].quantity shouldBe Quantity.Exact(2.0)
        result.otherProducts[0].position shouldBe Field.Value(6)
        result.all.map { it.item.id } shouldBe listOf(2, 3, 4, 5, 6)
    }

})

class CallCountingPdmClient(private val delegate: PdmClient) : PdmClient by delegate {
    private val _getObjectCallCount = AtomicInteger(0)
    private val _getDirectLinksCallCount = AtomicInteger(0)
    private val _getObjectCalledWithIds = ConcurrentLinkedQueue<Int>()
    private val _getDirectLinksCalledWithIds = ConcurrentLinkedQueue<Int>()

    val getObjectCallCount: Int get() = _getObjectCallCount.get()
    val getDirectLinksCallCount: Int get() = _getDirectLinksCallCount.get()
    val getObjectCalledWithIds: List<Int> get() = _getObjectCalledWithIds.toList()
    val getDirectLinksCalledWithIds: List<Int> get() = _getDirectLinksCalledWithIds.toList()

    override suspend fun getObject(objectId: Int): PdmObjectResult {
        _getObjectCallCount.incrementAndGet()
        _getObjectCalledWithIds.add(objectId)
        return delegate.getObject(objectId)
    }

    override suspend fun getDirectLinks(objectId: Int): List<DirectLink> {
        _getDirectLinksCallCount.incrementAndGet()
        _getDirectLinksCalledWithIds.add(objectId)
        return delegate.getDirectLinks(objectId)
    }
}

class DelayedPdmClient(
    private val delegate: PdmClient,
    private val delays: Map<Int, Long>
) : PdmClient by delegate {
    override suspend fun getObject(objectId: Int): PdmObjectResult {
        delays[objectId]?.let { delay(it) }
        return delegate.getObject(objectId)
    }
}
