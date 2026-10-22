package dev.reportgenerator.server

import dev.reportgenerator.loodsman.MetadataCache
import dev.reportgenerator.loodsman.MetadataSnapshot
import java.util.concurrent.atomic.AtomicInteger

// Stands in for Loodsman metadata: the cache version and load errors are set by the test.
class FakeRemoteMetadata {
    var version = 1L
    var versionError: Throwable? = null
    var loadError: Throwable? = null
    val loads = AtomicInteger(0)

    fun cache() = MetadataCache(
        versionOf = {
            versionError?.let { throw it }
            version
        },
        loadSnapshot = {
            loads.incrementAndGet()
            loadError?.let { throw it }
            MetadataSnapshot(linkTypes = emptyList(), objectTypes = emptyList(), version = version)
        },
        validate = { emptyList() },
    )
}
