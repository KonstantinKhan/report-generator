package dev.reportgenerator.loodsman

/** Для тестов, которые не читают метаданные: любое обращение к ним — ошибка теста. */
internal val unusedMetadata = MetadataSource { error("metadata is not expected in this test") }
