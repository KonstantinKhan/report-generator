package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.PdmNodeStructureClient
import dev.reportgenerator.loodsman.mapping.MappingConfig
import dev.reportgenerator.loodsman.mapping.MappingConfigValidator
import io.github.konstantinkhan.loodsman.LoodsmanApi

/**
 * Согласованный набор Loodsman-компонентов: клиент структуры и кэш метаданных, которые работают с одним маппингом.
 * Создаётся только через [bootstrap], поэтому маппинг всегда проверен против метаданных Loodsman.
 */
class LoodsmanContext private constructor(
    val client: PdmNodeStructureClient,
    val metadata: MetadataCache,
) {

    companion object {

        /**
         * Загружает метаданные и проверяет [mapping] против них. Ошибка загрузки или любые ошибки проверки
         * пробрасываются, контекст не создаётся: приложение не должно стартовать с неполными метаданными.
         */
        suspend fun bootstrap(api: LoodsmanApi, mapping: MappingConfig): LoodsmanContext {
            val metadata = MetadataCache(api) { MappingConfigValidator.validate(mapping, it) }
            metadata.initialize()
            val client = LoodsmanProductStructureClient(api, mapping, metadata = metadata)
            return LoodsmanContext(client, metadata)
        }
    }
}
