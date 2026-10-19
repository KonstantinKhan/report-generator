plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.test {
    useJUnitPlatform()
}
