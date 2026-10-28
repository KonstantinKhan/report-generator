package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.loodsman.mapping.KindMapper
import dev.reportgenerator.loodsman.mapping.MappingConfigLoader
import io.github.konstantinkhan.loodsman.LoodsmanApi
import io.github.konstantinkhan.loodsman.LoodsmanApiException
import io.github.konstantinkhan.loodsman.LoodsmanConfig
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.installLoodsman
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import org.slf4j.LoggerFactory

class TreeAssemblerTest : ShouldSpec({

    val mapping = MappingConfigLoader.load(emptyMap())
    // Сборщик количества не обращается к Loodsman: любой запрос в этом тесте — ошибка.
    val api = LoodsmanApi(
        LoodsmanConfig("http://localhost", "db", "user", "pass"),
        HttpClient(MockEngine { error("Loodsman is not expected in this test") }) { installLoodsman() },
    )
    val assembler = TreeAssembler(
        mapping,
        NodeLoader(
            api,
            mapping,
            KindMapper(mapping, LoggerFactory.getLogger("TreeAssemblerTest")),
            AttributeRequests(mapping),
        ),
        LinkAttributeLoader(api, mapping),
    )

    should("return Missing when both quantity bounds are empty") {
        assembler.quantityOf(linkDto(min = null, max = null)) shouldBe Quantity.Missing
    }

    should("return Exact when quantity bounds are equal") {
        assembler.quantityOf(linkDto(min = 2.0, max = 2.0)) shouldBe Quantity.Exact(2.0)
    }

    should("return Range when quantity bounds differ") {
        assembler.quantityOf(linkDto(min = 1.0, max = 3.0)) shouldBe Quantity.Range(1.0, 3.0)
    }

    should("fail when only one quantity bound is filled") {
        val error = shouldThrow<LoodsmanApiException> { assembler.quantityOf(linkDto(min = 1.0, max = null)) }
        error.message shouldBe "Link 7: quantity range is half-filled (min=1.0, max=null)"
    }
})

private fun linkDto(min: Double?, max: Double?) = GetLinkedObjectsForObjectsOutputDto_V4(
    idLink = 7,
    idParent = 1,
    idChild = 2,
    idLinkType = 1,
    idType = 0,
    idState = 0,
    minQuantity = min,
    maxQuantity = max,
)
