plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("dev.reportgenerator.server.MainKt")
}

dependencies {
    implementation(projects.reportLoodsman)
    implementation(projects.reportApi)
    implementation(projects.reportData)
    implementation(projects.reports.specification)
    implementation(projects.reportLayout)
    implementation(projects.reportRenderPdf)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
