package dev.reportgenerator.server

import dev.reportgenerator.layout.DefaultFontRegistry
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File

// The refresh route does not write to the output directory, so any existing directory is enough.
private val outputDir = File(System.getProperty("java.io.tmpdir"))

class MetadataRefreshRouteTest : ShouldSpec({

    should("return updated with the new version when the remote version grew") {
        val remote = FakeRemoteMetadata()
        val cache = remote.cache()
        cache.initialize()
        remote.version = 5

        testApplication {
            application {
                reportServerModule(FakeProductStructureClient(), outputDir, DefaultFontRegistry.load(), cache)
            }

            val response = client.post("/admin/metadata/refresh")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{"status":"updated","version":5}"""
        }
    }

    should("return unchanged without loading when the remote version is the same") {
        val remote = FakeRemoteMetadata()
        val cache = remote.cache()
        cache.initialize()

        testApplication {
            application {
                reportServerModule(FakeProductStructureClient(), outputDir, DefaultFontRegistry.load(), cache)
            }

            val response = client.post("/admin/metadata/refresh")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{"status":"unchanged","version":1}"""
        }
        remote.loads.get() shouldBe 1
    }

    should("pass force=true to the cache and reload although the version is the same") {
        val remote = FakeRemoteMetadata()
        val cache = remote.cache()
        cache.initialize()

        testApplication {
            application {
                reportServerModule(FakeProductStructureClient(), outputDir, DefaultFontRegistry.load(), cache)
            }

            val response = client.post("/admin/metadata/refresh?force=true")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{"status":"unchanged","version":1}"""
        }
        remote.loads.get() shouldBe 2
    }

    should("return 502 with the reason and keep the old snapshot when the refresh fails") {
        val remote = FakeRemoteMetadata()
        val cache = remote.cache()
        cache.initialize()
        remote.version = 5
        remote.loadError = IllegalStateException("metadata source is down")

        testApplication {
            application {
                reportServerModule(FakeProductStructureClient(), outputDir, DefaultFontRegistry.load(), cache)
            }

            val response = client.post("/admin/metadata/refresh")

            response.status shouldBe HttpStatusCode.BadGateway
            response.bodyAsText() shouldContain "metadata source is down"
        }
        cache.snapshot().version shouldBe 1
    }
})
