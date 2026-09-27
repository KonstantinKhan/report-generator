plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":report-api"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
