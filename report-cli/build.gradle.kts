plugins {
    application
}

application {
    mainClass.set("dev.reportgenerator.cli.MainKt")
}

dependencies {
    implementation(project(":reports:specification"))
    implementation(project(":report-layout"))
    implementation(project(":report-template"))
    implementation(project(":report-render-svg"))
    implementation(project(":report-render-pdf"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

// ./gradlew :report-cli:runTemplate -Pargs="my.yaml out --pages 2 --data data.yaml"  (no args = bundled sheet-frame.yaml -> output/)
tasks.register<JavaExec>("runTemplate") {
    group = "application"
    description = "Render a YAML template (arg 0) to SVG + PDF in a directory (arg 1)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.reportgenerator.cli.TemplateMainKt")
    workingDir = projectDir
    val extra = providers.gradleProperty("args")
    argumentProviders.add(CommandLineArgumentProvider { extra.orNull?.split(" ")?.filter { it.isNotBlank() } ?: emptyList() })
}
