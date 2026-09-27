package dev.reportgenerator.server

import io.ktor.server.application.ApplicationEnvironment

// Loodsman credentials are mandatory: the server must fail fast at startup with a clear message
// if they are missing, rather than silently starting with defaults. REPORT_OUTPUT_DIR and
// SERVER_PORT have sane defaults and are allowed to be omitted.
data class AppConfig(
    val loodsmanBaseUrl: String,
    val loodsmanDbName: String,
    val loodsmanUsername: String,
    val loodsmanPassword: String,
    val outputDir: String,
    val serverPort: Int,
) {
    constructor(environment: ApplicationEnvironment) : this(
        loodsmanBaseUrl = requiredEnv("LOODSMAN_BASE_URL"),
        loodsmanDbName = requiredEnv("LOODSMAN_DB_NAME"),
        loodsmanUsername = requiredEnv("LOODSMAN_USERNAME"),
        loodsmanPassword = requiredEnv("LOODSMAN_PASSWORD"),
        outputDir = optionalEnv("REPORT_OUTPUT_DIR") ?: DEFAULT_OUTPUT_DIR,
        serverPort = optionalEnv("SERVER_PORT")?.let {
            it.toIntOrNull() ?: throw IllegalStateException("SERVER_PORT must be a valid integer, got: '$it'")
        } ?: DEFAULT_SERVER_PORT,
    ) {
        environment.log.info("AppConfig loaded: port=$serverPort, outputDir=$outputDir")
    }

    companion object {
        private const val DEFAULT_OUTPUT_DIR = "/data/reports"
        private const val DEFAULT_SERVER_PORT = 8080

        private fun requiredEnv(name: String): String =
            optionalEnv(name) ?: throw IllegalStateException(
                "Missing required environment variable: $name. " +
                    "Set it before starting report-server (see Dockerfile / docker run -e $name=...)."
            )

        private fun optionalEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}
