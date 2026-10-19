package dev.reportgenerator.pdm

import dev.reportgenerator.domain.Assembly
import dev.reportgenerator.domain.Component
import dev.reportgenerator.domain.Components
import dev.reportgenerator.domain.DirectLink
import dev.reportgenerator.domain.DirectLinkType
import dev.reportgenerator.domain.Item
import dev.reportgenerator.domain.OtherProduct
import dev.reportgenerator.domain.Part
import dev.reportgenerator.domain.PdmClient
import dev.reportgenerator.domain.PdmObject
import dev.reportgenerator.domain.PdmObjectResult
import dev.reportgenerator.domain.PdmType
import dev.reportgenerator.domain.StandardProduct
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

suspend fun PdmClient.buildTree(
    rootId: Int,
    levels: Int = Int.MAX_VALUE,
    concurrency: Int = 8
): Assembly {
    require(levels >= 1) { "levels must be >= 1, but was $levels" }
    require(concurrency >= 1) { "concurrency must be >= 1, but was $concurrency" }

    val semaphore = Semaphore(concurrency)

    val root = loadObject(rootId, semaphore, "Root object")
    require(root.type == PdmType.Assembly) {
        "Root object with id=$rootId must be of type Assembly, but is ${root.type}"
    }

    return buildAssembly(root, emptySet(), levels, 0, semaphore)
}

private sealed interface LoadedChild {
    data class OfPart(val component: Component<Part>) : LoadedChild
    data class OfAssembly(val component: Component<Assembly>) : LoadedChild
    data class OfStandardProduct(val component: Component<StandardProduct>) : LoadedChild
    data class OfOtherProduct(val component: Component<OtherProduct>) : LoadedChild
}

private suspend fun PdmClient.loadObject(id: Int, semaphore: Semaphore, role: String): PdmObject =
    when (val result = semaphore.withPermit { getObject(id) }) {
        is PdmObjectResult.Found -> result.value
        is PdmObjectResult.NotFound -> throw IllegalArgumentException("$role with id=$id not found")
    }

private suspend fun PdmClient.buildAssembly(
    obj: PdmObject,
    ancestors: Set<Int>,
    levels: Int,
    currentLevel: Int,
    semaphore: Semaphore
): Assembly {
    if (currentLevel >= levels) return obj.toAssembly(Components.NotLoaded)

    val path = ancestors + obj.id
    val links = semaphore.withPermit { getDirectLinks(obj.id) }
        .filter { it.type == DirectLinkType.Composition }

    val children = coroutineScope {
        links.map { link -> async { loadChild(link, obj.id, path, levels, currentLevel, semaphore) } }.awaitAll()
    }.filterNotNull()

    val components = Components.Loaded(
        parts = children.filterIsInstance<LoadedChild.OfPart>().map { it.component },
        assemblies = children.filterIsInstance<LoadedChild.OfAssembly>().map { it.component },
        standardProducts = children.filterIsInstance<LoadedChild.OfStandardProduct>().map { it.component },
        otherProducts = children.filterIsInstance<LoadedChild.OfOtherProduct>().map { it.component }
    )
    return obj.toAssembly(components)
}

private suspend fun PdmClient.loadChild(
    link: DirectLink,
    parentId: Int,
    path: Set<Int>,
    levels: Int,
    currentLevel: Int,
    semaphore: Semaphore
): LoadedChild? {
    val child = loadObject(link.childId, semaphore, "Child object")
    if (child.type is PdmType.Unused) return null
    check(child.id !in path) { "Cycle detected: object $parentId leads back to ancestor ${child.id}" }

    return when (child.type) {
        PdmType.Assembly -> {
            val assembly = buildAssembly(child, path, levels, currentLevel + 1, semaphore)
            LoadedChild.OfAssembly(link.toComponent(assembly, child))
        }
        PdmType.Detail -> LoadedChild.OfPart(link.toComponent(child.toPart(), child))
        PdmType.StandardProduct -> LoadedChild.OfStandardProduct(link.toComponent(child.toStandardProduct(), child))
        PdmType.OtherProduct -> LoadedChild.OfOtherProduct(link.toComponent(child.toOtherProduct(), child))
        is PdmType.Unused -> null
    }
}

private fun <T : Item> DirectLink.toComponent(item: T, child: PdmObject) =
    Component(item = item, quantity = quantity, unit = unit, position = child.position)

private fun PdmObject.toPart() = Part(
    id = id,
    designation = designation,
    name = name,
    state = state,
    version = version
)

private fun PdmObject.toStandardProduct() = StandardProduct(
    id = id,
    designation = designation,
    name = name,
    state = state,
    version = version
)

private fun PdmObject.toOtherProduct() = OtherProduct(
    id = id,
    designation = designation,
    name = name,
    state = state,
    version = version
)

private fun PdmObject.toAssembly(components: Components) = Assembly(
    id = id,
    designation = designation,
    name = name,
    state = state,
    version = version,
    components = components
)
