package dev.reportgenerator.loodsman

import io.github.konstantinkhan.loodsman.LoodsmanApi

fun interface MetadataSource {
    suspend fun snapshot(): MetadataSnapshot
}

internal fun lazyMetadataSource(api: LoodsmanApi): MetadataSource {
    val lazy = SuspendLazy { MetadataLoader.load(api) }
    return MetadataSource { lazy.get() }
}
