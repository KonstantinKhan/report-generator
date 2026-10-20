package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

internal sealed class PropObjectResult {
    data class Found(val value: PropObjectOutputDto) : PropObjectResult()
    data class NotFound(val versionId: Int) : PropObjectResult()
}
