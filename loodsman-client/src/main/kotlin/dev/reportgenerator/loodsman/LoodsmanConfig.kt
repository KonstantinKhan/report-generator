package dev.reportgenerator.loodsman

data class LoodsmanConfig(
    val baseUrl: String,
    val dbName: String,
    val username: String,
    val password: String,
) {
    override fun toString(): String =
        "LoodsmanConfig(baseUrl=$baseUrl, dbName=$dbName, username=$username, password=***)"
}
