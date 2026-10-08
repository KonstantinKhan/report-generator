plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportGeometry)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
