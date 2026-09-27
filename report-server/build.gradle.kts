plugins {
    application
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("dev.reportgenerator.server.MainKt")
}

dependencies {
    implementation(project(":report-loodsman"))
    implementation(project(":report-api"))
    implementation(project(":report-data"))
    implementation(project(":reports:specification"))
    implementation(project(":report-layout"))
    implementation(project(":report-render-pdf"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)

    testImplementation(project(":report-api"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
