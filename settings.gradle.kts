rootProject.name = "report-engine"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "report-geometry",
    "report-ir",
    "report-layout-ir",
    "report-layout",
    "report-template",
    "report-render-svg",
    "report-render-pdf",
    "report-domain",
    "report-pdm",
    "reports:specification",
    "report-cli",
    "loodsman-client",
    "report-loodsman",
    "report-server",
)
