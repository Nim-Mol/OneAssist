// Lets the pure Kotlin logic build and test on its own, without the Android SDK:
//   gradle -p core test
// When building the whole app from the repo root, this file is ignored.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "core"
