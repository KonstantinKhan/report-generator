package dev.reportgenerator.server

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class AppConfigTest : ShouldSpec({

    should("add https scheme to a host with port") {
        normalizeLoodsmanBaseUrl("loodsman.example.local:8076") shouldBe "https://loodsman.example.local:8076"
    }

    should("add https scheme to a bare host") {
        normalizeLoodsmanBaseUrl("loodsman.example.local") shouldBe "https://loodsman.example.local"
    }

    should("keep an explicit https scheme") {
        normalizeLoodsmanBaseUrl("https://loodsman.example.local:8076") shouldBe "https://loodsman.example.local:8076"
    }

    should("keep an explicit http scheme as given") {
        normalizeLoodsmanBaseUrl("http://loodsman.example.local:8076") shouldBe "http://loodsman.example.local:8076"
    }

    should("ignore surrounding whitespace before adding the scheme") {
        normalizeLoodsmanBaseUrl("  loodsman.example.local:8076 ") shouldBe "https://loodsman.example.local:8076"
    }
})
