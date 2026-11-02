package dev.reportgenerator.loodsman

/**
 * Результат проверки и обновления снимка метаданных в [MetadataCache.refresh].
 */
sealed interface RefreshResult {

    data class Updated(val oldVersion: Long, val newVersion: Long) : RefreshResult

    data class Unchanged(val version: Long) : RefreshResult

    /** Старый снимок сохранён. */
    data class Failed(val cause: Throwable) : RefreshResult
}
