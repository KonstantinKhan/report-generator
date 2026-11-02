package dev.reportgenerator.loodsman

fun interface MetadataSource {
    suspend fun snapshot(): MetadataSnapshot
}
