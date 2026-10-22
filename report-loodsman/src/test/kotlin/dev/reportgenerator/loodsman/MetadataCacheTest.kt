package dev.reportgenerator.loodsman

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigValidator
import dev.reportgenerator.loodsman.mapping.FieldSource
import dev.reportgenerator.loodsman.mapping.IdentityMapping
import io.github.konstantinkhan.loodsman.PropField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/** Удалённый сервер метаданных: версия и ошибки меняются тестом. */
private class RemoteMetadata {
    var version = 1L
    var versionError: Throwable? = null
    var loadError: Throwable? = null
    var linkTypeNames = listOf("Документы")
    var beforeLoad: (suspend () -> Unit)? = null
    val loads = AtomicInteger()

    fun cache(validate: (MetadataSnapshot) -> List<String> = { emptyList() }) = MetadataCache(
        versionOf = {
            versionError?.let { throw it }
            version
        },
        loadSnapshot = {
            loads.incrementAndGet()
            beforeLoad?.invoke()
            loadError?.let { throw it }
            MetadataSnapshot(
                linkTypes = linkTypeNames.mapIndexed { index, name -> LinkTypeInfo(id = index + 1, name = name) },
                objectTypes = listOf(ObjectTypeInfo(id = 1, typeName = "Деталь")),
                attributeNames = setOf("Наименование"),
                version = version,
            )
        },
        validate = validate,
    )
}

private fun RefreshResult.failureMessage(): String =
    (this as? RefreshResult.Failed)?.cause?.message.orEmpty()

class MetadataCacheTest : ShouldSpec({

    should("load the first snapshot on initialize") {
        val remote = RemoteMetadata()
        val cache = remote.cache()

        cache.initialize()

        cache.snapshot().version shouldBe 1
        remote.loads.get() shouldBe 1
    }

    should("throw on initialize when the first load fails") {
        val remote = RemoteMetadata().apply { loadError = IllegalStateException("boom") }
        val cache = remote.cache()

        val error = shouldThrow<IllegalStateException> { cache.initialize() }

        error.message shouldBe "boom"
    }

    should("throw on initialize when the first snapshot fails validation") {
        val remote = RemoteMetadata()
        val cache = remote.cache { listOf("no link type Документы") }

        val error = shouldThrow<IllegalStateException> { cache.initialize() }

        error.message!! shouldContain "no link type Документы"
    }

    should("reject a mapping that does not match Loodsman and keep the old snapshot on refresh") {
        val mapping = MappingConfig(
            kinds = mapOf("Деталь" to "PART"),
            relations = mapOf("Документы" to "DOCUMENT"),
            requiredRelations = setOf("DOCUMENT"),
            identity = IdentityMapping(
                designation = FieldSource.Prop(PropField.PRODUCT),
                name = FieldSource.Attribute("Наименование"),
            ),
        )
        val remote = RemoteMetadata()
        val cache = remote.cache { MappingConfigValidator.validate(mapping, it) }
        cache.initialize()
        remote.version = 5
        remote.linkTypeNames = listOf("Другая связь")

        val result = cache.refresh()

        result.failureMessage() shouldContain "relations: link type 'Документы' not found in Loodsman"
        result.failureMessage() shouldContain "tried: Документы"
        cache.snapshot().version shouldBe 1
    }

    should("throw on snapshot before initialize") {
        val cache = RemoteMetadata().cache()

        shouldThrow<IllegalStateException> { cache.snapshot() }
    }

    should("not load when the remote version is the same") {
        val remote = RemoteMetadata()
        val cache = remote.cache()
        cache.initialize()

        val result = cache.refresh()

        result shouldBe RefreshResult.Unchanged(1)
        remote.loads.get() shouldBe 1
    }

    should("reload on force even when the remote version is the same") {
        val remote = RemoteMetadata()
        val cache = remote.cache()
        cache.initialize()

        val result = cache.refresh(force = true)

        result shouldBe RefreshResult.Unchanged(1)
        remote.loads.get() shouldBe 2
    }

    should("replace the whole snapshot when the remote version grew") {
        val remote = RemoteMetadata()
        val cache = remote.cache()
        cache.initialize()
        remote.version = 5

        val result = cache.refresh()

        result shouldBe RefreshResult.Updated(oldVersion = 1, newVersion = 5)
        cache.snapshot().version shouldBe 5
    }

    should("keep the old snapshot and return Failed when the load fails") {
        val remote = RemoteMetadata()
        val cache = remote.cache()
        cache.initialize()
        remote.version = 5
        remote.loadError = IllegalStateException("load failed")

        val result = cache.refresh()

        result shouldBe RefreshResult.Failed(remote.loadError!!)
        result.failureMessage() shouldBe "load failed"
        cache.snapshot().version shouldBe 1
    }

    should("keep the old snapshot and return Failed with validation errors") {
        val remote = RemoteMetadata()
        val cache = remote.cache { snapshot ->
            if (snapshot.version == 5L) listOf("no link type Документы", "no object types") else emptyList()
        }
        cache.initialize()
        remote.version = 5

        val result = cache.refresh()

        result.failureMessage() shouldContain "no link type Документы; no object types"
        cache.snapshot().version shouldBe 1
    }

    should("keep the old snapshot and return Failed when the version request fails") {
        val remote = RemoteMetadata()
        val cache = remote.cache()
        cache.initialize()
        remote.versionError = IllegalStateException("version failed")

        val result = cache.refresh()

        result.failureMessage() shouldBe "version failed"
        cache.snapshot().version shouldBe 1
        remote.loads.get() shouldBe 1
    }

    should("load once when two refreshes run in parallel") {
        val remote = RemoteMetadata()
        val cache = remote.cache()
        cache.initialize()
        remote.version = 5
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        remote.beforeLoad = {
            started.complete(Unit)
            release.await()
        }

        val results = coroutineScope {
            val first = async { cache.refresh() }
            started.await()
            val second = async { cache.refresh() }
            release.complete(Unit)
            awaitAll(first, second)
        }

        results.filterIsInstance<RefreshResult.Updated>() shouldHaveSize 1
        results.filterIsInstance<RefreshResult.Unchanged>() shouldHaveSize 1
        remote.loads.get() shouldBe 2
    }
})
