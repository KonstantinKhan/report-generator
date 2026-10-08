plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(projects.reportApi)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}
