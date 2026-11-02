pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "binnacle"
// The dash. Folders stay as app/build.sh and capture/build.sh expect them (src/, res/, assets/,
// AndroidManifest.xml in the module folder), so both builds work from the same tree.
include(":app")
// The optional drive logger, a separate APK signed with the same key.
include(":logger")
project(":logger").projectDir = file("capture")
