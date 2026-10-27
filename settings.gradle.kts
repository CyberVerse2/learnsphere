pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the Java 25 toolchain automatically when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "learnsphere"

include("core", "pdf", "search", "ai", "app", "testbench")
