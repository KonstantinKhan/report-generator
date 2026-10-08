plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    tasks.withType<Test> {
        useJUnitPlatform()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }
    }
}
