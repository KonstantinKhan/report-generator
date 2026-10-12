package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.PdmDatabase
import dev.reportgenerator.loodsman.dto.DatabaseOutputDto

internal fun DatabaseOutputDto.toDomain() = PdmDatabase(
    name = name ?: ""
)