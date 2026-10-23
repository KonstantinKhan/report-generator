package dev.reportgenerator.loodsman

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

class LimiterTest : ShouldSpec({

    should("run no more blocks at once than concurrency") {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        Limiter(2).withPermits((1..10).toList()) {
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(5)
            inFlight.decrementAndGet()
        }

        maxInFlight.get() shouldBeLessThanOrEqualTo 2
    }

    should("return results keyed by id") {
        Limiter(3).withPermits(listOf(1, 2, 3)) { it * 10 } shouldBe mapOf(1 to 10, 2 to 20, 3 to 30)
    }
})
