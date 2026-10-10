pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        kotlin("multiplatform") version "2.4.20"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../android/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "xan-ios"
include(":iosShared")
project(":iosShared").projectDir = file("shared")
