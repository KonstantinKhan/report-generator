package khan366kos.loodsman.api.dsl

interface Builder<T> {
    suspend fun build(): T
}