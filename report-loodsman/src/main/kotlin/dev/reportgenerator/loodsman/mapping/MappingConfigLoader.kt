package dev.reportgenerator.loodsman.mapping

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.PolymorphismStyle
import kotlinx.serialization.decodeFromString
import java.io.File

object MappingConfigLoader {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            polymorphismStyle = PolymorphismStyle.Property,
            polymorphismPropertyName = "type"
        )
    )

    fun load(env: Map<String, String> = System.getenv()): MappingConfig {
        val text = env["LOODSMAN_MAPPING_FILE"]?.let { filePath ->
            File(filePath).readText()
        } ?: MappingConfigLoader::class.java.classLoader.getResource("loodsman-mapping.yaml")?.readText()
            ?: throw IllegalStateException(
                "Mapping config not found. Set LOODSMAN_MAPPING_FILE env var or provide /loodsman-mapping.yaml in classpath"
            )

        return parse(text)
    }

    fun parse(text: String): MappingConfig {
        val mapping = yaml.decodeFromString(MappingConfig.serializer(), text)
        mapping.validate()
        return mapping
    }
}
