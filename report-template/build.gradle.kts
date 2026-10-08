plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportGeometry)
    implementation(libs.kaml)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
